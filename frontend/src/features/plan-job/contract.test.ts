import { describe, expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { createApi } from '../../shared/api/api';
import { ContractViolationError } from '../../shared/api/contract';
import { ScopeDisposedError } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import { parsePlanJob, parsePlanResult } from './contract';
import { needsInfo, ready, jobId } from './examples';
import { planJobQuery } from './query';

describe('공개 계획 Job 투영', () => {
    it('공개 needsInfo 예시와 READY 개수를 읽는다', () => {
        expect(parsePlanJob(needsInfo, jobId)).toMatchObject({
            state: 'SUCCEEDED',
            result: { status: 'NEEDS_INFO', questions: needsInfo.result.questions },
        });
        expect(parsePlanJob(ready, jobId)).toMatchObject({
            state: 'SUCCEEDED',
            result: { status: 'READY', summary: { tasks: 1, workGroups: 1 } },
        });
    });
    it.each(['QUEUED', 'RUNNING'])('%s는 결과/오류 없이 진행을 표시한다', (state) => {
        expect(parsePlanJob({ ...needsInfo, state, result: null }, jobId).state).toBe(state);
    });
    it.each([
        'INVALID_REQUEST',
        'AI_TIMEOUT',
        'AI_REVIEW_FAILED',
        'AI_UNAVAILABLE',
        'STALE_CONTEXT',
        'FUTURE_ERROR',
    ])('실패 %s와 retryable을 읽되 새 작업을 실행하지 않는다', (code) => {
        expect(
            parsePlanJob(
                {
                    ...needsInfo,
                    state: 'FAILED',
                    result: null,
                    error: { code, retryable: code === 'AI_UNAVAILABLE' },
                },
                jobId,
            ),
        ).toMatchObject({ state: 'FAILED', error: { code } });
    });
    it.each([
        { ...needsInfo, attempt: 1 },
        { ...needsInfo, attempt: '01' },
        { ...needsInfo, attempt: '9223372036854775808' },
        { ...needsInfo, state: 'STALE_CONTEXT' },
        { ...needsInfo, state: 'PARTIAL' },
        { ...needsInfo, kind: 'TRANSCRIBE' },
        { ...needsInfo, jobId: '11111111-1111-4111-8111-111111111111' },
        { ...needsInfo, state: 'RUNNING' },
        { ...needsInfo, error: { code: 'oops', retryable: false } },
        { ...needsInfo, state: 'FAILED', result: null, error: null },
        { ...needsInfo, state: 'FAILED', result: null, error: { code: '', retryable: true } },
        {
            ...needsInfo,
            state: 'FAILED',
            result: null,
            error: { code: 'STALE_CONTEXT', retryable: true },
        },
    ])('모순되거나 다른 종류/대상인 결과를 거절한다', (body) => {
        expect(() => parsePlanJob(body, jobId)).toThrow(ContractViolationError);
    });
    it.each([
        { ...ready.result, equipmentDraft: {} },
        { ...ready.result, plan: { ...ready.result.plan, venueDraft: {} } },
        { ...needsInfo.result, resultVersion: 6 },
        { ...needsInfo.result, questions: [] },
        { ...needsInfo.result, plan: ready.result.plan },
        { ...ready.result, questions: needsInfo.result.questions },
        { ...ready.result, plan: { ...ready.result.plan, tasks: [] } },
        { ...needsInfo.result, questions: [{ key: 'k', field: 'f', question: '' }] },
    ])('내부 확장 결과6/무효 질문/후보를 현재 공개 결과로 처리하지 않는다', (result) => {
        expect(() => parsePlanResult(result)).toThrow(ContractViolationError);
    });
});

describe('결과6 평면도 초안(#372)', () => {
    const square = [
        { x: 0.1, y: 0.3 },
        { x: 0.4, y: 0.3 },
        { x: 0.4, y: 0.9 },
        { x: 0.1, y: 0.9 },
    ];
    const venue = (zonePolygons: { zoneId: string; points: { x: number; y: number }[] }[]) => ({
        layout: {
            coordinateSystem: 'normalized-image',
            width: 1000,
            height: 600,
            zonePolygons,
            source: {
                sourceKind: 'AUTOMATIC_MEASUREMENT',
                sourceRef: 'job:plan',
                observedAt: null,
            },
        },
        profiles: [],
    });
    it('같은 후보 구역을 가리키는 평면도를 읽고, 없거나 null이면 0개다', () => {
        const withLayout = {
            ...ready.result,
            venueDraft: venue([{ zoneId: 'tmp-zone', points: square }]),
        };
        expect(parsePlanResult(withLayout)).toMatchObject({ status: 'READY', layoutZones: 1 });
        expect(parsePlanResult({ ...ready.result, venueDraft: null })).toMatchObject({
            layoutZones: 0,
        });
        expect(parsePlanResult(ready.result)).toMatchObject({ layoutZones: 0 });
    });
    it.each([
        ['후보에 없는 구역', venue([{ zoneId: 'tmp-other', points: square }])],
        [
            '같은 구역 두 번',
            venue([
                { zoneId: 'tmp-zone', points: square },
                { zoneId: 'tmp-zone', points: square },
            ]),
        ],
        [
            '0~1 밖의 점',
            venue([{ zoneId: 'tmp-zone', points: [...square.slice(1), { x: 1.2, y: 0 }] }]),
        ],
        ['점 2개', venue([{ zoneId: 'tmp-zone', points: square.slice(0, 2) }])],
    ])('%s의 평면도는 계약 위반이다', (_, venueDraft) => {
        expect(() => parsePlanResult({ ...ready.result, venueDraft })).toThrow(
            ContractViolationError,
        );
    });
    it('질문(NEEDS_INFO)에는 평면도가 없다', () => {
        expect(() => parsePlanResult({ ...needsInfo.result, venueDraft: venue([]) })).toThrow(
            ContractViolationError,
        );
        expect(parsePlanResult({ ...needsInfo.result, venueDraft: null }).status).toBe(
            'NEEDS_INFO',
        );
    });
});

describe('계획 Job의 범위별 GET', () => {
    it('전체 API 접두어 중복 없이 조회하고 계정/행사/회차별 key를 사용한다', async () => {
        const scopes = new ScopeManager(new QueryClient());
        scopes.setAccount('account');
        const scope = scopes.setParticipation({
            eventId: 'event',
            memberId: 'member',
            participationRevision: '1',
        }).scope!;
        const transport = vi.fn<typeof fetch>().mockResolvedValue(
            new Response(JSON.stringify(needsInfo), {
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        try {
            const query = planJobQuery(
                createApi({ base: '/api/v1', scopes, transport }),
                scope,
                jobId,
            );
            expect(query.queryKey).toEqual([
                'account',
                'account',
                'event',
                'event',
                'member',
                'member',
                'round',
                '1',
                'plan-job',
                jobId,
            ]);
            await query.queryFn({ signal: new AbortController().signal });
            expect(transport.mock.calls[0][0]).toBe(`/api/v1/ai-jobs/${jobId}`);
            expect(transport.mock.calls[0][1]?.method).toBe('GET');
            expect(scopes.intentKeys.size).toBe(0);
            expect(() =>
                planJobQuery(createApi({ base: '/api/v1', scopes, transport }), scope, 'bad-id'),
            ).toThrow(ContractViolationError);
        } finally {
            scopes.dispose();
        }
    });
    it('행사 이탈 뒤 도착한 질문을 버린다', async () => {
        const scopes = new ScopeManager(new QueryClient());
        scopes.setAccount('account');
        const scope = scopes.setParticipation({
            eventId: 'event',
            memberId: 'member',
            participationRevision: '1',
        }).scope!;
        let finish!: (response: Response) => void;
        const transport = vi.fn<typeof fetch>().mockImplementation(
            () =>
                new Promise((resolve) => {
                    finish = resolve;
                }),
        );
        const pending = planJobQuery(
            createApi({ base: '/api/v1', scopes, transport }),
            scope,
            jobId,
        ).queryFn({ signal: new AbortController().signal });
        scopes.leaveParticipation('event');
        finish(
            new Response(JSON.stringify(needsInfo), {
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        await expect(pending).rejects.toBeInstanceOf(ScopeDisposedError);
        scopes.dispose();
    });
});
