import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { backZoneId, cameras, safetyMap } from '../../../tests/fixtures/safety-data';
import { eventId, memberId, zoneId } from '../../../tests/fixtures/workflow-data';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { SafetyCommands, parsePoints } from './commands';
import { parseCameraPage, parseSafetyMap } from './contract';

const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const receipt = { id: '10000000-0000-4000-8000-000000000071', revision: '2', eventRevision: '5' };
const square = [
    { x: 0.1, y: 0.1 },
    { x: 0.4, y: 0.1 },
    { x: 0.4, y: 0.4 },
];
const crewRule = {
    workGroupId: '10000000-0000-4000-8000-000000000004',
    minimumCount: 2,
    startAt: '2026-11-18T00:00:00Z',
    endAt: '2026-11-18T02:00:00Z',
    evidence: {
        sourceKind: 'MANUAL_CONFIRMATION',
        sourceRef: 'fixture:crew',
        observedAt: null,
        recordedAt: '2026-11-04T03:00:00Z',
        confirmedByMemberId: memberId,
        confirmed: true,
    },
};

describe('안전 지도/카메라 설정 명령', () => {
    let scopes: ScopeManager;
    let scope: ParticipationScope;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    const desk = () =>
        new SafetyCommands(
            {
                api: createApi({ base: '/api/v1', transport, scopes }),
                scopes,
            } as unknown as IdentitySession,
            scope,
        );
    const body = (n: number) =>
        JSON.parse(String(transport.mock.calls[n][1]!.body)) as Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
    const key = (n: number) =>
        new Headers(transport.mock.calls[n][1]!.headers).get('Idempotency-Key');

    beforeEach(() => {
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
        scope = scopes.setParticipation({ eventId, memberId, participationRevision: '1' }).scope!;
    });
    afterEach(() => scopes.dispose());

    it('좌표는 세 점 이상/0~1 범위만 받는다', () => {
        expect(parsePoints('0.1,0.1; 0.5,0.1; 0.5,0.5')).toHaveLength(3);
        expect(parsePoints('0.1,0.1; 0.5,0.1')).toBeNull();
        expect(parsePoints('0.1,0.1; 1.5,0.1; 0.5,0.5')).toBeNull();
        expect(parsePoints('a,b; 0,0; 1,1')).toBeNull();
    });

    it('지도 저장은 기대 revision을 보내고, 화면이 고치지 않은 최소 인원/위험 기준/면적 근거를 그대로 돌려보낸다', async () => {
        const source = structuredClone(safetyMap);
        (source.profiles[0] as Record<string, unknown>).minimumCrewRule = crewRule;
        (source.profiles[0] as Record<string, unknown>).riskRule = {
            version: 'v1',
            unit: '명/㎡',
            condition: '2명 이하',
            evidence: crewRule.evidence,
        };
        const map = parseSafetyMap(source, eventId);
        transport.mockResolvedValueOnce(json(200, receipt));
        await desk().saveMap(
            map,
            {
                width: 1600,
                height: 900,
                zones: [
                    { zoneId, points: square, effectiveAreaM2: 120, riskCondition: '2명 이하' },
                    {
                        zoneId: backZoneId,
                        points: square,
                        effectiveAreaM2: 80,
                        riskCondition: '1.5명 이하',
                    },
                ],
            },
            '4',
        );
        const sent = body(0);
        expect(key(0)).toMatch(/^[0-9a-f-]{36}$/);
        expect(sent.expectedLayoutRevision).toBe('1');
        expect(sent.layout.coordinateSystem).toBe('normalized-image');
        const [main, back] = sent.profiles;
        expect(main.expectedProfileRevision).toBe('1');
        // 고치지 않은 값은 서버가 준 근거 그대로(쓰기 형식)
        expect(main.minimumCrewRule).toEqual({
            ...crewRule,
            evidence: {
                sourceKind: 'MANUAL_CONFIRMATION',
                sourceRef: 'fixture:crew',
                observedAt: null,
            },
        });
        expect(main.riskRule.evidence.sourceRef).toBe('fixture:crew');
        expect(main.areaEvidence.sourceRef).toBe('fixture:field-check');
        // 바꾼 면적·기준은 이번 확인 근거로 적는다
        expect(back.areaEvidence.sourceRef).toBe(`chief:${memberId}`);
        expect(back.riskRule).toMatchObject({ condition: '1.5명 이하', unit: '명/㎡' });
    });

    it('최소 인원 기준은 바꾸면 이번 확인 근거로 보내고, 같으면 기존 근거를, 없애면 null을 보낸다', async () => {
        const source = structuredClone(safetyMap);
        (source.profiles[0] as Record<string, unknown>).minimumCrewRule = crewRule;
        const map = parseSafetyMap(source, eventId);
        const zone = (minimumCrew: unknown) => ({
            zoneId,
            points: square,
            effectiveAreaM2: 120,
            riskCondition: null,
            minimumCrew: minimumCrew as never,
        });
        const save = (minimumCrew: unknown) =>
            desk().saveMap(map, { width: 1, height: 1, zones: [zone(minimumCrew)] }, '4');
        transport.mockResolvedValue(json(200, receipt));
        const { evidence: _e, ...plain } = crewRule;
        void _e;

        // 같은 값(시각 표기만 다름)은 기존 근거 그대로
        await save({ ...plain, startAt: '2026-11-18T09:00:00+09:00' });
        expect(body(0).profiles[0].minimumCrewRule.evidence.sourceRef).toBe('fixture:crew');
        // 인원을 바꾸면 이번 확인 근거
        await save({ ...plain, minimumCount: 3 });
        expect(body(1).profiles[0].minimumCrewRule).toMatchObject({
            minimumCount: 3,
            startAt: '2026-11-18T00:00:00.000Z',
            evidence: { sourceRef: `chief:${memberId}` },
        });
        await save(null);
        expect(body(2).profiles[0].minimumCrewRule).toBeNull();

        // 음수·소수 인원, 끝이 시작보다 앞이면 보내지 않는다
        await expect(save({ ...plain, minimumCount: -1 })).rejects.toThrow();
        await expect(save({ ...plain, minimumCount: 1.5 })).rejects.toThrow();
        await expect(save({ ...plain, endAt: plain.startAt })).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(3);
    });

    it('같은 구역을 두 번 그리거나 면적이 0 이하이면 보내지 않는다', async () => {
        const map = parseSafetyMap(structuredClone(safetyMap), eventId);
        const z = { zoneId, points: square, effectiveAreaM2: null, riskCondition: null };
        await expect(
            desk().saveMap(map, { width: 1, height: 1, zones: [z, z] }, '4'),
        ).rejects.toThrow();
        await expect(
            desk().saveMap(
                map,
                { width: 1, height: 1, zones: [{ ...z, effectiveAreaM2: 0 }] },
                '4',
            ),
        ).rejects.toThrow();
        expect(transport).not.toHaveBeenCalled();
    });

    it('카메라 등록은 저장된 배치도/구역 프로필의 revision을 묶고, 응답을 잃으면 같은 키로만 다시 보낸다', async () => {
        const map = parseSafetyMap(structuredClone(safetyMap), eventId);
        const d = desk();
        const input = {
            name: '객석 카메라',
            sourceBindingRef: 'collector-cam-3',
            enabled: true,
            coverage: [{ zoneId, scope: 'FULL' as const, roi: square, effectiveAreaM2: null }],
        };
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect((await d.createCamera(map, input, '4')).kind).toBe('retry');
        transport.mockResolvedValueOnce(json(201, receipt));
        expect((await d.createCamera(map, input, '4')).kind).toBe('done');
        expect(key(1)).toBe(key(0));
        expect(body(0).coverage[0]).toMatchObject({
            zoneId,
            layoutId: map.layout!.id,
            layoutRevision: '1',
            profileRevision: '1',
            calibrationEvidence: { sourceKind: 'MANUAL_CONFIRMATION' },
        });
        await expect(d.createCamera({ ...map, layout: null }, input, '4')).rejects.toThrow();
    });

    it('카메라 한 대가 두 구역을 비추면 구역마다 화면 속 영역을 나눠 보내고, 같은 구역 중복은 막는다', async () => {
        const map = parseSafetyMap(structuredClone(safetyMap), eventId);
        const left = [
            { x: 0, y: 0 },
            { x: 0.5, y: 0 },
            { x: 0.5, y: 1 },
            { x: 0, y: 1 },
        ];
        const right = left.map((p) => ({ x: p.x + 0.5, y: p.y }));
        const input = {
            name: '객석 카메라',
            sourceBindingRef: 'collector-cam-3',
            enabled: true,
            coverage: [
                { zoneId, scope: 'PARTIAL' as const, roi: left, effectiveAreaM2: 60 },
                { zoneId: backZoneId, scope: 'PARTIAL' as const, roi: right, effectiveAreaM2: 40 },
            ],
        };
        transport.mockResolvedValueOnce(json(201, receipt));
        expect((await desk().createCamera(map, input, '4')).kind).toBe('done');
        const sent = body(0).coverage as Record<string, unknown>[];
        expect(sent.map((c) => [c.zoneId, c.roi, c.effectiveAreaM2])).toEqual([
            [zoneId, left, 60],
            [backZoneId, right, 40],
        ]);
        expect(sent.every((c) => c.layoutRevision === '1')).toBe(true);

        const twice = { ...input, coverage: [input.coverage[0], { ...input.coverage[1], zoneId }] };
        await expect(desk().createCamera(map, twice, '4')).rejects.toThrow('같은 구역');
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('웹 연결 카메라 칸은 연결 식별자·관측 구역 없이 이름과 연결 담당 업무만으로 만든다(#397)', async () => {
        const map = parseSafetyMap(structuredClone(safetyMap), eventId);
        const taskId = '10000000-0000-4000-8000-0000000000aa';
        transport.mockResolvedValueOnce(json(201, receipt));
        const input = {
            name: ' 객석 카메라 ',
            sourceBindingRef: '  ',
            enabled: true,
            coverage: [],
            linkTaskId: taskId,
        };
        // 배치도가 없어도 관측 구역이 없으면 만들 수 있다
        expect((await desk().createCamera({ ...map, layout: null }, input, '4')).kind).toBe('done');
        expect(body(0)).toEqual({
            expectedEventRevision: '4',
            name: '객석 카메라',
            enabled: true,
            linkTaskId: taskId,
        });
        await expect(desk().createCamera(map, { ...input, name: ' ' }, '4')).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('담당 업무를 빼면 키를 보내지 않아 서버가 자동 지정하고, null이면 지정 안 함으로 보낸다(#395)', async () => {
        const map = parseSafetyMap(structuredClone(safetyMap), eventId);
        transport.mockResolvedValue(json(201, receipt));
        const input = { name: '객석 카메라', enabled: true };
        await desk().createCamera(map, input, '4');
        expect(body(0)).not.toHaveProperty('linkTaskId');
        await desk().createCamera(map, { ...input, name: '2층 카메라', linkTaskId: null }, '4');
        expect(body(1)).toMatchObject({ linkTaskId: null });
        expect(body(1)).toHaveProperty('linkTaskId', null);
    });

    it('연결 담당 업무를 바꾸거나 null로 풀고, 같은 값이면 보내지 않는다(#397)', async () => {
        const camera = parseCameraPage(structuredClone(cameras), eventId).items[0];
        const taskId = '10000000-0000-4000-8000-0000000000aa';
        transport.mockResolvedValue(json(200, receipt));
        await desk().updateCamera(camera, { linkTaskId: taskId });
        expect(body(0)).toEqual({ expectedCameraRevision: camera.revision, linkTaskId: taskId });
        await desk().updateCamera({ ...camera, linkTaskId: taskId }, { linkTaskId: null });
        expect(body(1)).toEqual({ expectedCameraRevision: camera.revision, linkTaskId: null });
        await expect(desk().updateCamera(camera, { linkTaskId: null })).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(2);
    });

    it('카메라 수정은 바꾼 항목만 보내고, 바꾼 것이 없으면 보내지 않는다', async () => {
        const camera = parseCameraPage(structuredClone(cameras), eventId).items[0];
        transport.mockResolvedValueOnce(json(200, receipt));
        await desk().updateCamera(camera, { enabled: false, name: camera.name });
        expect(body(0)).toEqual({ expectedCameraRevision: camera.revision, enabled: false });
        await expect(desk().updateCamera(camera, { name: camera.name })).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('관측 구역 바꾸기는 저장된 배치도/구역 프로필 revision에 묶은 coverage만 보낸다', async () => {
        const map = parseSafetyMap(structuredClone(safetyMap), eventId);
        const camera = parseCameraPage(structuredClone(cameras), eventId).items[0];
        const one = {
            zoneId: backZoneId,
            scope: 'PARTIAL' as const,
            roi: square,
            effectiveAreaM2: 40,
        };
        const coverage = [one];
        transport.mockResolvedValueOnce(json(200, receipt));
        await desk().updateCamera(camera, { coverage }, map);
        expect(transport.mock.calls[0][1]!.method).toBe('PATCH');
        const sent = body(0);
        expect(Object.keys(sent).sort()).toEqual(['coverage', 'expectedCameraRevision']);
        expect(sent.coverage).toEqual([
            {
                zoneId: backZoneId,
                layoutId: map.layout!.id,
                layoutRevision: '1',
                profileRevision: expect.any(String),
                scope: 'PARTIAL',
                roi: square,
                effectiveAreaM2: 40,
                calibrationEvidence: expect.objectContaining({ sourceKind: 'MANUAL_CONFIRMATION' }),
            },
        ]);
        // 지도 없이, 또는 프로필이 없는 구역으로는 보내지 않는다
        await expect(desk().updateCamera(camera, { coverage })).rejects.toThrow();
        await expect(
            desk().updateCamera(
                camera,
                { coverage: [{ ...one, zoneId: '10000000-0000-4000-8000-0000000000ff' }] },
                map,
            ),
        ).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(1);
    });
});
