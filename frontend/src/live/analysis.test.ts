import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Action } from '../domain/reducer';
import type { DemoState } from '../domain/types';
import type { Issue as ApiIssue } from '../features/response/contract';
import { emptyData } from './convert';
import { ANALYSIS_WINDOW_MS, analysisStatus, lookup, toIssues } from './convert-ops';
import { OPS_HANDLERS } from './handlers-ops';
import type { LiveRuntime } from './runtime';

// #358 보고 분석: 총괄 화면에서 분석 중·실패·결과 없음·낡음을 구분하고 다시 분석을 요청한다.

type Fake = Record<string, ReturnType<typeof vi.fn>>;
const fakes: { desk: Fake } = { desk: {} };
vi.mock('../features/response/client', () => ({ responder: () => fakes.desk }));

const Z = '10000000-0000-4000-8000-000000000005';
const report = (id: string, createdAt: string) => ({
    id,
    issueId: 'i-1',
    authorMemberId: 'm-2',
    text: '마이크 소리가 안 나요',
    audioId: null,
    kind: id === 'r-1' ? 'INITIAL' : 'FOLLOWUP',
    participationRevision: '1',
    createdAt,
});
const issue = (patch: Partial<ApiIssue> = {}): ApiIssue =>
    ({
        id: 'i-1',
        eventId: 'e-1',
        title: '메인 마이크 무음',
        status: 'RECEIVED',
        zoneId: Z,
        workGroupId: null,
        taskId: null,
        relatedIssueId: null,
        reports: [report('r-1', '2026-11-24T09:00:00Z'), report('r-2', '2026-11-24T09:05:00Z')],
        analysis: null,
        situation: null,
        eventRevision: '9',
        ...patch,
    }) as ApiIssue;
const at = (iso: string, plusMs = 0) => Date.parse(iso) + plusMs;
const analysis = (validity: 'CURRENT' | 'STALE') => ({
    analysisValidity: validity,
    category: 'EQUIPMENT',
    reportedState: 'OCCURRED',
    zoneCandidates: [],
});

describe('보고 분석 상태(#358)', () => {
    it('가장 최근 보고가 마감 안이면 분석 중, 지나도 결과가 없으면 결과 없음이다', () => {
        const i = issue();
        expect(analysisStatus(i, at('2026-11-24T09:05:00Z', 5_000))).toEqual({ kind: 'running' });
        expect(analysisStatus(i, at('2026-11-24T09:05:00Z', ANALYSIS_WINDOW_MS + 1))).toEqual({
            kind: 'missing',
        });
        // 기기 시계가 늦어 보고가 미래로 보여도 폭을 넘으면 계속 분석 중으로 두지 않는다
        expect(analysisStatus(i, at('2026-11-24T09:05:00Z', -2_000))).toEqual({ kind: 'running' });
        expect(analysisStatus(i, at('2026-11-24T09:05:00Z', -ANALYSIS_WINDOW_MS - 1))).toEqual({
            kind: 'missing',
        });
    });

    it('아는 작업이 있으면 그 상태를 따른다(진행 중/실패 코드)', () => {
        const late = at('2026-11-24T09:30:00Z');
        expect(analysisStatus(issue(), late, { state: 'RUNNING' })).toEqual({ kind: 'running' });
        expect(analysisStatus(issue(), late, { state: 'FAILED', code: 'AI_TIMEOUT' })).toEqual({
            kind: 'failed',
            code: 'AI_TIMEOUT',
        });
        // 막 성공했지만 이슈를 먼저 읽어 결과가 아직 없으면 마감 안에서는 분석 중이다
        expect(
            analysisStatus(issue(), at('2026-11-24T09:05:00Z', 2_000), { state: 'SUCCEEDED' }),
        ).toEqual({ kind: 'running' });
        // 마감이 지나도 결과가 없으면(더 뒤 보고가 생기는 등) 결과 없음이다
        expect(analysisStatus(issue(), late, { state: 'SUCCEEDED' })).toEqual({ kind: 'missing' });
        // 결과가 있으면 상태를 비운다. 다시 분석 중이면 그것을 먼저 보인다.
        const done = issue({ analysis: analysis('CURRENT') } as Partial<ApiIssue>);
        expect(analysisStatus(done, late, { state: 'SUCCEEDED' })).toBeUndefined();
        expect(analysisStatus(done, late, { state: 'QUEUED' })).toEqual({ kind: 'running' });
    });

    it('화면 이슈에 최신/낡음과 가장 최근 보고를 담는다', () => {
        const look = lookup(null, []);
        const [stale] = toIssues(
            [issue({ analysis: analysis('STALE') } as Partial<ApiIssue>)],
            look,
            at('2026-11-24T10:00:00Z'),
        );
        expect(stale.analysis?.validity).toBe('STALE');
        expect(stale.analysisStatus).toBeUndefined();
        expect(stale.latestReportId).toBe('r-2');
        const [failed] = toIssues(
            [issue()],
            look,
            at('2026-11-24T10:00:00Z'),
            new Map([['i-1', { state: 'FAILED' as const, code: 'AI_UNAVAILABLE' }]]),
        );
        expect(failed.analysisStatus).toEqual({ kind: 'failed', code: 'AI_UNAVAILABLE' });
    });
});

describe('다시 분석 요청(#358)', () => {
    const runtime = (chief: boolean): LiveRuntime =>
        ({
            session: {},
            client: { scope: {} },
            eventRevision: '9',
            chief,
            ops: { issues: [issue()] },
            analysisJobs: new Map(),
        }) as unknown as LiveRuntime;
    const run = (r: LiveRuntime) =>
        OPS_HANDLERS.reanalyze!(
            r,
            { type: 'reanalyze', issueId: 'i-1' } as Action,
            {
                data: emptyData(),
            } as DemoState,
        );

    beforeEach(() => {
        fakes.desk = {
            forget: vi.fn(),
            analyze: vi.fn(async () => ({ kind: 'job', jobId: 'job-9' })),
        };
    });

    it('가장 최근 보고를 지금 revision으로 새 의도로 보내고 작업을 기억한다', async () => {
        const r = runtime(true);
        expect(await run(r)).toContain('다시 요청');
        expect(fakes.desk.forget).toHaveBeenCalledWith('analyze', 'r-2');
        expect(fakes.desk.analyze).toHaveBeenCalledWith('r-2', '9');
        expect(r.analysisJobs.get('i-1')).toBe('job-9');
    });

    it('팀원은 요청하지 않고, 접수하지 못하면 작업을 기억하지 않는다', async () => {
        const worker = runtime(false);
        expect(await run(worker)).toContain('총괄만');
        expect(fakes.desk.analyze).not.toHaveBeenCalled();
        fakes.desk.analyze = vi.fn(async () => ({ kind: 'conflict', target: 'event' }));
        const r = runtime(true);
        expect(await run(r)).toContain('다른 변경');
        expect(r.analysisJobs.size).toBe(0);
    });
});
