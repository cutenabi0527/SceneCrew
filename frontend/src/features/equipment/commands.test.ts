import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { assets } from '../../../tests/fixtures/equipment-data';
import { eventId, memberId, taskId } from '../../../tests/fixtures/workflow-data';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { EquipmentCommands, parseContext } from './commands';
import { parseAsset } from './live-contract';

const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const receipt = (id = '10000000-0000-4000-8000-000000000071') => ({
    id,
    revision: '1',
    eventRevision: '5',
});
const other = '10000000-0000-4000-8000-0000000000ff';

describe('장비 운영 명령', () => {
    let scopes: ScopeManager;
    let scope: ParticipationScope;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    const desk = () =>
        new EquipmentCommands(
            {
                api: createApi({ base: '/api/v1', transport, scopes }),
                scopes,
            } as unknown as IdentitySession,
            scope,
        );
    const call = (n: number) => {
        const [url, init] = transport.mock.calls[n];
        return {
            url: String(url),
            method: init?.method,
            key: new Headers(init?.headers).get('Idempotency-Key'),
            body: init?.body
                ? (JSON.parse(String(init.body)) as Record<string, unknown>)
                : undefined,
        };
    };
    const asset = parseAsset(structuredClone(assets[0]));

    beforeEach(() => {
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
        scope = scopes.setParticipation({ eventId, memberId, participationRevision: '1' }).scope!;
    });
    afterEach(() => scopes.dispose());

    it('장비 등록은 같은 키로만 다시 보내고, 근거는 총괄 직접 확인으로 적는다', async () => {
        const d = desk();
        transport.mockRejectedValueOnce(new TypeError('network'));
        const input = {
            name: ' 예비 마이크 ',
            model: '',
            ports: [{ key: 'OUT', kind: 'XLR' }],
            storageZoneId: null,
        };
        expect((await d.register(input, '4')).kind).toBe('retry');
        expect(d.pending('register')).toBe(true);
        transport.mockResolvedValueOnce(json(201, receipt()));
        expect((await d.register({ ...input, name: '바뀐 이름' }, '4')).kind).toBe('done');
        // 남은 의도의 원래 본문을 그대로 보낸다
        expect(call(1).key).toBe(call(0).key);
        expect(call(1).body).toEqual(call(0).body);
        expect(call(0).body).toMatchObject({
            name: '예비 마이크',
            model: null,
            expectedEventRevision: '4',
            evidence: { sourceKind: 'MANUAL_CONFIRMATION', sourceRef: `chief:${memberId}` },
        });
        expect(d.pending('register')).toBe(false);
    });

    it('포트 이름이 겹치면 보내지 않는다', async () => {
        await expect(
            desk().register(
                {
                    name: '믹서',
                    model: null,
                    ports: [
                        { key: 'IN', kind: 'XLR' },
                        { key: 'IN', kind: 'XLR' },
                    ],
                    storageZoneId: null,
                },
                '4',
            ),
        ).rejects.toThrow();
        expect(transport).not.toHaveBeenCalled();
    });

    it('상태 관측은 장비 revision을 함께 보내고 taskId는 null이다', async () => {
        transport.mockResolvedValueOnce(json(201, receipt()));
        await desk().observe(asset, 'UNUSABLE', '소리 끊김', '4');
        expect(call(0)).toMatchObject({
            method: 'POST',
            url: `/api/v1/equipment/${asset.id}/observations`,
            body: {
                expectedAssetRevision: asset.revision,
                taskId: null,
                technicalState: 'UNUSABLE',
                evidence: { note: '소리 끊김' },
            },
        });
        expect(call(0).key).toMatch(/^[0-9a-f-]{36}$/);
    });

    it('관계 해제는 기대 revision을 query로 보내고, 선후 관계는 공연 revision만 보낸다', async () => {
        const d = desk();
        transport.mockResolvedValueOnce(
            json(200, { id: assets[0].id, eventRevision: '5', released: true }),
        );
        await d.release('connections', assets[0].id, '4', '2');
        expect(call(0).method).toBe('DELETE');
        expect(call(0).url).toContain('?expectedEventRevision=4&expectedRevision=2');
        transport.mockResolvedValueOnce(
            json(200, { id: assets[0].id, eventRevision: '5', released: true }),
        );
        await d.release('dependencies', assets[0].id, '5', null);
        expect(call(1).url).toMatch(/\?expectedEventRevision=5$/);
        await expect(d.release('requirements', assets[0].id, '5', null)).rejects.toThrow();
    });

    it('다른 관계의 해제 응답은 성공으로 보지 않고 같은 키로 다시 확인하게 한다', async () => {
        const d = desk();
        transport.mockResolvedValueOnce(
            json(200, { id: other, eventRevision: '5', released: true }),
        );
        expect((await d.release('connections', assets[0].id, '4', '2')).kind).toBe('retry');
        expect(d.pending(`connections:release:${assets[0].id}`)).toBe(true);
    });

    it('필요 조건 바꾸기는 관계 revision을 본문에 넣고, 선후 관계 바꾸기는 넣지 않는다', async () => {
        const d = desk();
        transport.mockResolvedValue(json(200, receipt()));
        await d.saveRequirement(
            {
                taskIds: [taskId],
                programId: null,
                assetIds: [asset.id],
                quantity: 2,
                readyBy: '2026-11-18T00:10:00.000Z',
                summary: '리허설에서 확인',
            },
            '4',
            { id: other, revision: '3' },
        );
        expect(call(0)).toMatchObject({ method: 'PATCH', body: { expectedRevision: '3' } });
        await d.saveDependency(
            {
                from: { kind: 'TASK', id: taskId },
                to: { kind: 'TASK', id: other },
                lagSec: 30,
                summary: '순서 확인',
            },
            '4',
            { id: other },
        );
        expect(call(1).method).toBe('PATCH');
        expect(call(1).body).not.toHaveProperty('expectedRevision');
        await expect(
            d.saveDependency(
                {
                    from: { kind: 'TASK', id: taskId },
                    to: { kind: 'TASK', id: taskId },
                    lagSec: 0,
                    summary: 'x',
                },
                '4',
            ),
        ).rejects.toThrow();
    });

    it('다른 공연의 관계가 섞인 조회 응답은 쓰지 않는다', () => {
        const ctx = {
            eventId,
            eventRevision: '4',
            connections: [],
            requirements: [],
            dependencies: [],
        };
        expect(parseContext(ctx, eventId).eventRevision).toBe('4');
        expect(() => parseContext({ ...ctx, eventId: other }, eventId)).toThrow();
    });
});
