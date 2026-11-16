import { describe, expect, it } from 'vitest';
import { ContractViolationError } from '../../shared/api/contract';
import { agentRecords } from '../../../tests/fixtures/change-agent-records';
import fixtures from './v9-changeset-fixtures.json';
import {
    parseAck,
    parseAnalysisJob,
    parseChangeJob,
    parseChangeSet,
    parseChangeSetPage,
    parseIssue,
    parseIssueCreated,
    parseIssuePage,
    parseReportCreated,
} from './contract';
import { ack, analysisJob, changeJob, issue, readyWithDiff, report } from './test-data';

const eventId = '88acd0b1-dae5-5a8d-b2fe-6f38cbdf8588';

describe('v9 변경안 응답(!128 교차 계약 예시)', () => {
    it('연산 11종의 생성/미리보기/적용/폐기 응답을 모두 받는다', () => {
        expect(fixtures.cases).toHaveLength(11);
        expect(parseChangeSet(readyWithDiff, eventId).diff[0].summary).toContain('예비 마이크');
        for (const c of fixtures.cases) {
            for (const r of [...c.responses, c.discardResponse])
                expect(parseChangeSet(r, eventId, r.id).id).toBe(r.id);
            expect(c.responses.map((r) => r.status)).toEqual(['DRAFT', 'READY', 'APPLIED']);
            expect(c.discardResponse.status).toBe('DISCARDED');
        }
        expect(
            parseChangeSetPage({ items: fixtures.cases[0].responses, nextCursor: null }, eventId)
                .items,
        ).toHaveLength(3);
    });

    it('다른 공연/숫자 revision/해시 없는 READY는 받지 않는다(모르는 필드는 #389부터 받는다)', () => {
        const ready = fixtures.cases[0].responses[1];
        const bad = [
            { ...ready, eventId: '9faafc2f-df0e-5f44-9f7b-713c7fc84a61' },
            { ...ready, revision: 2 },
            { ...ready, previewHash: null },
            { ...ready, operations: [] },
            { ...readyWithDiff, diff: [{ ...readyWithDiff.diff[0], operationIndex: 5 }] },
            { ...readyWithDiff, diff: [{ ...readyWithDiff.diff[0], summary: '' }] },
        ];
        for (const value of bad)
            expect(() => parseChangeSet(value, eventId)).toThrow(ContractViolationError);
        expect(() =>
            parseChangeSet(ready, eventId, '5e7a9c50-3a41-5c9e-9d0c-6b2f1a0e4c11'),
        ).toThrow(ContractViolationError);
    });

    it('이전 단수 simulationRef 키는 거절한다(배열만 받는다)', () => {
        const draft = fixtures.cases[8].responses[0];
        expect(() =>
            parseChangeSet({ ...draft, simulationRef: draft.simulationRefs[0] }, eventId),
        ).toThrow(ContractViolationError);
    });
});

describe('이슈/보고 응답', () => {
    it('현재 공연의 이슈와 보고를 읽는다', () => {
        expect(parseIssue(issue(), eventId).reports[0].participationRevision).toBe('1');
        expect(parseIssuePage({ items: [issue()], nextCursor: 'c1' }, eventId).nextCursor).toBe(
            'c1',
        );
        const created = parseIssueCreated(
            { issue: issue(), report: report(), analysisJobId: null },
            eventId,
        );
        expect(created.report.kind).toBe('INITIAL');
        expect(
            parseReportCreated(
                { report: report({ kind: 'FOLLOWUP' }), analysisJobId: null, eventRevision: '3' },
                issue().id,
            ).report.kind,
        ).toBe('FOLLOWUP');
    });

    it('계약에 없는 필드가 더해져도 이슈 응답은 받는다(#386)', () => {
        // 이전 서버의 urgency, 앞으로 더해질 필드가 이슈·보고·페이지·생성 응답 어디에 있어도 읽는다.
        const extra = issue({
            urgency: 'NORMAL',
            futureField: { any: 1 },
            reports: [{ ...report(), futureField: 'x' }],
        });
        expect(parseIssue(extra, eventId).status).toBe(issue().status);
        expect(
            parseIssuePage({ items: [extra], nextCursor: null, total: 1 }, eventId).items,
        ).toHaveLength(1);
        expect(
            parseIssueCreated(
                {
                    issue: extra,
                    report: { ...report(), futureField: 'x' },
                    analysisJobId: null,
                    extra: 1,
                },
                eventId,
            ).report.kind,
        ).toBe('INITIAL');
        // 필수·형식 검사는 그대로다.
        expect(() => parseIssue({ ...extra, status: 'URGENT' }, eventId)).toThrow(
            ContractViolationError,
        );
    });

    it('팀원 응답은 분석 없이 본인 보고만 받는다(형태 검사)', () => {
        expect(parseIssue(issue({ analysis: null }), eventId).analysis).toBeNull();
    });

    it('다른 공연/다른 이슈의 보고/작성 회차 누락/종료 페이지 문자열 null은 받지 않는다', () => {
        const other = '9faafc2f-df0e-5f44-9f7b-713c7fc84a61';
        const bad: unknown[] = [
            issue({ eventId: other }),
            issue({ reports: [report({ issueId: other })] }),
            issue({ reports: [{ ...report(), participationRevision: undefined }] }),
            issue({ status: 'OPEN' }),
        ];
        for (const value of bad)
            expect(() => parseIssue(value, eventId)).toThrow(ContractViolationError);
        expect(() => parseIssuePage({ items: [], nextCursor: 'null' }, eventId)).toThrow(
            ContractViolationError,
        );
        expect(() =>
            parseIssueCreated(
                { issue: issue(), report: report({ kind: 'FOLLOWUP' }), analysisJobId: null },
                eventId,
            ),
        ).toThrow(ContractViolationError);
    });
});

describe('Job/확인 응답', () => {
    it('AI 대응안 결과6의 추가 필드(coordination·alternatives)를 받고 형식 검사는 지킨다(#389)', () => {
        const base = changeJob().result;
        // BE 제안 모양: 보고·업무·인원 참조 근거, 계산 참조는 비움, 상황은 이슈의 운영 상황 행
        const result6 = {
            ...base,
            evidenceRefs: [
                {
                    kind: 'REPORT',
                    id: fixtures.cases[0].responses[0].id,
                    revision: null,
                    contentHash: 'a'.repeat(64),
                    observedAt: '2026-11-18T09:00:00Z',
                    preconditions: ['보고 원문이 바뀌지 않음'],
                },
            ],
            situation: {
                id: 'f1e2d3c4-b5a6-4978-8a9b-0c1d2e3f4a5b',
                eventId,
                issueId: null,
                primaryType: 'EQUIPMENT_ANOMALY',
                relatedTypes: [],
                sourceKind: 'FORMAL_REPORT',
                sourceRefs: [],
                confirmedByMemberId: null,
                confirmedAt: null,
            },
            coordination: { tasks: [{ taskId: 't1', role: 'Supervisor', status: 'DONE' }] },
            alternatives: [{ summary: '다른 예비 인원 배치' }],
        };
        const job = parseChangeJob(changeJob({ result: result6 }), changeJob().jobId);
        expect(job.state === 'SUCCEEDED' && job.result.evidenceRefs).toHaveLength(1);
        // 보고 분석의 추천 모양(primary/related)은 계약의 상황이 아니다
        expect(() =>
            parseChangeJob(
                changeJob({
                    result: { ...base, situation: { primary: 'EQUIPMENT_ANOMALY', related: [] } },
                }),
                changeJob().jobId,
            ),
        ).toThrow(ContractViolationError);
        // 필수 누락·형식은 그대로 거절한다
        const { rationale: _r, ...noRationale } = base;
        void _r;
        expect(() => parseChangeJob(changeJob({ result: noRationale }), changeJob().jobId)).toThrow(
            ContractViolationError,
        );
        // 다른 종류 Job은 여전히 엄격하다
        expect(() => parseAnalysisJob({ ...analysisJob(), extra: 1 }, analysisJob().jobId)).toThrow(
            ContractViolationError,
        );
    });

    it('변경안 조회도 추가 필드를 받는다(#389)', () => {
        for (const c of fixtures.cases) {
            const set = { ...c.responses[1], coordination: { note: 'x' }, alternatives: [] };
            expect(parseChangeSet(set, eventId, set.id).id).toBe(set.id);
            expect(
                parseChangeSetPage({ items: [set], nextCursor: null }, eventId).items,
            ).toHaveLength(1);
        }
        const { rationale: _r, ...broken } = fixtures.cases[0].responses[1];
        void _r;
        expect(() => parseChangeSet(broken, eventId)).toThrow(ContractViolationError);
    });

    it('AI 처리 기록·검토한 다른 방법은 계약 모양이면 남기고, 아니면 그 칸만 버린다(#389)', () => {
        const { alternatives, coordination } = agentRecords;
        const records = { alternatives, coordination };
        const set = { ...fixtures.cases[0].responses[1], ...records };
        const kept = parseChangeSet(set, eventId, set.id);
        expect(kept.alternatives).toEqual(alternatives);
        expect(kept.coordination?.graph.map((g) => g.role)).toEqual([
            'SUPERVISOR',
            'STAFFING',
            'GUIDE',
            'REVIEW',
        ]);
        expect(kept.coordination?.contributions).toHaveLength(4);
        const page = parseChangeSetPage({ items: [set], nextCursor: null }, eventId);
        expect(page.items[0].coordination?.graph).toHaveLength(4);
        const job = parseChangeJob(
            changeJob({ result: { ...changeJob().result, ...records } }),
            changeJob().jobId,
        );
        expect(job.state === 'SUCCEEDED' && job.result.coordination?.graph).toHaveLength(4);
        expect(job.state === 'SUCCEEDED' && job.result.alternatives).toEqual(alternatives);

        // 모양이 틀리면 응답은 받고 그 칸만 없앤다: 모르는 역할, 대안 4개, 빈 요약, 빠진 칸
        const badRole = structuredClone(coordination);
        badRole.graph[0].role = 'PLANNER';
        const broken = [
            { coordination: badRole },
            { alternatives: [1, 2, 3, 4].map((n) => ({ summary: `대안 ${n}` })) },
            { alternatives: [{ summary: '' }] },
            { coordination: { graph: [], redelegations: [], resolvedConflicts: [] } },
            { alternatives: 'x', coordination: null },
        ];
        for (const extra of broken) {
            const read = parseChangeSet({ ...set, ...extra }, eventId, set.id);
            for (const key of Object.keys(extra)) expect(Object.hasOwn(read, key)).toBe(false);
            // 다른 칸은 그대로 남는다
            const other = Object.hasOwn(extra, 'coordination') ? 'alternatives' : 'coordination';
            if (!Object.hasOwn(extra, other)) expect(Object.hasOwn(read, other)).toBe(true);
        }
        const badJob = parseChangeJob(
            changeJob({ result: { ...changeJob().result, coordination: badRole } }),
            changeJob().jobId,
        );
        expect(badJob.state === 'SUCCEEDED' && Object.hasOwn(badJob.result, 'coordination')).toBe(
            false,
        );
        // 참고 기록과 관계없는 필수 누락은 여전히 거절한다
        const { rationale: _r, ...noRationale } = set;
        void _r;
        expect(() => parseChangeSet(noRationale, eventId)).toThrow(ContractViolationError);
    });

    it('분석/변경 생성 Job을 종류별로 읽는다', () => {
        expect(parseAnalysisJob(analysisJob(), analysisJob().jobId).state).toBe('SUCCEEDED');
        const job = parseChangeJob(changeJob(), changeJob().jobId);
        expect(job.state === 'SUCCEEDED' && job.result.changeSetId).toBe(
            fixtures.cases[0].responses[0].id,
        );
        expect(() => parseChangeJob(analysisJob(), analysisJob().jobId)).toThrow(
            ContractViolationError,
        );
        expect(() =>
            parseAnalysisJob({ ...analysisJob(), state: 'RUNNING' }, analysisJob().jobId),
        ).toThrow(ContractViolationError);
    });

    it('명시 확인 대상과 해시를 읽는다', () => {
        expect(parseAck(ack(), ack().id).targets[0].changeKinds).toEqual(['TIME', 'ZONE']);
        expect(() => parseAck({ ...ack(), targetsHash: '' }, ack().id)).toThrow(
            ContractViolationError,
        );
    });
});
