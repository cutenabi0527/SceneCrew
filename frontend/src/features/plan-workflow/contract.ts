import { fail } from '../../shared/api/schema';
import { parseV9 } from '../../shared/api/v9';

// 계획·업무 응답 검사. v9 계약 묶음(shared/api/v9)의 스키마를 그대로 쓴다.
// 업무 단건 조회(051)는 v9 선택 필드 equipment·safety가 있는 V9Task, 목록·시작·완료는 Task다.
export const parse = parseV9;
export interface Ref {
    id?: string;
    tempId?: string;
}
export const refId = (item: Ref) => item.id ?? item.tempId!;
export interface Group extends Ref {
    name: string;
    instructions: string;
}
export interface Zone extends Ref {
    name: string;
    description?: string | null;
}
export interface Program extends Ref {
    name: string;
    zoneId: string | null;
    startAt: string;
    endAt: string;
}
export interface DraftTask extends Ref {
    workGroupId: string;
    bundleKey: string;
    zoneId: string;
    programId: string | null;
    title: string;
    startAt: string;
    endAt: string;
    steps: string[];
    checklist: { key: string; text: string; required: boolean }[];
}
export interface Draft {
    workGroups: Group[];
    zones: Zone[];
    programs: Program[];
    tasks: DraftTask[];
    instructions: { scope: 'EVENT'; workGroupId: null; body: string }[];
}
export interface Task {
    id: string;
    workGroupId: string;
    assigneeId: string | null;
    title: string;
    status: 'WAITING' | 'IN_PROGRESS' | 'DONE' | 'CANCELED';
    startAt: string;
    endAt: string;
    // 계약(Task·V9Task)에 있는 최초 배정 시각. 묶음 선택(045)이 쓴다. 옛 응답을 위해 선택 필드로 둔다.
    firstAssignedAt?: string | null;
    zoneId: string;
    programId: string | null;
    sourceIssueId: string | null;
    steps: string[];
    checklist: { key: string; text: string; required: boolean; checked: boolean }[];
    bundleKey: string;
    // 업무 단건 조회(051, V9Task)에만 있다. 목록·시작·완료 응답에는 없다.
    equipment?: TaskEquipment[];
    safety?: TaskSafety | null;
}
/** 업무에 묶인 장비 이동·설치 지시(V9TaskEquipment) */
export interface TaskEquipment {
    assetId: string;
    componentIds: string[];
    pickupZoneId: string | null;
    destinationZoneId: string;
    instruction: string;
}
/** 업무에 묶인 구역 안전 관측 요약(V9TaskSafety). 관측만으로 업무가 바뀌지 않는다. */
export interface TaskSafety {
    zoneId: string;
    observationId: string | null;
    estimatedCount: number | null;
    capturedAt: string | null;
    freshness: 'UNKNOWN' | 'FRESH' | 'STALE' | 'INVALID_MAPPING';
    qualityReasons: string[];
    instructions: string[];
}
export interface Plan {
    eventId: string;
    eventRevision: string;
    viewerRole: 'CHIEF' | 'WORKER';
    workGroups: { id: string; name: string; instructions: string | null }[];
    zones: (Zone & { id: string })[];
    programs: (Program & { id: string })[];
    tasks: {
        id: string;
        workGroupId: string;
        title: string;
        startAt: string;
        endAt: string;
        zoneId: string;
        programId: string | null;
    }[];
    instructions: { scope: 'EVENT' | 'WORK_GROUP'; workGroupId: string | null; body: string }[];
}
export interface EventDetail {
    id: string;
    name: string;
    description: string;
    instructions: string;
    state: 'DRAFT' | 'RECRUITING' | 'LIVE' | 'ENDED';
    revision: string;
    myParticipation: {
        memberId: string;
        participationRevision: string;
        active: boolean;
        eventRole: 'CHIEF' | 'WORKER';
        workGroupId: string | null;
    };
}
export interface Extraction {
    jobId: string;
    kind: 'EXTRACT_EVENT_INPUT';
    state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
    attempt: string;
    result: {
        fields: Record<string, string>;
        missingRequired: string[];
        conflictingValues: { field: string; values: string[] }[];
        unreadableFiles: { documentId: string; code: string }[];
        /** #284 선택 필드: 평면도로 보이는 PDF 페이지 후보. 계획 생성은 첫 장만 쓴다(11/11). */
        floorPlanPages?: { documentId: string; pageNumber: number }[];
        /** V9-D66 선택 필드: 문서 공연 순서 표(곡 이름·시각)와 업무 일정표의 직무 이름. PDF 탭이 뼈대 저장에 쓴다. */
        programs?: { title: string; startAt: string; endAt: string }[];
        workGroups?: { name: string }[];
        resultStatus: 'COMPLETE' | 'PARTIAL';
    } | null;
    error: { code: string; retryable: boolean } | null;
}
export interface Upload {
    eventRevision: string;
    files: (
        | {
              index: number;
              status: 'REGISTERED';
              document: {
                  id: string;
                  originalName: string;
                  bytes: string;
                  pageCount: number | null;
                  expiresAt: string;
              };
          }
        | {
              index: number;
              status: 'FAILED';
              error: { code: string; message: string; requestId: string };
          }
    )[];
}
/** 구역 설명 최대 길이(code point) */
export const ZONE_DESCRIPTION_MAX = 1000;
export const codePoints = (value: string) => [...value].length;

export function validateDraft(value: Draft, revision: string, candidate = false): Draft {
    if (candidate) parse('PlanCandidate', value);
    else parse('DraftPlanWrite', { ...value, expectedEventRevision: revision });
    const arrays = [value.workGroups, value.zones, value.programs, value.tasks];
    const allTemps: string[] = [];
    for (const items of arrays) {
        const ids = items.map(refId);
        if (new Set(ids).size !== ids.length) fail('draft.ids');
        allTemps.push(...items.flatMap((item) => (item.tempId ? [item.tempId] : [])));
    }
    if (new Set(allTemps).size !== allTemps.length) fail('draft.tempId');
    // Zone.description은 선택/null이며 최대 1,000 code point다(UTF-16 길이가 아니라 문자 단위로 센다)
    for (const z of value.zones)
        if (z.description != null && codePoints(z.description) > ZONE_DESCRIPTION_MAX)
            fail('zone.description');
    const [groups, zones, programs] = arrays.map((items) => new Set(items.map(refId)));
    for (const p of value.programs) {
        if (p.zoneId !== null && !zones.has(p.zoneId)) fail('program.zoneId');
        if (Date.parse(p.startAt) >= Date.parse(p.endAt)) fail('program.endAt');
    }
    for (const t of value.tasks) {
        if (
            !groups.has(t.workGroupId) ||
            !zones.has(t.zoneId) ||
            (t.programId !== null && !programs.has(t.programId))
        )
            fail('task.references');
        if (
            Date.parse(t.startAt) >= Date.parse(t.endAt) ||
            new Set(t.checklist.map((c) => c.key)).size !== t.checklist.length
        )
            fail('task');
    }
    return value;
}
export function currentDraft(plan: Plan, tasks: Task[]): Draft {
    const draft: Draft = {
        workGroups: plan.workGroups.map((g) => {
            if (g.instructions === null) fail('group.instructions');
            return { ...g, instructions: g.instructions! };
        }),
        zones: structuredClone(plan.zones),
        programs: structuredClone(plan.programs),
        instructions: plan.instructions
            .filter((i) => i.scope === 'EVENT')
            .map((i) => ({ ...i, scope: 'EVENT', workGroupId: null })),
        tasks: plan.tasks.map((summary) => {
            const t = tasks.find((task) => task.id === summary.id);
            if (
                !t ||
                t.title !== summary.title ||
                t.workGroupId !== summary.workGroupId ||
                t.zoneId !== summary.zoneId ||
                t.programId !== summary.programId ||
                t.startAt !== summary.startAt ||
                t.endAt !== summary.endAt
            )
                fail('task.current');
            return {
                ...summary,
                bundleKey: t!.bundleKey,
                steps: [...t!.steps],
                checklist: t!.checklist.map(({ key, text, required }) => ({ key, text, required })),
            };
        }),
    };
    return validateDraft(draft, plan.eventRevision);
}
