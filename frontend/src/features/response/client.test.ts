import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { Responder } from './client';
import {
    ack,
    ackId,
    authorId,
    changeSetFixture,
    eventId,
    issue,
    issueId,
    report,
    reportId,
    zoneId,
} from './test-data';

const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const failure = (status: number, code: string, details?: unknown) =>
    json(status, { code, message: '안내', requestId: 'req-1', ...(details ? { details } : {}) });
const [draft, ready, applied] = changeSetFixture.responses;

describe('보고/변경 요청', () => {
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let scope: ParticipationScope;
    let counter = 0;
    const responder = () =>
        new Responder(
            {
                api: createApi({ base: '/api/v1', transport, scopes }),
                scopes,
            } as unknown as IdentitySession,
            scope,
        );
    const call = (n: number) => {
        const [url, init] = transport.mock.calls[n];
        return {
            url,
            method: init?.method,
            key: new Headers(init?.headers).get('Idempotency-Key'),
            body: init?.body ? (JSON.parse(String(init.body)) as unknown) : undefined,
        };
    };

    beforeEach(() => {
        counter = 0;
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(
            new QueryClient(),
            new IntentKeyStore({ createKey: () => `key-${++counter}` }),
        );
        scopes.setAccount('A');
        scope = scopes.setParticipation({
            eventId,
            memberId: authorId,
            participationRevision: '1',
        }).scope!;
    });
    afterEach(() => scopes.dispose());

    it('최초 보고는 구역과 원문만 보내고 회차는 보내지 않는다', async () => {
        transport.mockResolvedValueOnce(
            json(201, { issue: issue(), report: report(), analysisJobId: null }),
        );
        const result = await responder().createIssue({ zoneId, text: '마이크 끊김' });
        expect(result).toMatchObject({ kind: 'done', data: { issue: { id: issueId } } });
        expect(call(0)).toEqual({
            url: `/api/v1/events/${eventId}/issues`,
            method: 'POST',
            key: 'key-1',
            body: {
                zoneId,
                report: {
                    kind: 'INITIAL',
                    text: '마이크 끊김',
                    quickTemplateKey: null,
                    audioId: null,
                },
            },
        });
    });

    it('응답을 받지 못한 보고는 같은 키/같은 원문으로만 다시 보낸다', async () => {
        const r = responder();
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect((await r.createIssue({ zoneId, text: '처음 원문' })).kind).toBe('retry');
        expect(r.pending('createIssue', 'new')).toBe(true);
        transport.mockResolvedValueOnce(
            json(201, { issue: issue(), report: report(), analysisJobId: null }),
        );
        // 사용자가 다시 보내기를 눌렀다. 바뀐 입력은 같은 의도에 끼워 넣지 않는다.
        expect((await r.createIssue({ zoneId, text: '바뀐 원문' })).kind).toBe('done');
        expect(call(1).key).toBe(call(0).key);
        expect(call(1).body).toEqual(call(0).body);
        expect(r.pending('createIssue', 'new')).toBe(false);
    });

    it('상황 정정은 결과를 모르는 사이 값을 바꾸면 바뀐 값을 새 키로 보낸다', async () => {
        const r = responder();
        const first = {
            primaryType: 'EQUIPMENT_ANOMALY' as const,
            relatedTypes: [],
            reason: '마이크 고장',
        };
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect((await r.correctSituation(issueId, first, '3')).kind).toBe('retry');
        expect(r.pending('correctSituation', issueId)).toBe(true);
        const receipt = { id: issueId, revision: '1', eventRevision: '4' };
        // 같은 값이면 같은 키로 다시 보낸다
        transport.mockRejectedValueOnce(new TypeError('network'));
        await r.correctSituation(issueId, first, '3');
        expect(call(1).key).toBe(call(0).key);
        // 값을 바꾸면 새 키·새 본문으로 보낸다
        transport.mockResolvedValueOnce(json(200, receipt));
        const changed = { ...first, primaryType: 'STAFF_GAP' as const, reason: '담당 이탈' };
        expect((await r.correctSituation(issueId, changed, '3')).kind).toBe('done');
        expect(call(2).key).not.toBe(call(0).key);
        expect(call(2).body).toEqual({ expectedEventRevision: '3', ...changed });
        expect(r.pending('correctSituation', issueId)).toBe(false);
    });

    it('결과 불명이면 같은 키도 다시 보내지 않고 확인으로 넘긴다', async () => {
        const r = responder();
        transport.mockResolvedValueOnce(failure(409, 'IDEMPOTENCY_OUTCOME_UNKNOWN'));
        expect(await r.addReport(issueId, 'FOLLOWUP', '추가 원문')).toEqual({ kind: 'check' });
        expect(await r.addReport(issueId, 'FOLLOWUP', '추가 원문')).toEqual({ kind: 'check' });
        expect(transport).toHaveBeenCalledTimes(1);
        // 목록에서 결과를 확인한 뒤 새로 쓰겠다고 명시하면 새 키를 쓴다.
        r.forget('addReport', `${issueId}:FOLLOWUP`);
        transport.mockResolvedValueOnce(
            json(201, {
                report: report({ id: reportId, kind: 'FOLLOWUP' }),
                analysisJobId: null,
                eventRevision: '4',
            }),
        );
        expect((await r.addReport(issueId, 'FOLLOWUP', '새 원문')).kind).toBe('done');
        expect(call(1).key).not.toBe(call(0).key);
    });

    it('분석/변경 생성은 Job 접수로 돌려준다', async () => {
        const jobId = '0a1b2c3d-4e5f-5061-8273-8495a6b7c8d9';
        transport.mockResolvedValueOnce(
            json(202, { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }),
        );
        expect(await responder().analyze(reportId, '3')).toEqual({ kind: 'job', jobId });
        expect(call(0).body).toEqual({ expectedEventRevision: '3' });
        transport.mockResolvedValueOnce(
            json(202, { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }),
        );
        await responder().generateChange({
            issueId,
            instruction: '예비 인원 배치',
            reportIds: [reportId],
            expectedEventRevision: '3',
        });
        expect(call(1).body).toEqual({
            issueId,
            instruction: '예비 인원 배치',
            reportIds: [reportId],
            expectedEventRevision: '3',
        });
    });

    it('미리보기는 키 없이 보내고, 적용은 미리보기 해시와 두 revision을 같은 키로 보낸다', async () => {
        const r = responder();
        transport.mockResolvedValueOnce(json(200, ready));
        const preview = await r.preview(draft.id, draft.revision, draft.eventRevision);
        expect(preview.kind).toBe('done');
        expect(call(0).key).toBeNull();
        transport.mockRejectedValueOnce(new TypeError('network'));
        const input = {
            expectedChangeSetRevision: ready.revision,
            previewHash: ready.previewHash!,
            expectedEventRevision: ready.eventRevision,
        };
        expect((await r.apply(ready.id, input)).kind).toBe('retry');
        transport.mockResolvedValueOnce(json(200, applied));
        expect(await r.apply(ready.id, input)).toMatchObject({
            kind: 'done',
            data: { status: 'APPLIED' },
        });
        expect(call(2).key).toBe(call(1).key);
        expect(call(2).body).toEqual(input);
    });

    it('적용 응답이 APPLIED가 아니면 성공으로 보지 않고 같은 키로 확인한다', async () => {
        const r = responder();
        transport.mockResolvedValueOnce(json(200, ready));
        const result = await r.apply(ready.id, {
            expectedChangeSetRevision: ready.revision,
            previewHash: ready.previewHash!,
            expectedEventRevision: ready.eventRevision,
        });
        expect(result.kind).toBe('retry');
        expect(r.pending('apply', ready.id)).toBe(true);
    });

    it('충돌은 서버가 알려 준 대상을 함께 돌려준다', async () => {
        transport.mockResolvedValueOnce(
            failure(409, 'VERSION_CONFLICT', { target: 'reservation' }),
        );
        expect(
            await responder().apply(ready.id, {
                expectedChangeSetRevision: ready.revision,
                previewHash: ready.previewHash!,
                expectedEventRevision: ready.eventRevision,
            }),
        ).toEqual({ kind: 'conflict', target: 'reservation' });
        transport.mockResolvedValueOnce(failure(409, 'VERSION_CONFLICT'));
        expect(await responder().preview(draft.id, draft.revision, '9')).toEqual({
            kind: 'conflict',
            target: null,
        });
    });

    it('예약 오류(장비 사용 불가 422/계산 만료 410)는 확정 거절로 코드를 남긴다', async () => {
        transport.mockResolvedValueOnce(failure(422, 'EQUIPMENT_UNAVAILABLE'));
        const r = responder();
        const input = {
            expectedChangeSetRevision: ready.revision,
            previewHash: ready.previewHash!,
            expectedEventRevision: ready.eventRevision,
        };
        expect(await r.apply(ready.id, input)).toMatchObject({
            kind: 'rejected',
            failure: { code: 'EQUIPMENT_UNAVAILABLE' },
        });
        expect(r.pending('apply', ready.id)).toBe(false);
        transport.mockResolvedValueOnce(failure(410, 'RECOVERY_RESULT_EXPIRED'));
        expect(await r.preview(draft.id, draft.revision, draft.eventRevision)).toMatchObject({
            kind: 'rejected',
            failure: { code: 'RECOVERY_RESULT_EXPIRED' },
        });
    });

    it('비멱등 명령(폐기/확인/상태 변경)은 응답이 없으면 다시 보내지 않고 확인으로 넘긴다', async () => {
        const r = responder();
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect(await r.discard(draft.id, draft.revision)).toEqual({ kind: 'check' });
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect(await r.ack(ackId, 'hash-1')).toEqual({ kind: 'check' });
        transport.mockResolvedValueOnce(json(200, issue({ status: 'CLOSED' })));
        expect(await r.patchIssue(issueId, { status: 'CLOSED' }, '3')).toMatchObject({
            kind: 'done',
            data: { status: 'CLOSED' },
        });
        expect(transport).toHaveBeenCalledTimes(3);
        expect([call(0).key, call(1).key, call(2).key]).toEqual([null, null, null]);
        expect(call(1).body).toEqual({ targetsHash: 'hash-1' });
        expect(call(2).body).toEqual({ status: 'CLOSED', expectedEventRevision: '3' });
    });

    it('편집(068)은 키 없이 바뀐 항목과 두 revision만 보내고, 응답이 없으면 확인으로 넘긴다', async () => {
        const r = responder();
        const edited = { ...structuredClone(draft), revision: '2', rationale: '새 근거' };
        edited.evidenceSnapshot = { ...edited.evidenceSnapshot!, changeSetRevision: '2' };
        transport.mockResolvedValueOnce(json(200, edited));
        expect(await r.edit(draft.id, { rationale: '새 근거' }, '1', '1')).toMatchObject({
            kind: 'done',
            data: { revision: '2', rationale: '새 근거' },
        });
        expect(call(0)).toMatchObject({
            method: 'PATCH',
            key: null,
            body: {
                rationale: '새 근거',
                expectedChangeSetRevision: '1',
                expectedEventRevision: '1',
            },
        });
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect(await r.edit(draft.id, { rationale: '또 수정' }, '2', '1')).toEqual({
            kind: 'check',
        });
        expect(transport).toHaveBeenCalledTimes(2);
        await expect(r.edit(draft.id, { operations: [] }, '2', '1')).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(2);
    });

    it('과거 revision 근거(R2)는 요청한 변경안/revision의 기록만 받는다', async () => {
        const r = responder();
        const snap = draft.evidenceSnapshot!;
        transport.mockResolvedValueOnce(json(200, snap));
        await expect(r.evidence(draft.id, '1')).resolves.toMatchObject({ changeSetRevision: '1' });
        expect(String(call(0).url)).toContain(`/change-sets/${draft.id}/evidence/1`);
        transport.mockResolvedValueOnce(json(200, snap));
        await expect(r.evidence(draft.id, '2')).rejects.toThrow();
        transport.mockResolvedValueOnce(failure(404, 'NOT_FOUND'));
        await expect(r.evidence(draft.id, '3')).rejects.toMatchObject({ status: 404 });
        await expect(r.evidence(draft.id, '../1')).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(3);
    });

    it('변경안의 근거가 다른 변경안이거나 나중 revision이면 쓰지 않는다', async () => {
        const r = responder();
        const wrong = structuredClone(draft);
        wrong.evidenceSnapshot = { ...wrong.evidenceSnapshot!, changeSetRevision: '9' };
        transport.mockResolvedValueOnce(json(200, wrong));
        await expect(r.changeSet(draft.id)).rejects.toThrow();
    });

    it('명시 확인 결과를 읽는다', async () => {
        transport.mockResolvedValueOnce(
            json(200, ack({ state: 'ACKNOWLEDGED', acknowledgedAt: '2026-11-18T10:06:00Z' })),
        );
        expect(await responder().ack(ackId, 'hash-1')).toMatchObject({
            kind: 'done',
            data: { state: 'ACKNOWLEDGED' },
        });
    });

    it('참여 회차가 끝나면 남은 의도를 버린다', async () => {
        const r = responder();
        transport.mockRejectedValueOnce(new TypeError('network'));
        await r.createIssue({ zoneId, text: '원문' });
        expect(r.pending('createIssue', 'new')).toBe(true);
        scopes.leaveParticipation(eventId);
        expect(r.pending('createIssue', 'new')).toBe(false);
    });

    describe('안전 인력 지원안(#361)', () => {
        const ids = {
            observationId: 'a1111111-1111-4111-8111-111111111111',
            sourceZoneId: 'a2222222-2222-4222-8222-222222222222',
            targetZoneId: 'a3333333-3333-4333-8333-333333333333',
            workGroupId: 'a4444444-4444-4444-8444-444444444444',
            startAt: '2026-11-24T09:00:00Z',
            endAt: '2026-11-24T09:30:00Z',
        };
        type Picked = {
            memberId: string;
            participationRevision: string;
            sourceTaskId: string;
        };
        const m1: Picked = {
            memberId: 'b1111111-1111-4111-8111-111111111111',
            participationRevision: '2',
            sourceTaskId: 'c2222222-2222-4222-8222-222222222222',
        };
        const m2: Picked = {
            memberId: 'b2222222-2222-4222-8222-222222222222',
            participationRevision: '5',
            sourceTaskId: 'c1111111-1111-4111-8111-111111111111',
        };
        const support = (candidates: Picked[] = [m1], expectedEventRevision = '7') => ({
            selection: { ...ids, candidates },
            expectedEventRevision,
        });

        it('연산 대신 supportSelection을 066으로 보내고 ChangeSetView를 받는다', async () => {
            transport.mockResolvedValueOnce(json(201, draft));
            const result = await responder().createSupport(support([m1, m2]));
            expect(result).toMatchObject({ kind: 'done', data: { id: draft.id } });
            expect(call(0)).toEqual({
                url: `/api/v1/events/${eventId}/change-sets`,
                method: 'POST',
                key: 'key-1',
                body: {
                    mode: 'MANUAL',
                    expectedEventRevision: '7',
                    supportSelection: { ...ids, candidates: [m1, m2] },
                },
            });
            // 최상위에는 mode·expectedEventRevision·supportSelection만 보낸다
            expect(Object.keys(call(0).body as object).sort()).toEqual([
                'expectedEventRevision',
                'mode',
                'supportSelection',
            ]);
        });

        it('같은 선택은 같은 키로 다시 보내고, 선택을 바꾸면 새 키를 쓴다', async () => {
            const r = responder();
            transport.mockRejectedValueOnce(new TypeError('network'));
            expect((await r.createSupport(support([m1]))).kind).toBe('retry');
            transport.mockResolvedValueOnce(json(201, draft));
            expect((await r.createSupport(support([m1]))).kind).toBe('done');
            expect(call(1).key).toBe(call(0).key);
            // 응답을 못 받은 채 선택을 바꾸면 남은 의도(이전 선택)를 쓰지 않고 새 키·새 선택으로 보낸다
            transport.mockRejectedValueOnce(new TypeError('network'));
            expect((await r.createSupport(support([m2]))).kind).toBe('retry');
            transport.mockResolvedValueOnce(json(201, draft));
            await r.createSupport(support([m1, m2]));
            expect(call(3).key).not.toBe(call(2).key);
            expect(call(3).body).toMatchObject({ supportSelection: { candidates: [m1, m2] } });
        });

        it('같은 선택이라도 기대 revision이 바뀌면 새 키를 쓴다', async () => {
            const r = responder();
            transport.mockRejectedValueOnce(new TypeError('network'));
            expect((await r.createSupport(support([m1], '7'))).kind).toBe('retry');
            transport.mockResolvedValueOnce(json(201, draft));
            await r.createSupport(support([m1], '8'));
            expect(call(1).key).not.toBe(call(0).key);
            expect(call(1).body).toMatchObject({ expectedEventRevision: '8' });
        });

        it('409 INVALID_STATE는 확정 거절로 details.reason을 넘긴다', async () => {
            const r = responder();
            transport.mockResolvedValueOnce(
                failure(409, 'INVALID_STATE', { reason: 'SUPPORT_MINIMUM_CREW' }),
            );
            expect(await r.createSupport(support())).toMatchObject({
                kind: 'rejected',
                failure: { code: 'INVALID_STATE', reason: 'SUPPORT_MINIMUM_CREW' },
            });
            transport.mockResolvedValueOnce(
                failure(409, 'INVALID_STATE', { reason: 'SUPPORT_CANDIDATE_CHANGED' }),
            );
            expect(await r.createSupport(support([m2]))).toMatchObject({
                kind: 'rejected',
                failure: { code: 'INVALID_STATE', reason: 'SUPPORT_CANDIDATE_CHANGED' },
            });
        });
    });
});
