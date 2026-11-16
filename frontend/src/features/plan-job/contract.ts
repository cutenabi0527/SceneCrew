import {
    ContractViolationError,
    asArray,
    asBigIntString,
    asRecord,
    asString,
    asUuid,
} from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';
import type { DraftLayout } from '../unconfirmed-draft/layout';

export interface PlanQuestion {
    key: string;
    field: string;
    question: string;
}
export type PlanResult =
    | { status: 'NEEDS_INFO'; questions: PlanQuestion[] }
    | {
          status: 'READY';
          summary: {
              workGroups: number;
              zones: number;
              programs: number;
              tasks: number;
              instructions: number;
          };
          /** 결과6의 평면도 초안 구역 수(#372). 결과5이거나 평면도가 없으면 0이다. */
          layoutZones: number;
      };
export type PlanJob = { jobId: string; kind: 'GENERATE_PLAN'; attempt: string } & (
    | { state: 'QUEUED' | 'RUNNING'; result: null; error: null }
    | { state: 'SUCCEEDED'; result: PlanResult; error: null }
    | { state: 'FAILED'; result: null; error: { code: string; retryable: boolean } }
);

function fail(path: string): never {
    throw new ContractViolationError(path, '공개 계획 Job 계약과 다릅니다.');
}
function keys(body: Record<string, unknown>, allowed: string[], path: string) {
    if (Object.keys(body).some((key) => !allowed.includes(key))) fail(path);
}
function nonempty(value: unknown, path: string) {
    const text = asString(value, path);
    if (!text.length) fail(path);
    return text;
}

/**
 * 결과6의 평면도 초안(V9-D45, #372). BE가 AI의 구역 이름·사각형을 같은 후보 zones의 tempId/id와
 * 꼭짓점(zonePolygons)으로 바꿔 `result.venueDraft`에 싣는다(11/11 오세진 결정). 결과5는 키가 없다.
 * 도형은 같은 후보의 구역만 가리킨다. 반환값은 평면도(없으면 null)다.
 */
export function parseVenueDraft(value: unknown, zoneRefs: string[]): DraftLayout | null {
    if (value === undefined || value === null) return null;
    const venue = parseV9<{ layout: DraftLayout | null; profiles: unknown[] }>(
        'V9VenueDraft',
        value,
    );
    if (!venue.layout) return null;
    const refs = new Set(zoneRefs.map((r) => r.toLowerCase()));
    const seen = new Set<string>();
    venue.layout.zonePolygons.forEach((p, i) => {
        const key = p.zoneId.toLowerCase();
        if (!refs.has(key) || seen.has(key))
            fail(`result.venueDraft.layout.zonePolygons[${i}].zoneId`);
        seen.add(key);
    });
    return venue.layout;
}

const zoneRefsOf = (plan: Record<string, unknown>) =>
    asArray(plan.zones, 'result.plan.zones').map((z) => {
        const zone = asRecord(z, 'result.plan.zones');
        return String(zone.id ?? zone.tempId ?? '');
    });

// 현재 공개 후보(결과5·결과6)의 표시용 투영이다.
export function parsePlanResult(value: unknown): PlanResult {
    const body = asRecord(value, 'result');
    keys(body, ['status', 'questions', 'plan', 'venueDraft'], 'result');
    const questions = asArray(body.questions, 'result.questions').map((item, index) => {
        const path = `result.questions[${index}]`;
        const q = asRecord(item, path);
        keys(q, ['key', 'field', 'question'], path);
        return {
            key: nonempty(q.key, `${path}.key`),
            field: nonempty(q.field, `${path}.field`),
            question: nonempty(q.question, `${path}.question`),
        };
    });
    if (body.status === 'NEEDS_INFO') {
        if (!questions.length || body.plan !== null) fail('result');
        if (body.venueDraft !== undefined && body.venueDraft !== null) fail('result.venueDraft');
        return { status: 'NEEDS_INFO', questions };
    }
    if (body.status !== 'READY' || questions.length) fail('result.status');
    const plan = asRecord(body.plan, 'result.plan');
    const names = ['workGroups', 'zones', 'programs', 'tasks', 'instructions'] as const;
    keys(plan, [...names], 'result.plan');
    const counts = names.map((name) => asArray(plan[name], `result.plan.${name}`).length);
    if (
        counts[0] < 1 ||
        counts[0] > 31 ||
        counts[1] > 10 ||
        counts[2] > 20 ||
        counts[3] < 1 ||
        counts[3] > 200
    )
        fail('result.plan');
    const layout = parseVenueDraft(body.venueDraft, zoneRefsOf(plan));
    // 후보 전체의 편집/적용 검증은 계획 편집에서 한다. 이 화면은 개수만 읽고 후보를 저장하지 않는다.
    return {
        layoutZones: layout?.zonePolygons.length ?? 0,
        status: 'READY',
        summary: {
            workGroups: counts[0],
            zones: counts[1],
            programs: counts[2],
            tasks: counts[3],
            instructions: counts[4],
        },
    };
}

export function parsePlanJob(value: unknown, expectedJobId: string): PlanJob {
    const body = asRecord(value, 'job');
    const jobId = asUuid(body.jobId, 'job.jobId');
    if (jobId.toLowerCase() !== asUuid(expectedJobId, 'jobId').toLowerCase()) fail('job.jobId');
    if (body.kind !== 'GENERATE_PLAN') fail('job.kind');
    const common = {
        jobId,
        kind: 'GENERATE_PLAN' as const,
        attempt: asBigIntString(body.attempt, 'job.attempt'),
    };
    if (body.state === 'QUEUED' || body.state === 'RUNNING') {
        if (body.result !== null || body.error !== null) fail('job');
        return { ...common, state: body.state, result: null, error: null };
    }
    if (body.state === 'SUCCEEDED') {
        if (body.error !== null) fail('job.error');
        return { ...common, state: 'SUCCEEDED', result: parsePlanResult(body.result), error: null };
    }
    if (body.state === 'FAILED') {
        if (body.result !== null) fail('job.result');
        const error = asRecord(body.error, 'job.error');
        const code = nonempty(error.code, 'job.error.code');
        if (typeof error.retryable !== 'boolean') fail('job.error.retryable');
        if (
            ['INVALID_REQUEST', 'AI_TIMEOUT', 'AI_REVIEW_FAILED', 'STALE_CONTEXT'].includes(code) &&
            error.retryable
        )
            fail('job.error.retryable');
        return {
            ...common,
            state: 'FAILED',
            result: null,
            error: { code, retryable: error.retryable },
        };
    }
    return fail('job.state');
}
