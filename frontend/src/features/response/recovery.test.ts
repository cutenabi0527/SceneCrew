import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApi } from '../../shared/api/api';
import { ContractViolationError } from '../../shared/api/contract';
import { classifyFailure } from '../../shared/api/failure';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { Responder } from './client';
import {
    parsePointer,
    parseRecoveryJob,
    parseSimulation,
    type RecoverySimulation,
} from './contract';
import {
    feasible,
    pointer,
    recoveryJob,
    recoveryJobId,
    simulation,
    simulationId,
    spareAssetId,
} from './recovery-data';
import { authorId, changeSetFixture, eventId, issueId } from './test-data';

const brokenAssetId = '30000000-0000-4000-8000-0000000000ff';
const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });

describe('복구 계산 응답', () => {
    it('후보별 가능 여부/예약 미리보기와 확인되지 않은 가정을 읽는다', () => {
        const sim = parseSimulation(
            simulation(eventId, brokenAssetId, issueId),
            eventId,
            simulationId,
        );
        expect(sim.candidates.map((c) => c.status)).toEqual(['FEASIBLE', 'UNDETERMINED']);
        expect(sim.candidates[0].preview[0].after?.status).toBe('PLANNED');
        expect(sim.assumptions[0].confirmed).toBe(false);
        expect(parsePointer(pointer(true)).expired).toBe(true);
        const job = parseRecoveryJob(recoveryJob('SUCCEEDED'), recoveryJobId);
        expect(job.state === 'SUCCEEDED' && job.result.simulationId).toBe(simulationId);
    });

    it('다른 공연/다른 계산/겹치는 후보 ID/확인됐다는 가정/만료 시각 누락은 받지 않는다', () => {
        const base = simulation(eventId, brokenAssetId, issueId);
        const bad: unknown[] = [
            { ...base, eventId: brokenAssetId },
            { ...base, candidates: [feasible(eventId), feasible(eventId)] },
            { ...base, assumptions: [{ key: 'k', description: '가정', confirmed: true }] },
            { ...base, expiresAt: undefined },
            { ...base, state: 'DONE' },
            { ...base, candidates: [{ ...feasible(eventId), status: 'MAYBE' }] },
        ];
        for (const value of bad)
            expect(() => parseSimulation(value, eventId, simulationId)).toThrow(
                ContractViolationError,
            );
        expect(() => parseSimulation(base, eventId, brokenAssetId)).toThrow(ContractViolationError);
    });
});

describe('복구 계산 요청', () => {
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let scope: ParticipationScope;
    let counter = 0;
    const responder = () =>
        new Responder(
            {
                api: createApi({ base: '/api/v1', transport, scopes }),
                scopes,
            } as unknown as IdentitySession,
            scope,
        );
    const call = (n: number) => {
        const [url, init] = transport.mock.calls[n];
        return {
            url,
            key: new Headers(init?.headers).get('Idempotency-Key'),
            body: init?.body ? (JSON.parse(String(init.body)) as unknown) : undefined,
        };
    };

    beforeEach(() => {
        counter = 0;
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(
            new QueryClient(),
            new IntentKeyStore({ createKey: () => `key-${++counter}` }),
        );
        scopes.setAccount('A');
        scope = scopes.setParticipation({
            eventId,
            memberId: authorId,
            participationRevision: '1',
        }).scope!;
    });
    afterEach(() => scopes.dispose());

    it('계산 요청은 이슈/대상 장비/기대 revision만 보내고 응답이 없으면 같은 키로 다시 보낸다', async () => {
        const r = responder();
        transport.mockRejectedValueOnce(new TypeError('network'));
        const input = { issueId, targetAssetIds: [brokenAssetId], expectedEventRevision: '3' };
        expect((await r.recover(input)).kind).toBe('retry');
        expect(r.pending('recover', issueId)).toBe(true);
        transport.mockResolvedValueOnce(
            json(202, { jobId: recoveryJobId, statusUrl: `/api/v1/ai-jobs/${recoveryJobId}` }),
        );
        expect(await r.recover(input)).toEqual({ kind: 'job', jobId: recoveryJobId });
        expect(call(0)).toEqual({
            url: `/api/v1/events/${eventId}/recovery-simulations`,
            key: 'key-1',
            body: {
                expectedEventRevision: '3',
                issueId,
                targetAssetIds: [brokenAssetId],
                evidenceRefs: [],
                assumptions: [],
            },
        });
        expect(call(1).key).toBe('key-1');
    });

    it('만료된 결과(410)는 자동으로 다시 계산하지 않고 오류로 알린다', async () => {
        transport.mockResolvedValueOnce(
            json(410, { code: 'RECOVERY_RESULT_EXPIRED', message: '만료', requestId: 'r' }),
        );
        const error = await responder()
            .simulation(simulationId)
            .catch((e: unknown) => e);
        expect(classifyFailure(error).code).toBe('RECOVERY_RESULT_EXPIRED');
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('고른 후보의 연산을 그대로 보내고 계산 근거를 simulationRefs 배열로 묶는다', async () => {
        const sim = parseSimulation(
            simulation(eventId, brokenAssetId, issueId),
            eventId,
            simulationId,
        ) as RecoverySimulation;
        transport.mockResolvedValueOnce(json(201, changeSetFixture.responses[0]));
        const result = await responder().createFromCandidate({
            simulation: sim,
            candidate: sim.candidates[0],
            issueId,
            expectedEventRevision: '3',
        });
        expect(result.kind).toBe('done');
        const sent = call(0);
        expect(sent.url).toBe(`/api/v1/events/${eventId}/change-sets`);
        expect(sent.key).toBe('key-1');
        expect(sent.body).toMatchObject({
            mode: 'MANUAL',
            operations: sim.candidates[0].operations,
            issueId,
            expectedEventRevision: '3',
            simulationRefs: [{ simulationId, candidateId: 'spare-mic', inputHash: 'a'.repeat(64) }],
        });
        expect(sent.body).not.toHaveProperty('simulationRef');
        expect(JSON.stringify(sent.body)).toContain(spareAssetId);
    });
});
