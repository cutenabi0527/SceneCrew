import { describe, it, expect, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { createApi } from '../../shared/api/api';
import { ScopeManager } from '../../shared/scope/scope-manager';
import { ScopeDisposedError } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { AiRequestUnavailable, CommandNeedsReview, Workflow } from './client';
import {
    parse,
    currentDraft,
    validateDraft,
    type Draft,
    type Plan,
    type Task,
    type Extraction,
} from './contract';
import {
    accountId,
    eventId,
    memberId,
    jobId,
    taskId,
    event,
    plan,
    task,
    ready,
    candidate,
    extracted,
} from '../../../tests/fixtures/workflow-data';

const json = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
function setup() {
    const scopes = new ScopeManager(new QueryClient());
    scopes.setAccount(accountId);
    const scope = scopes.setParticipation({ eventId, memberId, participationRevision: '1' }).scope!;
    const fetcher = vi.fn<typeof fetch>();
    const session = {
        api: createApi({ base: '/api/v1', transport: fetcher, scopes }),
    } as IdentitySession;
    return { scopes, scope, fetcher, client: new Workflow(session, scope) };
}
const draft = () => currentDraft(parse<Plan>('PlanView', plan), [parse<Task>('Task', task)]);
describe('계획/문서/내 업무 공개 계약', () => {
    it('생성 때의 revision과 현재 입력 revision이 다르면 후보를 가져오지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockResolvedValueOnce(json({ jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }, 202));
        await client.generate('generate', '1');
        expect(() => client.candidate(jobId, '2')).toThrow('생성 후 입력이 바뀌었습니다');
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('접수 당시 revision을 모르는 작업의 후보는 가져오지 않는다(#268)', () => {
        const { client, fetcher } = setup();
        expect(() => client.candidate(jobId, '1')).toThrow('만든 시점의 입력을 확인할 수 없습니다');
        expect(fetcher).not.toHaveBeenCalled();
    });
    it('v9 업무 단건(051)의 장비/안전 필드를 계약대로 받되 초안에는 섞지 않는다', () => {
        const equipment = {
            assetId: eventId,
            componentIds: [],
            pickupZoneId: null,
            destinationZoneId: eventId,
            instruction: '예비 마이크 이동',
        };
        const safety = {
            zoneId: eventId,
            observationId: null,
            estimatedCount: null,
            capturedAt: null,
            freshness: 'UNKNOWN',
            qualityReasons: [],
            instructions: ['통로 확보'],
        };
        const parsed = parse<Task>('V9Task', { ...task, equipment: [equipment], safety });
        expect(parsed.equipment).toHaveLength(1);
        const result = currentDraft(parse<Plan>('PlanView', plan), [parsed]);
        expect(result.tasks[0]).not.toHaveProperty('equipment');
        expect(result.tasks[0]).not.toHaveProperty('safety');
        // 항목 형식이 다르거나 계약에 없는 필드는 거절한다
        expect(() =>
            parse<Task>('V9Task', { ...task, equipment: [{ assetId: eventId }] }),
        ).toThrow();
        expect(() =>
            parse<Task>('V9Task', { ...task, safety: { ...safety, freshness: 'OK' } }),
        ).toThrow();
        expect(() => parse<Task>('V9Task', { ...task, extra: 1 })).toThrow();
        // 목록·시작·완료의 Task에는 이 필드가 없다
        expect(() => parse<Task>('Task', { ...task, safety: null })).toThrow();
    });
    it('상속 속성과 같은 이름의 미정의 필드도 거절한다', () => {
        expect(() =>
            validateDraft({ ...candidate, toString: '내부 값' } as unknown as Draft, '1', true),
        ).toThrow();
    });
    it.each([202, 204])(
        '초안 저장의 예상 밖 %s는 결과 확인 전 다시 보내지 않는다',
        async (status) => {
            const { client, fetcher } = setup();
            fetcher.mockResolvedValueOnce(
                status === 204
                    ? new Response(null, { status })
                    : json({ jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }, status),
            );
            await expect(client.save(draft(), '1')).rejects.toThrow();
            expect(client.pending('save')).toBe(true);
            await expect(client.save(draft(), '1')).rejects.toThrow();
            expect(fetcher).toHaveBeenCalledTimes(1);
        },
    );
    it('업로드 지문이 바뀌면 같은 의도의 다른 파일을 전송하지 않는다', async () => {
        const { client, scope, fetcher } = setup();
        const form = new FormData();
        form.append('files', new Blob(['첫 내용']), 'a.txt');
        fetcher.mockRejectedValueOnce(new TypeError('lost'));
        const spec = {
            method: 'POST' as const,
            path: `/events/${eventId}/documents`,
            operation: 'files',
            intentId: 'upload-intent',
            recovery: 'same-key' as const,
            form: { data: form, fingerprint: '내용 지문1' },
        };
        await client.session.api.command(scope, spec);
        await expect(
            client.session.api.command(scope, {
                ...spec,
                form: { data: form, fingerprint: '내용 지문2' },
            }),
        ).rejects.toThrow();
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('AI 작업 접수가 503이면 결과 불명으로 남기지 않고 다른 요청을 막지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher
            .mockResolvedValueOnce(
                json(
                    {
                        code: 'SERVICE_UNAVAILABLE',
                        message: '문서 추출을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
                        requestId: jobId,
                    },
                    503,
                ),
            )
            .mockResolvedValueOnce(new Response(null, { status: 204 }));
        await expect(client.generate('extract', '1')).rejects.toBeInstanceOf(AiRequestUnavailable);
        expect(client.pending('extract')).toBe(false);
        // 문서 정리(047)는 본문 없는 204로 끝나고 성공으로 본다
        await expect(client.deleteDocuments('1')).resolves.toBeUndefined();
        expect(fetcher).toHaveBeenCalledTimes(2);
        expect(fetcher.mock.calls[1][1]?.method).toBe('DELETE');
    });
    it('결과를 모르는 이전 요청이 있으면 막은 요청이 그 이름을 알린다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockRejectedValueOnce(new TypeError('lost'));
        await expect(client.input({ 조건: '확인 값' }, '1')).rejects.toThrow();
        const blocked = await client.deleteDocuments('1').catch((e: unknown) => e);
        expect(blocked).toBeInstanceOf(CommandNeedsReview);
        expect((blocked as CommandNeedsReview).blocking).toBe('input');
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('미확인 입력 저장이 있을 때 새 생성을 보내지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockRejectedValueOnce(new TypeError('lost'));
        await expect(client.input({ 조건: '확인 값' }, '1')).rejects.toThrow();
        await expect(client.generate('generate', '2')).rejects.toThrow();
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('다른 필드를 편집해도 그룹 안내/구역 설명/수행 단계/공통 안내가 유지된다', () => {
        const value = draft();
        value.tasks[0].title = '변경한 업무';
        validateDraft(value, '1');
        expect(value.workGroups[0].instructions).toBe(plan.workGroups[0].instructions);
        expect(value.zones[0].description).toBe(plan.zones[0].description);
        expect(value.tasks[0].steps).toEqual(task.steps);
        expect(value.instructions).toEqual(plan.instructions);
    });
    it.each(['workGroups', 'zones', 'programs', 'tasks', 'instructions'] as const)(
        '전체 교체의 %s 배열 생략을 거절한다',
        (key) => {
            const value = { ...candidate } as Record<string, unknown>;
            delete value[key];
            expect(() => validateDraft(value as unknown as Draft, '1')).toThrow();
        },
    );
    it('내부 결과6와 수행 상태/담당자를 공개 후보로 받지 않는다', () => {
        expect(() =>
            validateDraft({ ...candidate, equipmentDraft: {} } as unknown as Draft, '1', true),
        ).toThrow();
        const value = structuredClone(candidate) as unknown as Draft;
        Object.assign(value.tasks[0], { assigneeId: memberId });
        expect(() => validateDraft(value, '1', true)).toThrow();
    });
    it('범위를 넘는 배열과 단계/이름/구역 설명/회차를 거절한다', () => {
        const value = draft();
        value.zones[0].description = '가'.repeat(1001);
        expect(() => validateDraft(value, '1')).toThrow();
        value.zones[0].description = null;
        value.tasks[0].steps = Array(7).fill('점검');
        expect(() => validateDraft(value, '1')).toThrow();
        expect(() => validateDraft(draft(), '01')).toThrow();
    });
    it('구역 설명 길이는 UTF-16 길이가 아닌 code point로 센다', () => {
        const value = draft();
        value.zones[0].description = '😀'.repeat(1000);
        expect(() => validateDraft(value, '1')).not.toThrow();
        value.zones[0].description = '😀'.repeat(1001);
        expect(() => validateDraft(value, '1')).toThrow();
    });
    it('중복 임시 ID/끊어진 참조/동일 체크 key와 역전된 시각을 거절한다', () => {
        const value = structuredClone(candidate) as unknown as Draft;
        value.zones[0].tempId = value.workGroups[0].tempId;
        expect(() => validateDraft(value, '1')).toThrow();
        const v = draft();
        v.tasks[0].zoneId = eventId;
        expect(() => validateDraft(v, '1')).toThrow();
        const v2 = draft();
        v2.tasks[0].checklist.push(v2.tasks[0].checklist[0]);
        expect(() => validateDraft(v2, '1')).toThrow();
        const v3 = draft();
        v3.tasks[0].endAt = v3.tasks[0].startAt;
        expect(() => validateDraft(v3, '1')).toThrow();
    });
    it('보이지 않는 그룹 안내나 불일치하는 업무 상세를 빈 값으로 저장하지 않는다', () => {
        expect(() =>
            currentDraft(
                {
                    ...parse<Plan>('PlanView', plan),
                    workGroups: [{ ...plan.workGroups[0], instructions: null }],
                },
                [parse<Task>('Task', task)],
            ),
        ).toThrow();
        expect(() =>
            currentDraft(parse<Plan>('PlanView', plan), [
                { ...parse<Task>('Task', task), zoneId: eventId },
            ]),
        ).toThrow();
    });
    it('추출 PARTIAL과 실패/진행 본문을 구별한다', () => {
        expect(parse<Extraction>('PublicJobExtractEventInput', extracted).state).toBe('PARTIAL');
        expect(() =>
            parse('PublicJobExtractEventInput', { ...extracted, state: 'RUNNING' }),
        ).toThrow();
        expect(() => parse('PublicJobExtractEventInput', { ...extracted, attempt: 1 })).toThrow();
    });
    it('현재 계획 조회에서 event revision과 권한을 대조하고 상세를 보존한다', async () => {
        const { client, fetcher } = setup();
        fetcher
            .mockResolvedValueOnce(json(event))
            .mockResolvedValueOnce(json(plan))
            .mockResolvedValueOnce(json(task));
        expect((await client.read()).draft).toEqual(draft());
        const s = setup();
        s.fetcher
            .mockResolvedValueOnce(json(event))
            .mockResolvedValueOnce(json({ ...plan, eventRevision: '2' }));
        await expect(s.client.read()).rejects.toThrow();
    });
    it('계획 접수202와 READY 명시 조회는 현재 초안을 쓰지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher
            .mockResolvedValueOnce(json({ jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }, 202))
            .mockResolvedValueOnce(json(ready));
        expect(await client.generate('generate', '1')).toBe(jobId);
        expect(await client.candidate(jobId, '1')).toEqual({
            plan: candidate,
            layout: null,
            jobId,
        });
        expect(fetcher.mock.calls.map(([, init]) => init?.method)).toEqual(['POST', 'GET']);
    });
    it('생성 응답 유실 뒤 원래 revision/같은 키만 재전송한다', async () => {
        const { client, fetcher } = setup();
        fetcher
            .mockRejectedValueOnce(new TypeError('lost'))
            .mockResolvedValueOnce(json({ jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }, 202));
        await expect(client.generate('generate', '1')).rejects.toBeInstanceOf(CommandNeedsReview);
        await client.generate('generate', '99');
        const a = fetcher.mock.calls[0][1]!,
            b = fetcher.mock.calls[1][1]!;
        expect(a.body).toBe(b.body);
        expect(new Headers(a.headers).get('Idempotency-Key')).toBe(
            new Headers(b.headers).get('Idempotency-Key'),
        );
    });
    it('결과 불명은 새 키나 생성으로 우회하지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockResolvedValueOnce(
            json(
                { code: 'IDEMPOTENCY_OUTCOME_UNKNOWN', message: 'unknown', requestId: 'test' },
                409,
            ),
        );
        await expect(client.generate('generate', '1')).rejects.toThrow();
        await expect(client.generate('generate', '2')).rejects.toThrow();
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('누락된 신규 항목 매핑을 저장 성공으로 사용하지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockResolvedValueOnce(json({ plan, idMappings: [] }));
        await expect(client.save(candidate as unknown as Draft, '1')).rejects.toThrow();
        expect(client.pending('save')).toBe(true);
        await expect(client.save(candidate as unknown as Draft, '1')).rejects.toThrow();
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('본인 업무만 명령을 보내고 업무 응답의 대상도 검사한다', async () => {
        const { client, fetcher } = setup();
        await expect(
            client.perform({ ...parse<Task>('Task', task), assigneeId: eventId }, 'start', '1'),
        ).rejects.toThrow();
        expect(fetcher).not.toHaveBeenCalled();
        fetcher.mockResolvedValueOnce(
            json({ task: { ...task, status: 'IN_PROGRESS' }, eventRevision: '2' }),
        );
        const result = await client.perform(parse<Task>('Task', task), 'start', '1');
        expect(result.task.status).toBe('IN_PROGRESS');
        expect(new Headers(fetcher.mock.calls[0][1]?.headers).has('Idempotency-Key')).toBe(false);
    });
    it('파일의 내용/순서/이름/MIME으로 지문을 만들고 multipart를 전송한다', async () => {
        const { client, fetcher } = setup();
        const file = new File(['내용'], '공연.md', { type: 'text/markdown' });
        fetcher.mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce(
            json({
                eventRevision: '2',
                files: [
                    {
                        index: 0,
                        status: 'FAILED',
                        error: { code: 'LIMIT', message: '제한', requestId: 'test' },
                    },
                ],
            }),
        );
        await expect(client.upload([file])).rejects.toThrow();
        await client.upload([new File(['다른 내용'], '다른.md')]);
        const first = fetcher.mock.calls[0][1]!,
            second = fetcher.mock.calls[1][1]!;
        expect(first.body).toBeInstanceOf(FormData);
        expect(second.body).toBe(first.body);
        expect(new Headers(first.headers).has('Content-Type')).toBe(false);
        expect(new Headers(first.headers).get('Idempotency-Key')).toBe(
            new Headers(second.headers).get('Idempotency-Key'),
        );
    });
    it('파일 형식/수/합계 상한은 전송 전에 검사한다', async () => {
        const { client, fetcher } = setup();
        await expect(client.upload([new File(['x'], 'x.exe')])).rejects.toThrow();
        await expect(
            client.upload(Array.from({ length: 4 }, () => new File(['x'], 'x.txt'))),
        ).rejects.toThrow();
        expect(fetcher).not.toHaveBeenCalled();
    });
    it('참여 종료 뒤 늦은 후보 응답을 전달하지 않는다', async () => {
        const { client, fetcher, scopes } = setup();
        fetcher.mockResolvedValueOnce(json({ jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }, 202));
        await client.generate('generate', '1');
        let resolve!: (value: Response) => void;
        fetcher.mockReturnValueOnce(
            new Promise((r) => {
                resolve = r;
            }),
        );
        const query = client.candidate(jobId, '1');
        const checked = expect(query).rejects.toBeInstanceOf(ScopeDisposedError);
        scopes.leaveParticipation(eventId);
        resolve(json(ready));
        await checked;
        expect(client.jobs.size).toBe(0);
    });
    it('체크리스트 수정 본문에 담당자/수행 시각/숫자 revision을 넣지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockResolvedValueOnce(json({ task, eventRevision: '2' }));
        await client.perform(parse<Task>('Task', task), 'checklist', '1', [
            { key: 'power', checked: true },
        ]);
        expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({
            expectedEventRevision: '1',
            checks: [{ key: 'power', checked: true }],
        });
    });
    it('행사 생성/발행/최초 배정 적용의 제외 경로를 호출하지 않는다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockResolvedValueOnce(json({ plan, idMappings: [] }));
        await client.save(draft(), '1');
        expect(fetcher.mock.calls[0][0]).toBe(`/api/v1/events/${eventId}/draft-plan`);
        expect(
            fetcher.mock.calls.some(([url]) =>
                /publish|initial-assignment|\/events$/.test(String(url)),
            ),
        ).toBe(false);
    });
    it('업무 상세는 요청한 ID와 같아야 한다', async () => {
        const { client, fetcher } = setup();
        fetcher.mockResolvedValueOnce(json({ ...task, id: eventId }));
        await expect(client.task(taskId)).rejects.toThrow();
    });
});
