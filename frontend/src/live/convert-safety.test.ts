import { describe, expect, it } from 'vitest';
import { cameras, safetyMap, backZoneId, cameraId } from '../../tests/fixtures/safety-data';
import { zoneId } from '../../tests/fixtures/workflow-data';
import { cameraStates, zoneReading } from '../domain/selectors';
import type { EventData } from '../domain/types';
import { parseCameraPage, parseSafetyMap } from '../features/safety-map/contract';
import { rectPercent, toSafety } from './convert-safety';

const eventId = safetyMap.eventId;
const map = () => parseSafetyMap(structuredClone(safetyMap), eventId);
const cams = () => parseCameraPage(structuredClone(cameras), eventId).items;
const zones = [
    { id: zoneId, name: 'A구역 무대 앞' },
    { id: backZoneId, name: 'B구역 객석 뒤쪽' },
];

describe('안전 지도 변환(#372)', () => {
    it('배치도 다각형을 감싸는 사각형(%)으로 구역을 그리고 면적은 프로필 값을 쓴다', () => {
        const view = toSafety({ map: map(), cameras: cams() }, zones);
        expect(view.defaultArrangement).toBe(false);
        expect(view.safetyZones).toEqual([
            expect.objectContaining({
                id: zoneId,
                name: 'A구역 무대 앞',
                area: 120,
                limit: null,
                rect: [5, 10, 40, 40],
            }),
            expect.objectContaining({ id: backZoneId, rect: [55, 10, 40, 40] }),
        ]);
        expect(
            rectPercent([
                { x: 0.1, y: 0.2 },
                { x: 0.3, y: 0.25 },
                { x: 0.2, y: 0.6 },
            ]),
        ).toEqual([10, 20, 20, 40]);
    });

    it('배치도가 없거나 도형이 빠진 구역은 구역 이름으로 기본 배치를 그린다', () => {
        const noLayout = { ...map(), layout: null };
        const view = toSafety({ map: noLayout, cameras: [] }, zones);
        expect(view.defaultArrangement).toBe(true);
        // 오늘 구현(layout.ts defaultLayout): 위쪽 30%를 무대 자리로 비우고 한 줄로 놓는다
        for (const z of view.safetyZones) expect(z.rect[1]).toBe(30);
        expect(view.safetyZones[0].rect[0]).toBeLessThan(view.safetyZones[1].rect[0]);
        const partial = map();
        partial.layout!.zonePolygons = partial.layout!.zonePolygons.slice(0, 1);
        expect(toSafety({ map: partial, cameras: [] }, zones).defaultArrangement).toBe(true);
    });

    it('위험 판정은 서버 값을 쓰고, 낡은 관측은 판정과 관계없이 확인 필요로 둔다', () => {
        const view = toSafety({ map: map(), cameras: cams() }, zones);
        const a = view.observations.find((o) => o.zoneId === zoneId)!;
        const b = view.observations.find((o) => o.zoneId === backZoneId)!;
        expect(a).toMatchObject({ count: 180.4, fresh: 'FRESH', quality: 'GOOD', risk: 'OVER' });
        expect(a.at).toMatch(/^\d{2}:\d{2}$/);
        expect(b).toMatchObject({ fresh: 'STALE', quality: 'PARTIAL', risk: 'OK' });
        const data = {
            safetyZones: view.safetyZones,
            observations: view.observations,
            cameras: view.cameras,
        } as unknown as EventData;
        expect(zoneReading(data, zoneId).state).toBe('OVER');
        // 서버는 기준 이내라고 했지만 낡은 관측이라 0명/정상으로 보이지 않는다
        expect(zoneReading(data, backZoneId).state).toBe('CHECK');
    });

    it('처리 중·시각 불명·매핑 무효 관측은 지금 값으로 쓰지 않는다', () => {
        const m = map();
        m.observations[0].state = 'PROCESSING';
        m.observations[1].freshness = 'INVALID_MAPPING';
        let view = toSafety({ map: m, cameras: [] }, zones);
        expect(view.observations.map((o) => o.fresh)).toEqual(['PENDING', 'FAILED']);
        const unknown = map();
        unknown.observations[0].freshness = 'UNKNOWN';
        view = toSafety({ map: unknown, cameras: [] }, zones);
        expect(view.observations[0].fresh).toBe('STALE');
        const noCount = map();
        noCount.observations[0].estimates[0].estimatedCount = null;
        expect(toSafety({ map: noCount, cameras: [] }, zones).observations[0].quality).toBe('NONE');
    });

    it('카메라는 연결 상태·사용 여부·관측 구역·보정을 화면 값으로 바꾼다', () => {
        const view = toSafety({ map: map(), cameras: cams() }, zones);
        expect(view.cameras).toEqual([
            expect.objectContaining({
                id: cameraId,
                name: '무대 카메라',
                link: 'CONNECTED',
                active: true,
                zones: [zoneId],
                calibrated: true,
            }),
            expect.objectContaining({
                name: '후면 카메라',
                link: 'DISCONNECTED',
                frameAt: undefined,
            }),
        ]);
        const c = cams();
        c[0].enabled = false;
        c[0].connectionState = 'DISABLED';
        c[0].coverage = [{ zoneId, scope: 'UNDETERMINED' }];
        expect(toSafety({ map: map(), cameras: c }, zones).cameras[0]).toMatchObject({
            active: false,
            link: 'DISCONNECTED',
            calibrated: false,
        });
    });

    it('웹 연결 칸은 등록 안됨·입력 종류·연결한 사람을 보이고, 연결 뒤 15초가 지나도 사진이 없으면 첫 사진 없음이다(#397)', () => {
        const now = Date.parse('2026-11-04T03:20:00Z');
        const c = cams();
        c[0].connectionState = 'UNLINKED';
        c[0].inputKind = null;
        c[0].coverage = [];
        Object.assign(c[1], {
            connectionState: 'CONNECTING',
            inputKind: 'BROWSER',
            linkMemberId: 'm-1',
            linkedAt: '2026-11-04T03:19:50Z',
            lastReceivedAt: null,
        });
        const names = new Map([['m-1', '김현장']]);
        const [unlinked, connecting] = toSafety(
            { map: map(), cameras: c },
            zones,
            names,
            now,
        ).cameras;
        expect(unlinked).toMatchObject({ unlinked: true, input: null, calibrated: false });
        // 연결한 지 10초: 아직 기다린다
        expect(connecting).toMatchObject({
            unlinked: false,
            input: 'BROWSER',
            linkedBy: '김현장',
            noFirstFrame: false,
        });
        // 16초: 첫 사진 없음
        expect(
            toSafety({ map: map(), cameras: c }, zones, names, now + 6_000).cameras[1].noFirstFrame,
        ).toBe(true);
        expect(cameraStates(unlinked).map((s) => s.label)).toEqual(['등록 안됨', '보정 필요']);
        expect(
            cameraStates(toSafety({ map: map(), cameras: c }, zones, names, now + 6_000).cameras[1])
                .map((s) => s.label)
                .slice(0, 3),
        ).toEqual(['첫 사진 없음', '웹 연결', '연결 김현장']);
    });

    it('계획에 없는 구역의 관측·카메라 범위는 화면에 넣지 않는다', () => {
        const view = toSafety({ map: map(), cameras: cams() }, zones.slice(0, 1));
        expect(view.observations.map((o) => o.zoneId)).toEqual([zoneId]);
        expect(view.safetyZones).toHaveLength(1);
    });
});
