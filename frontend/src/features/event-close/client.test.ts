import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApi } from '../../shared/api/api';
import { ContractViolationError } from '../../shared/api/contract';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { EventCloser, parseSummary } from './client';

const eventId = '88acd0b1-dae5-5a8d-b2fe-6f38cbdf8588';
const memberId = '322a1e11-0d1a-5939-8080-7784aa5929a0';
const event = (state: string, revision: string) => ({
    id: eventId,
    name: '합성 공연',
    state,
    revision,
    date: '2026-11-18',
    prepStartAt: '2026-11-18T09:00:00+09:00',
    openAt: '2026-11-18T10:00:00+09:00',
    closeAt: '2026-11-18T20:00:00+09:00',
    teardownEndAt: '2026-11-18T21:00:00+09:00',
    locationName: '합성 시연장',
    address: '합성 주소',
    totalCapacity: '10',
    expectedVisitors: '50',
    peakVisitors: '20',
    desiredReserveCount: '1',
    myParticipation: {
        memberId,
        participationRevision: '1',
        active: true,
        eventRole: 'CHIEF',
        workGroupId: null,
    },
    description: '설명',
    instructions: '안내',
});
const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const failure = (status: number, code: string) =>
    json(status, { code, message: '안내', requestId: 'req-1' });

describe('종료 요약 응답', () => {
    it('본문/형식/문맥 revision을 읽는다', () => {
        expect(parseSummary({ text: '요약', format: 'MARKDOWN', contextRevision: '5' })).toEqual({
            text: '요약',
            format: 'MARKDOWN',
            contextRevision: '5',
        });
        expect(parseSummary({ text: '요약' })).toEqual({
            text: '요약',
            format: 'TEXT',
            contextRevision: null,
        });
    });

    it('빈 본문/모르는 형식/추가 필드는 받지 않는다', () => {
        expect(() => parseSummary({ text: ' ' })).toThrow(ContractViolationError);
        expect(() => parseSummary({ text: '요약', format: 'PDF' })).toThrow(ContractViolationError);
        expect(() => parseSummary({ text: '요약', saved: true })).toThrow(ContractViolationError);
        expect(() => parseSummary({ text: '요약', contextRevision: 5 })).toThrow(
            ContractViolationError,
        );
    });
});

describe('공연 종료와 종료 요약', () => {
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let scope: ParticipationScope;
    let counter = 0;
    const closer = () =>
        new EventCloser(
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
            key: new Headers(init!.headers).get('Idempotency-Key'),
            body: JSON.parse(String(init!.body)) as unknown,
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
        const result = scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        scope = result.scope!;
    });
    afterEach(() => scopes.dispose());

    it('종료는 기대 revision만 보내고 멱등 키를 쓰지 않는다', async () => {
        transport.mockResolvedValueOnce(json(200, event('ENDED', '5')));
        const result = await closer().end('4');
        expect(result).toMatchObject({ kind: 'ended', event: { state: 'ENDED', revision: '5' } });
        expect(call(0)).toEqual({
            url: `/api/v1/events/${eventId}/end`,
            key: null,
            body: { expectedEventRevision: '4' },
        });
    });

    it('종료 응답을 받지 못하면 다시 보내지 않고 상태 조회로 넘긴다', async () => {
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect(await closer().end('4')).toEqual({ kind: 'check-state' });
        transport.mockResolvedValueOnce(failure(409, 'VERSION_CONFLICT'));
        expect(await closer().end('4')).toEqual({ kind: 'version-conflict' });
        expect(transport).toHaveBeenCalledTimes(2);
    });

    it('최초 성공만 본문을 받고, 끝난 의도는 다음 생성에서 새 키를 쓴다', async () => {
        const c = closer();
        transport
            .mockResolvedValueOnce(json(200, { text: '요약 A' }))
            .mockResolvedValueOnce(json(200, { text: '요약 B' }));
        expect(await c.summarize('5')).toMatchObject({ kind: 'text', summary: { text: '요약 A' } });
        expect(c.hasIntent).toBe(false);
        await c.summarize('5');
        expect(call(0).key).toBe('key-1');
        expect(call(1).key).toBe('key-2');
        expect(call(0).body).toEqual({ expectedEventRevision: '5' });
    });

    it('410 원문 미보관 뒤에는 같은 키를 다시 보내지 않는다', async () => {
        const c = closer();
        transport.mockResolvedValueOnce(failure(410, 'TEXT_NOT_RETAINED'));
        expect(await c.summarize('5')).toEqual({ kind: 'not-retained' });
        // 같은 의도를 다시 눌러도 요청이 나가지 않는다
        expect(await c.summarize('5')).toEqual({ kind: 'outcome-unknown' });
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('409 결과 불명은 미보관과 구분하고 자동으로 다시 보내지 않는다', async () => {
        const c = closer();
        transport.mockResolvedValueOnce(failure(409, 'IDEMPOTENCY_OUTCOME_UNKNOWN'));
        expect(await c.summarize('5')).toEqual({ kind: 'outcome-unknown' });
        await c.summarize('5');
        expect(transport).toHaveBeenCalledTimes(1);
        expect(c.hasIntent).toBe(true);
    });

    it('응답을 받지 못하면 사용자가 누를 때 같은 키/같은 본문으로 처리 사실을 확인한다', async () => {
        const c = closer();
        transport
            .mockRejectedValueOnce(new TypeError('network'))
            .mockResolvedValueOnce(failure(410, 'TEXT_NOT_RETAINED'));
        expect(await c.summarize('5')).toEqual({ kind: 'no-response' });
        // 그 사이 화면의 revision이 바뀌어도 원래 의도의 본문을 유지한다
        expect(await c.summarize('6')).toEqual({ kind: 'not-retained' });
        expect(call(1).key).toBe(call(0).key);
        expect(call(1).body).toEqual({ expectedEventRevision: '5' });
    });

    it('새 생성은 사용자가 명시할 때만 새 키로 시작한다', async () => {
        const c = closer();
        transport
            .mockResolvedValueOnce(failure(410, 'TEXT_NOT_RETAINED'))
            .mockResolvedValueOnce(json(200, { text: '새 요약' }));
        await c.summarize('5');
        expect(await c.startOver('5')).toMatchObject({ kind: 'text' });
        expect(call(1).key).not.toBe(call(0).key);
        expect(c.hasIntent).toBe(false);
    });

    it('권한/상태 거절은 확정 실패로 보고 의도를 끝낸다', async () => {
        const c = closer();
        transport.mockResolvedValueOnce(failure(409, 'INVALID_STATE'));
        expect(await c.summarize('5')).toMatchObject({
            kind: 'rejected',
            failure: { code: 'INVALID_STATE', intent: 'finished' },
        });
        expect(c.hasIntent).toBe(false);
    });
});
