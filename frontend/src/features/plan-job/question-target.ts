import { BASIC_FIELDS } from '../event-admin/labels';
import type { PlanQuestion } from './contract';

// 계획 생성 추가 질문(NEEDS_INFO)의 field는 AI 요청의 위치다(예: payload.event.prepStartAt, payload.zones).
// 어디서 보완해야 다시 생성할 때 질문이 풀리는지 가른다(#268, !288 리뷰).
// - payload.event.<기본정보>: 공연 기본정보를 고친다(계획 조건으로 넣으면 다음 생성에 반영되지 않는다).
// - payload.zones·programs·workGroups: 계획 초안에서 구역·공연 순서·직무 그룹을 고친다.
// - payload.event.confirmedInput.<키> 또는 위치가 아닌 이름: 계획 생성 조건(026)으로 답한다.

const BASIC_LABEL: Record<string, string> = Object.fromEntries(
    BASIC_FIELDS.map((f) => [f.key, f.label]),
);
const PLAN_LABEL: Record<string, string> = {
    zones: '구역',
    programs: '공연 순서',
    workGroups: '직무 그룹',
};
const CONDITION_PREFIX = 'payload.event.confirmedInput.';

export type QuestionTarget =
    | { kind: 'condition'; key: string }
    | { kind: 'basic'; label: string }
    | { kind: 'plan'; label: string };

export function questionTarget(q: PlanQuestion): QuestionTarget {
    const field = q.field;
    if (field.startsWith(CONDITION_PREFIX)) {
        const key = field.slice(CONDITION_PREFIX.length);
        return { kind: 'condition', key: key || q.key };
    }
    if (field === 'payload.event.confirmedInput') return { kind: 'condition', key: q.key };
    if (field === 'payload.event' || field.startsWith('payload.event.')) {
        const name = field.slice('payload.event.'.length).split(/[.[]/)[0];
        return { kind: 'basic', label: BASIC_LABEL[name] ?? '공연 기본정보' };
    }
    if (field.startsWith('payload.')) {
        const name = field.slice('payload.'.length).split(/[.[]/)[0];
        return { kind: 'plan', label: PLAN_LABEL[name] ?? '계획 입력' };
    }
    // 요청 위치가 아닌 이름은 지금처럼 조건 이름으로 답한다.
    return { kind: 'condition', key: field };
}

/** 질문 아래에 보일 보완 위치 안내. 조건으로 답하는 질문은 안내가 없다(아래 조건 칸에 답한다). */
export function questionHint(q: PlanQuestion, embedded: boolean): string | null {
    const target = questionTarget(q);
    if (target.kind === 'basic')
        return `${embedded ? '공연 관리의 ' : ''}기본정보에서 '${target.label}'을(를) 고친 뒤 다시 생성하세요.`;
    if (target.kind === 'plan')
        return `계획 초안 편집에서 ${target.label}을(를) 보완해 저장한 뒤 다시 생성하세요.`;
    return null;
}
