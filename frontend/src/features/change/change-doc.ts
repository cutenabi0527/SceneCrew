import { affectedOf } from '../../domain/selectors';
import type { Change, ChangeOp, EditableField, EventData, Slot } from '../../domain/types';

/** 이 칸을 고칠 수 있는가. 연산에 editable이 없으면(데모) 모든 칸을 고친다. */
export const canEdit = (o: ChangeOp, field: EditableField) =>
    o.kind !== 'cancel' && (!o.editable || o.editable.includes(field));

/** 고치기 전 "확인 필요 사항"의 기본 글. 한 줄이 한 항목이다 */
export const defaultChecks = (c: Change) =>
    [
        c.mode === 'AI'
            ? 'AI가 만든 후보입니다. 실제 이전 값과 승인 사실은 확정 뒤에만 생깁니다.'
            : '직접 만든 변경안입니다.',
        // 예약만 바뀌는 변경안은 확인할 사람이 없다(#371)
        affectedOf(c).length
            ? '확정하면 바뀐 사람에게 본인 확인 요청이 갑니다.'
            : '업무/담당이 바뀌지 않아 확정해도 본인 확인 요청은 없습니다.',
        '아직 적용 전입니다. 예약/배정은 실제 인수/설치/도착 기록이 아닙니다.',
        '알림 읽음이나 업무 시작은 확인이 아닙니다.',
    ].join('\n');

export const checksOf = (c: Change) => c.checks ?? defaultChecks(c);

/** 절차 한 단계의 들여쓰기 한 칸. 단계 글 앞의 공백으로 저장한다 */
export const STEP_INDENT = '    ';

/** 모든 줄이 글머리표 '- '로 시작하도록 맞춘다 */
export const normalizeSteps = (text: string) =>
    text
        .split('\n')
        .map((line) => {
            const [, lead, body] = /^(\s*)(?:- ?)?(.*)$/.exec(line)!;
            return `${lead}- ${body}`;
        })
        .join('\n');

const stepsToText = (steps: string[]) => normalizeSteps(steps.join('\n'));

const textToSteps = (text: string) =>
    text
        .split('\n')
        .map((line) => {
            const [, lead, body] = /^(\s*)- ?(.*)$/.exec(line) ?? [];
            return body?.trim() ? `${lead}${body.trim()}` : '';
        })
        .filter(Boolean);

export type ChangeDraft = {
    checks: string;
    ops: ChangeOp[];
    /** 항목별 절차 글. 타이핑 중 빈 줄이 지워지지 않도록 글 그대로 들고 있다가 저장할 때 나눈다 */
    steps: string[];
};

const stepsOfOp = (d: EventData, o: ChangeOp) => {
    if (o.kind === 'add') return o.steps;
    if (o.kind !== 'update') return [];
    return o.after.steps ?? d.tasks.find((t) => t.id === o.taskId)?.steps ?? [];
};

export const draftOf = (d: EventData, c: Change): ChangeDraft => ({
    checks: checksOf(c),
    ops: structuredClone(c.ops),
    steps: c.ops.map((o) => stepsToText(stepsOfOp(d, o))),
});

/** 고르다 만 빈 칸과 중복을 빼고 첫 사람을 담당으로, 나머지를 추가 담당으로 둔다 */
function withPeople(after: Slot): Slot {
    const picked = [after.assignee, ...(after.extraAssignees ?? [])].filter((x) => !!x);
    const list = [...new Set(picked)] as string[];
    if (!list.length) return { ...after, extraAssignees: undefined };
    return {
        ...after,
        assignee: list[0],
        extraAssignees: list.length > 1 ? list.slice(1) : undefined,
    };
}

/** 편집 글을 변경 후 값에 넣는다. 원래와 같은 절차는 넣지 않아 바뀐 항목으로 보이지 않게 한다 */
export function opsFromDraft(d: EventData, draft: ChangeDraft): ChangeOp[] {
    return draft.ops.map((o, k) => {
        const steps = textToSteps(draft.steps[k]);
        if (o.kind === 'add') return { ...o, steps, after: withPeople(o.after) };
        if (o.kind !== 'update') return o;
        const current = d.tasks.find((t) => t.id === o.taskId)?.steps ?? [];
        const same = steps.join('\n') === current.join('\n');
        return { ...o, after: { ...withPeople(o.after), steps: same ? undefined : steps } };
    });
}
