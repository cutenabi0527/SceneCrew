import { basicsPatch } from '../event-admin/patch';
import { BASIC_FIELDS } from '../event-admin/labels';
import { prefillEdits } from '../event-admin/prefill';
import type { PlanJob, PlanQuestion } from '../plan-job/contract';
import { workFailureMessage } from '../plan-workflow/action';
import { draftProblem, tidyDraft } from '../plan-workflow/draft-check';
import { splitExtracted, zonesFromCondition } from '../plan-workflow/extraction-fields';
import type { Draft, EventDetail, Extraction, Plan, Upload } from '../plan-workflow/contract';
import { remapLayout, type DraftLayout } from '../unconfirmed-draft/layout';
import {
    emptyEquipment,
    emptyVenue,
    type EquipmentDraft,
    type UnconfirmedDraft,
    type VenueDraft,
} from '../unconfirmed-draft/contract';

// PDF 탭 자동 진행(V9-D65). 문서를 올리고 추출을 한 번 누르면 기존 API를 차례로 불러 공개 직전까지 저장한다.
// 업로드 → 추출 → 기본정보(024) → 조건(026) → 구역·직무 그룹·공연 순서 뼈대(028) → 계획 생성(043) →
// READY 후보 저장(028) → 미확정 초안 출처·평면도(Q3). 빈 필수칸·충돌·NEEDS_INFO·오류에서만 멈춘다.
// 값은 문서에 적힌 것만 쓴다(지어내지 않는다). 모든 쓰기는 직전 응답의 revision으로 하나씩 순서대로 보낸다.
// 화면과 떨어진 순수 async라 단위 시험에서 API를 바꿔 끼워 확인한다.

export type PdfStep =
    'upload' | 'extract' | 'basics' | 'input' | 'skeleton' | 'generate' | 'apply' | 'unconfirmed';

export const STEP_LABEL: Record<PdfStep, string> = {
    upload: '문서 올리기',
    extract: '문서 내용 추출',
    basics: '공연 기본정보 저장',
    input: '계획 생성 조건 저장',
    skeleton: '구역·직무 그룹·공연 순서 저장',
    generate: 'AI 계획 생성',
    apply: '계획 저장',
    unconfirmed: '평면도·장비 초안 저장',
};

export type PdfSummary = {
    workGroups: number;
    zones: number;
    programs: number;
    tasks: number;
    floorPlan: boolean;
    /** 미확정 초안 출처를 남기지 못했으면 안내(공개는 할 수 있지만 카메라·장비 칸이 생기지 않는다) */
    warning: string | null;
};

export type PdfOutcome =
    | { kind: 'done'; summary: PdfSummary }
    /** 올린 문서가 없다(새로고침으로 넘겨받은 파일을 잃었다) */
    | { kind: 'need-files' }
    /** 문서에서 찾지 못했거나 값이 둘 이상인 필수 기본정보. 그 칸만 입력받는다 */
    | {
          kind: 'missing';
          fields: string[];
          conflicts: { field: string; values: string[] }[];
          unreadable: number;
      }
    /** AI가 계획을 만들려면 더 알아야 한다(NEEDS_INFO) */
    | { kind: 'questions'; questions: PlanQuestion[] }
    | { kind: 'failed'; step: PdfStep; message: string };

/** 멈춘 뒤 사용자가 채운 값. basics는 기본정보 키, conditions는 계획 생성 조건 키다 */
export type PdfAnswers = {
    basics?: Record<string, string>;
    conditions?: Record<string, string>;
};

export interface PdfDeps {
    read(): Promise<{ event: EventDetail; plan: Plan; draft: Draft | null }>;
    upload(files: File[]): Promise<Upload>;
    generate(kind: 'extract' | 'generate', revision: string): Promise<string>;
    /** 이 탭이 기억하는 마지막 작업(새로고침 뒤 이어 가기) */
    lastJob(kind: 'extract' | 'generate'): string | undefined;
    extraction(id: string): Promise<Extraction>;
    planJob(id: string): Promise<PlanJob>;
    basics(fields: Record<string, string | null>, revision: string): Promise<EventDetail>;
    input(fields: Record<string, string>, revision: string): Promise<EventDetail>;
    save(
        draft: Draft,
        revision: string,
    ): Promise<{ plan: Plan; idMappings: { tempId: string; id: string }[] }>;
    candidate(
        id: string,
        revision: string,
    ): Promise<{ plan: Draft; layout: DraftLayout | null; jobId: string }>;
    savedConditions(): Record<string, string>;
    rememberConditions(conditions: Record<string, string>): void;
    readUnconfirmed(): Promise<UnconfirmedDraft | null>;
    saveUnconfirmed(input: {
        expectedRevision: string;
        equipmentDraft: EquipmentDraft;
        venueDraft: VenueDraft;
        sourceJobId: string | null;
    }): Promise<{ kind: string }>;
    wait(ms: number): Promise<void>;
    tempId(): string;
}

export const POLL_MS = 2000;
/** 추출·생성을 기다리는 최대 횟수(2초 × 300 = 10분). 넘으면 멈추고 다시 이어 가게 한다 */
const MAX_POLLS = 300;
/** BE 026 조건 키 상한 */
const MAX_CONDITIONS = 20;
const REQUIRED = [
    'date',
    'prepStartAt',
    'openAt',
    'closeAt',
    'teardownEndAt',
    'locationName',
    'address',
    'totalCapacity',
] as const;
const BASIC_KEYS = new Set<string>(BASIC_FIELDS.map((f) => f.key));
const TIME_KEYS = new Set<string>(BASIC_FIELDS.filter((f) => f.kind === 'time').map((f) => f.key));
const nameKey = (value: string) => value.replace(/\s+/g, '').toLowerCase();

class Stop extends Error {
    constructor(readonly outcome: PdfOutcome) {
        super(outcome.kind);
    }
}
const failed = (step: PdfStep, message: string) => new Stop({ kind: 'failed', step, message });

async function poll<T extends { state: string }>(
    deps: PdfDeps,
    step: PdfStep,
    read: () => Promise<T>,
    signal?: AbortSignal,
): Promise<T> {
    for (let i = 0; i < MAX_POLLS; i++) {
        if (signal?.aborted) throw new DOMException('멈춤', 'AbortError');
        const job = await read();
        if (job.state !== 'QUEUED' && job.state !== 'RUNNING') return job;
        await deps.wait(POLL_MS);
    }
    throw failed(step, '작업이 오래 걸리고 있습니다. 잠시 뒤 이어서 진행을 눌러 주세요.');
}

/**
 * 추출 결과 + 사용자가 채운 값에서 저장할 기본정보와 조건을 만든다. 남은 빈 필수칸·충돌이 있으면 missing이다.
 * 시각 칸은 prefillEdits가 읽을 수 있는 값만 쓰고(날짜와 합칠 수 없는 시각은 빈 칸으로 본다), 사용자가 채운 값이 우선한다.
 */
export function plannedInput(
    result: NonNullable<Extraction['result']>,
    answers: PdfAnswers = {},
    saved: Record<string, unknown> = {},
):
    | { kind: 'ready'; basics: Record<string, string | null>; conditions: Record<string, string> }
    | Extract<PdfOutcome, { kind: 'missing' }> {
    const { basics, conditions } = splitExtracted(result.fields);
    const given = Object.fromEntries(
        Object.entries(answers.basics ?? {}).filter(([k, v]) => BASIC_KEYS.has(k) && v.trim()),
    );
    // 이미 공연에 저장된 값(앞서 물어 저장했거나 직접 입력한 값)은 있는 값으로 본다. 다시 묻지 않는다(#410 리뷰).
    const has = (key: string) =>
        saved[key] !== null && saved[key] !== undefined && saved[key] !== '';
    const savedDate = typeof saved.date === 'string' ? saved.date.slice(0, 10) : undefined;
    const edits = prefillEdits({
        ...(savedDate && !basics.date ? { date: savedDate } : {}),
        ...basics,
        ...given,
    });
    const conflicts = result.conflictingValues.filter(
        (c) => BASIC_KEYS.has(c.field) && !(c.field in edits) && !has(c.field),
    );
    // 날짜를 아직 모르면 문서의 시:분 시각은 날짜만 채우면 저절로 채워지므로 따로 묻지 않는다
    const dateOpen = !('date' in edits);
    const waitsForDate = (key: string) =>
        dateOpen && TIME_KEYS.has(key) && !!(basics[key] ?? given[key])?.trim();
    const missing = REQUIRED.filter(
        (key) =>
            !(key in edits) &&
            !has(key) &&
            !conflicts.some((c) => c.field === key) &&
            !waitsForDate(key),
    );
    if (missing.length || conflicts.length)
        return {
            kind: 'missing',
            fields: [...missing],
            conflicts,
            unreadable: result.unreadableFiles.length,
        };
    return {
        kind: 'ready',
        basics: basicsPatch(edits),
        conditions: { ...conditions, ...(answers.conditions ?? {}) },
    };
}

/** 지금 초안에 없는 구역·직무 그룹·공연 순서만 더한 뼈대. 더할 것이 없으면 null(이미 저장했다) */
export function skeleton(
    draft: Draft,
    zoneNames: string[],
    result: NonNullable<Extraction['result']>,
    tempId: () => string,
): Draft | null {
    const has = (items: { name: string }[], name: string) =>
        items.some((item) => nameKey(item.name) === nameKey(name));
    const zones = zoneNames
        .filter((name) => !has(draft.zones, name))
        .map((name) => ({ tempId: tempId(), name, description: null }));
    const workGroups = (result.workGroups ?? [])
        .filter((g) => !has(draft.workGroups, g.name))
        .map((g) => ({ tempId: tempId(), name: g.name, instructions: '' }));
    const programs = (result.programs ?? [])
        .filter((p) => !has(draft.programs, p.title))
        .map((p) => ({
            tempId: tempId(),
            name: p.title,
            zoneId: null,
            startAt: p.startAt,
            endAt: p.endAt,
        }));
    if (!zones.length && !workGroups.length && !programs.length) return null;
    return {
        ...draft,
        zones: [...draft.zones, ...zones],
        workGroups: [...draft.workGroups, ...workGroups],
        programs: [...draft.programs, ...programs],
    };
}

function messageOf(error: unknown) {
    return workFailureMessage(error) || '요청을 처리하지 못했습니다.';
}

/**
 * PDF 탭 자동 진행. 같은 입력으로 다시 불러도 안전하다: 이미 업무가 저장돼 있으면 완료로 보고, 뼈대는 없는 것만 더한다.
 * files가 있으면 올리고 새로 추출한다. 없으면 이 탭이 기억하는 마지막 추출(또는 계획 생성)부터 이어 간다.
 */
export async function runPdf(
    deps: PdfDeps,
    options: {
        files?: File[];
        answers?: PdfAnswers;
        /** 새 문서·새 답 없이 이어 가기: 이 탭이 기억하는 계획 생성 결과를 다시 쓴다 */
        reuse?: boolean;
        signal?: AbortSignal;
    },
    report: (step: PdfStep) => void,
): Promise<PdfOutcome> {
    let step: PdfStep = 'upload';
    const at = (next: PdfStep) => {
        step = next;
        report(next);
    };
    try {
        let current = await deps.read();
        let revision = current.event.revision;
        if (current.event.state !== 'DRAFT')
            throw failed(step, '작성 중인 공연에서만 문서로 계획을 만들 수 있습니다.');
        if (!options.files?.length && current.plan.tasks.length > 0 && !options.answers)
            return { kind: 'done', summary: summary(current.plan, false, null) };

        // 0. 이어 가기: 기억한 계획 생성이 지금 입력(revision)으로 만든 것이면 다시 생성하지 않고 그 결과를 쓴다.
        //    진행 중이면 기다린다(행사당 진행 중 생성은 1개라 새로 요청하면 거절된다, #410 리뷰).
        const previous =
            options.reuse && !options.files?.length ? deps.lastJob('generate') : undefined;
        if (previous) {
            at('generate');
            const job = await poll(deps, step, () => deps.planJob(previous), options.signal);
            if (job.state === 'SUCCEEDED' && job.result.status === 'NEEDS_INFO')
                return { kind: 'questions', questions: job.result.questions };
            if (job.state === 'SUCCEEDED') {
                const reused = await deps.candidate(previous, revision).catch(() => null);
                if (reused) return await apply(previous, reused, revision);
            }
        }

        // 1. 문서 올리기와 추출 요청
        let extractionId = options.files?.length ? undefined : deps.lastJob('extract');
        if (options.files?.length) {
            at('upload');
            const uploaded = await deps.upload(options.files);
            if (uploaded.files.some((f) => f.status !== 'REGISTERED'))
                throw failed(
                    step,
                    '등록하지 못한 문서가 있습니다. 형식(PDF/TXT/MD)과 크기를 확인하고 다시 올려 주세요.',
                );
            revision = uploaded.eventRevision;
            at('extract');
            extractionId = await deps.generate('extract', revision);
        }
        if (!extractionId) return { kind: 'need-files' };

        // 2. 추출 기다리기
        at('extract');
        const extraction = await poll(
            deps,
            step,
            () => deps.extraction(extractionId!),
            options.signal,
        );
        if (!extraction.result)
            throw failed(
                step,
                extraction.error?.code === 'EXTERNAL_AI_CONSENT_REQUIRED'
                    ? '외부 AI 사용 동의가 필요합니다. 내 정보에서 동의한 뒤 이어서 진행해 주세요.'
                    : '문서를 읽지 못했습니다. 문서를 확인하고 다시 올려 주세요.',
            );
        const result = extraction.result;
        const planned = plannedInput(
            result,
            options.answers,
            current.event as unknown as Record<string, unknown>,
        );
        if (planned.kind === 'missing') return planned;

        // 3. 기본정보(024)와 조건(026). 조건 저장은 전체 교체라 이 탭이 저장한 조건에 더한다
        at('basics');
        if (Object.keys(planned.basics).length)
            revision = (await deps.basics(planned.basics, revision)).revision;
        at('input');
        const conditions = { ...deps.savedConditions(), ...planned.conditions };
        if (Object.keys(conditions).length > MAX_CONDITIONS)
            throw failed(
                step,
                `계획 생성 조건은 ${MAX_CONDITIONS}개까지입니다. 문서 부록의 조건 수를 줄여 주세요.`,
            );
        revision = (await deps.input(conditions, revision)).revision;
        deps.rememberConditions(conditions);

        // 4. 뼈대(028). AI는 요청의 구역·직무 그룹·공연 순서에 업무를 놓는다
        at('skeleton');
        current = await deps.read();
        revision = current.event.revision;
        const base = current.draft ?? {
            workGroups: [],
            zones: [],
            programs: [],
            tasks: [],
            instructions: [],
        };
        const bones = skeleton(base, zonesFromCondition(conditions.zones), result, deps.tempId);
        if (bones) {
            const next = tidyDraft(bones);
            const problem = draftProblem(next);
            if (problem) throw failed(step, problem);
            revision = (await deps.save(next, revision)).plan.eventRevision;
        }

        // 5. 계획 생성(043)
        at('generate');
        const jobId = await deps.generate('generate', revision);
        const job = await poll(deps, step, () => deps.planJob(jobId), options.signal);
        if (job.state === 'FAILED')
            throw failed(
                step,
                job.error.code === 'EXTERNAL_AI_CONSENT_REQUIRED'
                    ? '외부 AI 사용 동의가 필요합니다. 내 정보에서 동의한 뒤 이어서 진행해 주세요.'
                    : job.error.retryable
                      ? 'AI 계획 생성이 잠시 실패했습니다. 이어서 진행을 눌러 다시 시도해 주세요.'
                      : 'AI 계획 생성에 실패했습니다. 직접 입력 탭에서 확인해 주세요.',
            );
        if (job.state !== 'SUCCEEDED') throw failed(step, '계획 생성 결과를 확인하지 못했습니다.');
        if (job.result.status === 'NEEDS_INFO')
            return { kind: 'questions', questions: job.result.questions };

        return await apply(jobId, await deps.candidate(jobId, revision), revision);
    } catch (error) {
        if (error instanceof Stop) return error.outcome;
        if (error instanceof DOMException && error.name === 'AbortError') throw error;
        return { kind: 'failed', step, message: messageOf(error) };
    }

    // 6. READY 후보 저장(028)과 7. 미확정 초안
    async function apply(
        jobId: string,
        candidate: Awaited<ReturnType<PdfDeps['candidate']>>,
        revision: string,
    ): Promise<PdfOutcome> {
        at('apply');
        const plan = tidyDraft(structuredClone(candidate.plan));
        const problem = draftProblem(plan);
        if (problem) throw failed(step, `AI 계획을 그대로 저장할 수 없습니다. ${problem}`);
        const saved = await deps.save(plan, revision);

        // 7. 미확정 초안: 출처(sourceJobId)와 AI가 읽은 평면도. 공개 때 카메라·장비 칸과 안전 지도가 된다
        at('unconfirmed');
        let warning: string | null = null;
        let floorPlan = false;
        try {
            const draft = await deps.readUnconfirmed();
            const layout =
                draft?.venueDraft.layout ??
                (candidate.layout ? remapLayout(candidate.layout, saved.idMappings) : null);
            floorPlan = !!layout;
            const outcome = await deps.saveUnconfirmed({
                expectedRevision: draft?.revision ?? '0',
                equipmentDraft: draft?.equipmentDraft ?? emptyEquipment(),
                venueDraft: {
                    ...(draft?.venueDraft ?? emptyVenue()),
                    layout: layout as unknown as VenueDraft['layout'],
                },
                sourceJobId: jobId,
            });
            if (outcome.kind !== 'done')
                warning =
                    '평면도·장비 초안을 저장했는지 확인하지 못했습니다. 직접 입력 탭의 미확정 초안에서 확인해 주세요.';
        } catch {
            warning =
                '평면도·장비 초안을 저장하지 못했습니다. 공개는 할 수 있지만 카메라·장비 칸이 생기지 않습니다. 직접 입력 탭의 미확정 초안에서 저장해 주세요.';
        }
        return { kind: 'done', summary: summary(saved.plan, floorPlan, warning) };
    }
}

function summary(plan: Plan, floorPlan: boolean, warning: string | null): PdfSummary {
    return {
        workGroups: plan.workGroups.length,
        zones: plan.zones.length,
        programs: plan.programs.length,
        tasks: plan.tasks.length,
        floorPlan,
        warning,
    };
}
