import { ContractViolationError, asUtcTimestamp } from '../../shared/api/contract';
import { ZONE_DESCRIPTION_MAX, codePoints, refId, type Draft } from './contract';

// 초안 저장 전 사람이 고칠 수 있는 입력 검사(#387). 계약(DraftPlanWrite)의 빈 값·상한·시각 형식·순서를
// 항목 이름으로 알린다. 통과하면 validateDraft(계약 전체 검사)가 한 번 더 본다.

/** 저장할 때 빈 수행 단계 줄을 뺀다. 나머지는 그대로다. */
export function tidyDraft(draft: Draft): Draft {
    return {
        ...draft,
        tasks: draft.tasks.map((t) => ({
            ...t,
            steps: t.steps.map((s) => s.trim()).filter(Boolean),
        })),
    };
}

const validTime = (value: string) => {
    try {
        asUtcTimestamp(value, 'time');
        return true;
    } catch (error) {
        if (error instanceof ContractViolationError) return false;
        throw error;
    }
};

// field는 받침으로 끝나는 말(이름·제목·내용)만 쓴다.
function textProblem(where: string, field: string, value: string, max?: number) {
    if (!value.trim()) return `${where}의 ${field}을 입력해주세요.`;
    if (max !== undefined && codePoints(value) > max)
        return `${where}의 ${field}은 ${max}자 이하로 줄여주세요.`;
    return null;
}

function timeProblem(where: string, startAt: string, endAt: string) {
    if (!startAt || !validTime(startAt)) return `${where}의 시작 시각을 선택해주세요.`;
    if (!endAt || !validTime(endAt)) return `${where}의 종료 시각을 선택해주세요.`;
    if (Date.parse(startAt) >= Date.parse(endAt))
        return `${where}의 종료 시각을 시작 시각보다 뒤로 정해주세요.`;
    return null;
}

// 서버와 같은 이름 비교(공백 무시·대소문자 무시, BE UnicodeText.nameKey). 전체 casefold의 근사다.
const nameKey = (value: string) => value.replace(/\s+/gu, '').toLowerCase();

const groupLabel = (_: Draft, i: number) => `직무 그룹 ${i + 1}`;
const zoneLabel = (d: Draft, i: number) =>
    `구역 ${i + 1}(${d.zones[i]?.name.trim() || '이름 없음'})`;
const programLabel = (d: Draft, i: number) =>
    `공연 순서 ${i + 1}(${d.programs[i]?.name.trim() || '이름 없음'})`;
const taskLabel = (d: Draft, i: number) =>
    `업무 ${i + 1}(${d.tasks[i]?.title.trim() || '제목 없음'})`;

/** 같은 목록 안 이름 중복(서버 DUPLICATE). */
function duplicateName(items: { name: string }[], label: (i: number) => string): string | null {
    const seen = new Map<string, number>();
    for (const [i, item] of items.entries()) {
        const key = nameKey(item.name);
        const first = seen.get(key);
        if (first !== undefined)
            return `${label(i)}의 이름이 ${label(first)}의 이름과 같습니다. 이름을 다르게 정해주세요.`;
        seen.set(key, i);
    }
    return null;
}

/** 서버만 아는 관계 규칙(be/04): 공연 순서와 업무의 구역·시간, 업무 묶음의 그룹·시간 겹침. */
function relationProblem(draft: Draft): string | null {
    const programs = new Map(draft.programs.map((p) => [refId(p), p]));
    for (const [i, t] of draft.tasks.entries()) {
        if (t.programId === null) continue;
        const p = programs.get(t.programId);
        if (!p) continue;
        const pi = draft.programs.indexOf(p);
        if (p.zoneId !== null && p.zoneId !== t.zoneId)
            return `${taskLabel(draft, i)}의 구역이 연결한 ${programLabel(draft, pi)}의 구역과 다릅니다. 같은 구역을 고르거나 공연 순서 연결을 빼주세요.`;
        if (!(
            Date.parse(t.startAt) < Date.parse(p.endAt) &&
            Date.parse(p.startAt) < Date.parse(t.endAt)
        ))
            return `${taskLabel(draft, i)}의 시간이 연결한 ${programLabel(draft, pi)}의 시간과 겹치지 않습니다. 시간을 맞추거나 공연 순서 연결을 빼주세요.`;
    }
    const bundles = new Map<string, number[]>();
    for (const [i, t] of draft.tasks.entries())
        bundles.set(t.bundleKey, [...(bundles.get(t.bundleKey) ?? []), i]);
    for (const [key, members] of bundles) {
        const group = draft.tasks[members[0]].workGroupId;
        for (const i of members)
            if (draft.tasks[i].workGroupId !== group)
                return `업무 묶음 '${key}'에 다른 직무 그룹의 업무가 섞였습니다(${taskLabel(draft, members[0])}, ${taskLabel(draft, i)}). 한 묶음은 한 사람이 맡으므로 같은 그룹이어야 합니다.`;
        const ordered = [...members].sort(
            (a, b) =>
                Date.parse(draft.tasks[a].startAt) - Date.parse(draft.tasks[b].startAt) || a - b,
        );
        let last: number | null = null;
        for (const i of ordered) {
            const t = draft.tasks[i];
            if (last !== null && Date.parse(t.startAt) < Date.parse(draft.tasks[last].endAt))
                return `업무 묶음 '${key}' 안에서 ${taskLabel(draft, last)}, ${taskLabel(draft, i)}의 시간이 겹칩니다. 한 사람이 맡으므로 시간을 나누거나 묶음 이름을 다르게 정해주세요.`;
            if (last === null || Date.parse(t.endAt) > Date.parse(draft.tasks[last].endAt))
                last = i;
        }
    }
    return null;
}

const REASONS: Record<string, string> = {
    TIME_ORDER: '종료 시각이 시작 시각보다 뒤여야 합니다',
    PROGRAM_CONTEXT: '연결한 공연 순서와 구역이 같고 시간이 겹쳐야 합니다',
    INVALID_REFERENCE: '가리키는 항목이 없습니다. 다시 선택해주세요',
    DUPLICATE: '같은 이름이나 항목이 이미 있습니다',
    BUNDLE_MISMATCH: '같은 업무 묶음의 업무는 같은 직무 그룹이어야 합니다',
    TIME_OVERLAP: '같은 업무 묶음 안에서 시간이 겹칩니다',
};

/** 서버의 400 details.fields(예: tasks[1].programId, PROGRAM_CONTEXT)를 항목 이름과 사유로 바꾼다. */
export function explainDraftRejection(
    fields: { field: string; reason: string }[] | undefined,
    draft: Draft,
): string | undefined {
    const first = fields?.[0];
    if (!first) return undefined;
    const m = /^(workGroups|zones|programs|tasks)\[(\d+)\]/.exec(first.field);
    const i = m ? Number(m[2]) : -1;
    const where = !m
        ? '초안'
        : m[1] === 'workGroups'
          ? groupLabel(draft, i)
          : m[1] === 'zones'
            ? zoneLabel(draft, i)
            : m[1] === 'programs'
              ? programLabel(draft, i)
              : taskLabel(draft, i);
    const reason = REASONS[first.reason] ?? `값을 확인해주세요(${first.reason})`;
    return `서버가 초안을 받지 않았습니다. ${where}: ${reason}.`;
}

/**
 * 첫 번째로 고칠 곳을 알려 준다. 없으면 null.
 * relations=false면 서버만 아는 관계 규칙(공연 순서·묶음)은 보지 않는다(서버에 보내지 않는 데모 계획 수정).
 */
export function draftProblem(draft: Draft, { relations = true } = {}): string | null {
    const groups = new Set(draft.workGroups.map(refId));
    const zones = new Set(draft.zones.map(refId));
    const programs = new Set(draft.programs.map(refId));
    for (const [i, g] of draft.workGroups.entries()) {
        const p = textProblem(`직무 그룹 ${i + 1}`, '이름', g.name, 20);
        if (p) return p;
    }
    for (const [i, z] of draft.zones.entries()) {
        const where = `구역 ${i + 1}(${z.name.trim() || '이름 없음'})`;
        const p =
            textProblem(where, '이름', z.name, 30) ??
            (z.description != null && codePoints(z.description) > ZONE_DESCRIPTION_MAX
                ? `${where}의 설명은 ${ZONE_DESCRIPTION_MAX}자 이하로 줄여주세요.`
                : null);
        if (p) return p;
    }
    for (const [i, pr] of draft.programs.entries()) {
        const where = `공연 순서 ${i + 1}(${pr.name.trim() || '이름 없음'})`;
        const p =
            textProblem(where, '이름', pr.name, 20) ??
            (pr.zoneId !== null && !zones.has(pr.zoneId)
                ? `${where}의 공연 구역을 다시 선택해주세요.`
                : null) ??
            timeProblem(where, pr.startAt, pr.endAt);
        if (p) return p;
    }
    for (const [i, t] of draft.tasks.entries()) {
        const where = `업무 ${i + 1}(${t.title.trim() || '제목 없음'})`;
        const p =
            textProblem(where, '제목', t.title, 100) ??
            (!groups.has(t.workGroupId) ? `${where}의 직무 그룹을 선택해주세요.` : null) ??
            (!zones.has(t.zoneId) ? `${where}의 구역을 선택해주세요.` : null) ??
            (t.programId !== null && !programs.has(t.programId)
                ? `${where}의 공연 순서를 다시 선택해주세요.`
                : null) ??
            textProblem(where, '업무 묶음 이름', t.bundleKey) ??
            timeProblem(where, t.startAt, t.endAt) ??
            (t.steps.length === 0 ? `${where}의 수행 단계를 한 줄 이상 적어주세요.` : null) ??
            (t.steps.length > 6 ? `${where}의 수행 단계는 6개 이하로 줄여주세요.` : null) ??
            (t.steps.some((s) => codePoints(s) > 120)
                ? `${where}의 수행 단계는 한 줄에 120자 이하로 줄여주세요.`
                : null) ??
            t.checklist.reduce<string | null>(
                (found, c, ci) =>
                    found ?? textProblem(`${where}의 점검 항목 ${ci + 1}`, '내용', c.text, 80),
                null,
            );
        if (p) return p;
    }
    for (const [i, n] of draft.instructions.entries()) {
        const p = textProblem(`공통 안내 ${i + 1}`, '내용', n.body);
        if (p) return p;
    }
    return (
        duplicateName(draft.workGroups, (i) => groupLabel(draft, i)) ??
        duplicateName(draft.zones, (i) => zoneLabel(draft, i)) ??
        duplicateName(draft.programs, (i) => programLabel(draft, i)) ??
        (relations ? relationProblem(draft) : null)
    );
}
