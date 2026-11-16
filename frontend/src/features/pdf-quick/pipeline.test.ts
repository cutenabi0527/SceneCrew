import { describe, expect, it } from 'vitest';
import type { PlanJob } from '../plan-job/contract';
import type { Draft, EventDetail, Extraction, Plan } from '../plan-workflow/contract';
import { plannedInput, runPdf, skeleton, type PdfDeps, type PdfStep } from './pipeline';
import { dropFiles, handFiles, peekFiles, provisionalName } from './handoff';

const EVENT = '10000000-0000-4000-8000-000000000001';
const DOC = '20000000-0000-4000-8000-000000000001';

const FIELDS = {
    name: '[검증] PDF 한 곡 공연',
    date: '2026-11-13',
    prepStartAt: '11:30:00',
    openAt: '12:00:00',
    closeAt: '12:35:00',
    teardownEndAt: '12:50:00',
    locationName: '씬크루 소극장',
    address: '서울특별시 강남구 테헤란로 212',
    totalCapacity: '5',
    zones: 'A구역, B구역',
    programOrder: '새벽을 여는 노래',
    receiverLinkBy: 'CHIEF',
};

function result(
    over: Partial<NonNullable<Extraction['result']>> = {},
): NonNullable<Extraction['result']> {
    return {
        fields: { ...FIELDS },
        missingRequired: [],
        conflictingValues: [],
        unreadableFiles: [],
        programs: [
            {
                title: '새벽을 여는 노래',
                startAt: '2026-11-13T12:20:00+09:00',
                endAt: '2026-11-13T12:25:00+09:00',
            },
        ],
        workGroups: [{ name: '장비 운영' }, { name: '안전 통제' }, { name: '장비 오류 대처' }],
        resultStatus: 'COMPLETE',
        ...over,
    };
}

const emptyDraft = (): Draft => ({
    workGroups: [],
    zones: [],
    programs: [],
    tasks: [],
    instructions: [],
});

function plan(rev: string, tasks = 0): Plan {
    return {
        eventId: EVENT,
        eventRevision: rev,
        viewerRole: 'CHIEF',
        workGroups: [{ id: 'g1', name: '안전 통제', instructions: '' }],
        zones: [{ id: 'z1', name: 'A구역' }],
        programs: [],
        tasks: Array.from({ length: tasks }, (_, i) => ({
            id: `t${i}`,
            workGroupId: 'g1',
            title: '업무',
            startAt: '2026-11-13T02:30:00Z',
            endAt: '2026-11-13T02:50:00Z',
            zoneId: 'z1',
            programId: null,
        })),
        instructions: [],
    };
}

const event = (revision: string, saved: Record<string, unknown> = {}): EventDetail =>
    ({ id: EVENT, revision, state: 'DRAFT', ...saved }) as unknown as EventDetail;

/** 호출 순서와 본문을 기록하는 가짜 API. revision은 쓰기마다 1씩 오른다 */
function fake(options: {
    extraction?: NonNullable<Extraction['result']>;
    planResult?: PlanJob;
    tasks?: number;
    lastExtract?: string;
    lastGenerate?: string;
    /** 공연에 이미 저장된 기본정보 */
    saved?: Record<string, unknown>;
    /** 기억한 생성 결과가 지금 revision과 맞는지 */
    stale?: boolean;
}) {
    let rev = 1;
    const calls: { name: string; revision?: string; body?: unknown }[] = [];
    let extractPolls = 0;
    const deps: PdfDeps = {
        read: async () => ({
            event: event(String(rev), options.saved),
            plan: plan(String(rev), options.tasks ?? 0),
            draft: emptyDraft(),
        }),
        upload: async () => {
            calls.push({ name: 'upload' });
            rev += 1;
            return {
                eventRevision: String(rev),
                files: [
                    {
                        index: 0,
                        status: 'REGISTERED',
                        document: {
                            id: DOC,
                            originalName: '계획서.pdf',
                            bytes: '10',
                            pageCount: 6,
                            expiresAt: '2026-11-20T00:00:00Z',
                        },
                    },
                ],
            };
        },
        generate: async (kind, revision) => {
            calls.push({ name: `generate:${kind}`, revision });
            return kind === 'extract' ? 'job-extract' : 'job-plan';
        },
        lastJob: (kind) => (kind === 'extract' ? options.lastExtract : options.lastGenerate),
        extraction: async (id) => {
            extractPolls += 1;
            return {
                jobId: id,
                kind: 'EXTRACT_EVENT_INPUT',
                state: extractPolls < 2 ? 'RUNNING' : 'SUCCEEDED',
                attempt: '1',
                result: extractPolls < 2 ? null : (options.extraction ?? result()),
                error: null,
            };
        },
        planJob: async () =>
            options.planResult ??
            ({
                jobId: 'job-plan',
                kind: 'GENERATE_PLAN',
                attempt: '1',
                state: 'SUCCEEDED',
                result: {
                    status: 'READY',
                    summary: { workGroups: 3, zones: 2, programs: 1, tasks: 4, instructions: 1 },
                    layoutZones: 2,
                },
                error: null,
            } as PlanJob),
        basics: async (body, revision) => {
            calls.push({ name: 'basics', revision, body });
            rev += 1;
            return event(String(rev));
        },
        input: async (body, revision) => {
            calls.push({ name: 'input', revision, body });
            rev += 1;
            return event(String(rev));
        },
        save: async (draft, revision) => {
            calls.push({ name: 'save', revision, body: draft });
            rev += 1;
            return {
                plan: plan(String(rev), draft.tasks.length),
                idMappings: [{ tempId: 'tmp-zone-a', id: 'z-saved' }],
            };
        },
        candidate: async (id, revision) => {
            calls.push({ name: 'candidate', revision });
            if (options.stale) throw new Error('입력이 바뀌었습니다');
            return {
                jobId: id,
                plan: {
                    workGroups: [{ id: 'g1', name: '안전 통제', instructions: '' }],
                    zones: [{ id: 'z1', name: 'A구역', description: null }],
                    programs: [],
                    tasks: [
                        {
                            tempId: 'tmp-t1',
                            workGroupId: 'g1',
                            bundleKey: 'safety-a',
                            zoneId: 'z1',
                            programId: null,
                            title: 'A구역 동선 점검',
                            startAt: '2026-11-13T02:30:00Z',
                            endAt: '2026-11-13T02:50:00Z',
                            steps: ['동선 확인'],
                            checklist: [],
                        },
                    ],
                    instructions: [],
                },
                layout: {
                    source: { documentId: DOC, pageNumber: 5 },
                    width: 1000,
                    height: 700,
                    zonePolygons: [{ zoneId: 'tmp-zone-a', points: [] }],
                } as never,
            };
        },
        savedConditions: () => ({}),
        rememberConditions: (c) => calls.push({ name: 'remember', body: c }),
        readUnconfirmed: async () => null,
        saveUnconfirmed: async (input) => {
            calls.push({ name: 'unconfirmed', body: input });
            return { kind: 'done' };
        },
        wait: async () => undefined,
        tempId: (() => {
            let n = 0;
            return () => `tmp-${++n}`;
        })(),
    };
    return { deps, calls };
}

const file = () => new File(['%PDF-1.4'], '계획서.pdf', { type: 'application/pdf' });

describe('PDF 탭 자동 진행(V9-D65)', () => {
    it('올리고 추출하면 기본정보·조건·뼈대·생성·후보·미확정 초안을 차례로 저장한다', async () => {
        const { deps, calls } = fake({});
        const steps: PdfStep[] = [];
        const out = await runPdf(deps, { files: [file()] }, (s) => steps.push(s));
        expect(out.kind).toBe('done');
        expect(calls.map((c) => c.name)).toEqual([
            'upload',
            'generate:extract',
            'basics',
            'input',
            'remember',
            'save',
            'generate:generate',
            'candidate',
            'save',
            'unconfirmed',
        ]);
        // 모든 쓰기는 직전 응답의 revision으로 보낸다
        const writes = calls.filter((c) => c.revision);
        expect(writes.map((c) => c.revision)).toEqual(['2', '2', '3', '4', '5', '5', '5']);
        const basics = calls.find((c) => c.name === 'basics')!.body as Record<string, string>;
        expect(basics.name).toBe('[검증] PDF 한 곡 공연');
        expect(basics.totalCapacity).toBe('5');
        expect(Date.parse(basics.openAt)).toBe(new Date(2026, 10, 13, 12, 0).getTime());
        expect(basics).not.toHaveProperty('desiredReserveCount');
        expect(calls.find((c) => c.name === 'input')!.body).toMatchObject({
            zones: 'A구역, B구역',
            receiverLinkBy: 'CHIEF',
        });
        const bones = calls.find((c) => c.name === 'save')!.body as Draft;
        expect(bones.zones.map((z) => z.name)).toEqual(['A구역', 'B구역']);
        expect(bones.workGroups.map((g) => [g.name, g.instructions])).toEqual([
            ['장비 운영', ''],
            ['안전 통제', ''],
            ['장비 오류 대처', ''],
        ]);
        expect(bones.programs).toEqual([
            expect.objectContaining({ name: '새벽을 여는 노래', zoneId: null }),
        ]);
        expect(bones.tasks).toEqual([]);
        const unconfirmed = calls.find((c) => c.name === 'unconfirmed')!.body as {
            sourceJobId: string;
            venueDraft: { layout: { zonePolygons: { zoneId: string }[] } };
        };
        expect(unconfirmed.sourceJobId).toBe('job-plan');
        expect(unconfirmed.venueDraft.layout.zonePolygons[0].zoneId).toBe('z-saved');
        expect(steps).toContain('generate');
    });

    it('필수 기본정보가 없거나 값이 둘이면 저장하지 않고 그 칸만 묻는다', async () => {
        const fields: Record<string, string> = { ...FIELDS };
        delete fields.address;
        delete fields.date;
        const { deps, calls } = fake({
            extraction: result({
                fields,
                conflictingValues: [{ field: 'date', values: ['2026-11-13', '2026-11-14'] }],
                missingRequired: ['address'],
                resultStatus: 'PARTIAL',
            }),
        });
        const out = await runPdf(deps, { files: [file()] }, () => undefined);
        expect(out).toEqual({
            kind: 'missing',
            fields: ['address'],
            conflicts: [{ field: 'date', values: ['2026-11-13', '2026-11-14'] }],
            unreadable: 0,
        });
        expect(calls.some((c) => c.name === 'basics')).toBe(false);
    });

    it('날짜가 없으면 날짜만 묻고(시:분 시각은 날짜를 채우면 채워진다), 채운 값으로 이어 간다', async () => {
        const fields: Record<string, string> = { ...FIELDS };
        delete fields.date;
        const r = result({ fields, missingRequired: ['date'] });
        expect(plannedInput(r)).toMatchObject({
            kind: 'missing',
            fields: ['date'],
        });
        const ready = plannedInput(r, { basics: { date: '2026-11-13' } });
        expect(ready.kind).toBe('ready');
        const { deps, calls } = fake({ extraction: r, lastExtract: 'job-extract' });
        const out = await runPdf(
            deps,
            { answers: { basics: { date: '2026-11-13' } } },
            () => undefined,
        );
        expect(out.kind).toBe('done');
        expect(calls[0].name).toBe('basics'); // 올리지 않고 기억한 추출부터 이어 간다
    });

    it('공연에 이미 저장된 기본정보는 다시 묻지 않는다(이어 가기, #410 리뷰)', async () => {
        const fields: Record<string, string> = { ...FIELDS };
        delete fields.address;
        delete fields.date;
        const r = result({ fields, missingRequired: ['address', 'date'] });
        expect(plannedInput(r, {}, { address: '서울', date: '2026-11-13' }).kind).toBe('ready');
        const { deps, calls } = fake({
            extraction: r,
            lastExtract: 'job-extract',
            saved: { address: '서울', date: '2026-11-13' },
        });
        const out = await runPdf(deps, {}, () => undefined);
        expect(out.kind).toBe('done');
        const basics = calls.find((c) => c.name === 'basics')!.body as Record<string, string>;
        // 문서의 시:분 시각은 저장된 날짜와 합친다
        expect(Date.parse(basics.prepStartAt)).toBe(new Date(2026, 10, 13, 11, 30).getTime());
    });

    it('이어 가기는 기억한 계획 생성 결과를 다시 쓰고, 입력이 바뀌었으면 새로 생성한다', async () => {
        const reuse = fake({ lastExtract: 'job-extract', lastGenerate: 'job-old' });
        const out = await runPdf(reuse.deps, { reuse: true }, () => undefined);
        expect(out.kind).toBe('done');
        expect(reuse.calls.map((c) => c.name)).toEqual(['candidate', 'save', 'unconfirmed']);
        const stale = fake({ lastExtract: 'job-extract', lastGenerate: 'job-old', stale: true });
        const again = await runPdf(stale.deps, { reuse: true }, () => undefined);
        expect(again.kind).toBe('failed'); // 이 가짜는 후보를 늘 거절한다
        expect(stale.calls.map((c) => c.name)).toContain('generate:generate');
    });

    it('AI가 더 물으면(NEEDS_INFO) 질문을 보이고 멈춘다', async () => {
        const { deps, calls } = fake({
            planResult: {
                jobId: 'job-plan',
                kind: 'GENERATE_PLAN',
                attempt: '1',
                state: 'SUCCEEDED',
                result: {
                    status: 'NEEDS_INFO',
                    questions: [
                        {
                            key: 'staffing',
                            field: 'payload.event.totalCapacity',
                            question: '인원?',
                        },
                    ],
                },
                error: null,
            },
        });
        const out = await runPdf(deps, { files: [file()] }, () => undefined);
        expect(out).toEqual({
            kind: 'questions',
            questions: [
                { key: 'staffing', field: 'payload.event.totalCapacity', question: '인원?' },
            ],
        });
        expect(calls.some((c) => c.name === 'candidate')).toBe(false);
    });

    it('기억한 추출이 없으면 문서를 고르게 하고, 이미 업무가 있으면 저장하지 않고 끝낸다', async () => {
        expect((await runPdf(fake({}).deps, {}, () => undefined)).kind).toBe('need-files');
        const { deps, calls } = fake({ tasks: 3, lastExtract: 'job-extract' });
        const out = await runPdf(deps, {}, () => undefined);
        expect(out).toMatchObject({ kind: 'done', summary: { tasks: 3 } });
        expect(calls).toEqual([]);
    });

    it('계획 생성 실패는 단계와 안내를 남기고 멈춘다', async () => {
        const { deps } = fake({
            planResult: {
                jobId: 'job-plan',
                kind: 'GENERATE_PLAN',
                attempt: '1',
                state: 'FAILED',
                result: null,
                error: { code: 'AI_UNAVAILABLE', retryable: true },
            },
        });
        const out = await runPdf(deps, { files: [file()] }, () => undefined);
        expect(out).toMatchObject({ kind: 'failed', step: 'generate' });
    });
});

describe('뼈대와 넘겨받은 파일', () => {
    it('이미 있는 구역·그룹·공연은 다시 더하지 않는다(다시 불러도 안전하다)', () => {
        const draft: Draft = {
            ...emptyDraft(),
            zones: [{ id: 'z1', name: 'A 구역', description: null }],
            workGroups: [{ id: 'g1', name: '안전 통제', instructions: '' }],
        };
        let n = 0;
        const next = skeleton(draft, ['A구역', 'B구역'], result(), () => `tmp-${++n}`)!;
        expect(next.zones.map((z) => z.name)).toEqual(['A 구역', 'B구역']);
        expect(next.workGroups.map((g) => g.name)).toEqual([
            '안전 통제',
            '장비 운영',
            '장비 오류 대처',
        ]);
        const again = skeleton(next, ['A구역', 'B구역'], result(), () => 'tmp-x');
        expect(again).toBeNull();
    });

    it('파일은 시작한 뒤에만 지우고, 임시 이름은 첫 파일 이름 20자다', () => {
        const f = new File(['x'], '씬크루_한곡공연_운영계획서_v1_최종본.pdf');
        handFiles(EVENT, [f]);
        expect(peekFiles(EVENT)).toEqual([f]);
        dropFiles(EVENT);
        expect(peekFiles(EVENT)).toBeNull();
        expect(provisionalName([f])).toBe('씬크루_한곡공연_운영계획서_v1_최종본'.slice(0, 20));
        expect(provisionalName([])).toBe('새 공연');
    });
});
