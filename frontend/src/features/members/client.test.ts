import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    draft,
    groups,
    invite,
    lightGroupId,
    member,
    members,
    workerId,
} from '../../../tests/fixtures/members-data';
import { eventId, groupId, memberId } from '../../../tests/fixtures/workflow-data';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { DraftDesk } from '../unconfirmed-draft/client';
import { danglingAssetRefs, parseUnconfirmedDraft } from '../unconfirmed-draft/contract';
import { MemberDesk } from './client';
import { parseGroupPage, parseInvite, parseMemberPage } from './contract';

const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const failure = (status: number, code: string) =>
    json(status, { code, message: '안내', requestId: 'req-1' });

describe('참여 인원/미확정 초안 요청', () => {
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let scope: ParticipationScope;
    const session = () =>
        ({
            api: createApi({ base: '/api/v1', transport, scopes }),
            scopes,
        }) as unknown as IdentitySession;
    const call = (n: number) => {
        const [url, init] = transport.mock.calls[n];
        return {
            url: String(url),
            method: init?.method,
            key: new Headers(init?.headers).get('Idempotency-Key'),
            body: init?.body ? (JSON.parse(String(init.body)) as unknown) : undefined,
        };
    };

    beforeEach(() => {
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
        scope = scopes.setParticipation({ eventId, memberId, participationRevision: '1' }).scope!;
    });
    afterEach(() => scopes.dispose());

    it('초대 링크는 현재 공연의 http(s) 주소만 받는다', () => {
        expect(parseInvite(invite, eventId).inviteUrl).toMatch(/^https:/);
        expect(() =>
            parseInvite({ ...invite, inviteUrl: 'javascript:alert(1)' }, eventId),
        ).toThrow();
        expect(() =>
            parseInvite({ ...invite, eventId: '10000000-0000-4000-8000-0000000000ff' }, eventId),
        ).toThrow();
    });

    it('초대 재발급(033)은 키 없이 기대 revision만 보내고, 응답이 없으면 다시 보내지 않는다(#353)', async () => {
        const desk = new MemberDesk(session(), scope);
        transport.mockResolvedValueOnce(json(200, { ...invite, revision: '8' }));
        const done = await desk.rotateInvite('7');
        expect(done.kind).toBe('done');
        expect(call(0)).toEqual({
            url: `/api/v1/events/${eventId}/invite/rotate`,
            method: 'POST',
            key: null,
            body: { expectedEventRevision: '7' },
        });
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect((await desk.rotateInvite('8')).kind).toBe('check');
        transport.mockResolvedValueOnce(json(200, { ...invite, eventId: lightGroupId }));
        expect((await desk.rotateInvite('8')).kind).toBe('check');
        expect(transport).toHaveBeenCalledTimes(3);
    });

    it('같은 그룹이 두 번 온 목록은 쓰지 않는다', () => {
        // 브라우저 시험 자료도 계약을 지킨다
        expect(parseGroupPage(structuredClone(groups)).items).toHaveLength(2);
        expect(parseMemberPage(structuredClone(members)).items).toHaveLength(3);
        expect(() =>
            parseGroupPage({ ...groups, items: [groups.items[0], groups.items[0]] }),
        ).toThrow();
    });

    it('인원 목록은 cursor를 따라가고 같은 cursor가 되돌아오면 잘림으로 알린다', async () => {
        transport
            .mockResolvedValueOnce(json(200, { items: [member()], nextCursor: 'c1' }))
            .mockResolvedValueOnce(json(200, { items: [], nextCursor: 'c1' }));
        const result = await new MemberDesk(session(), scope).members();
        expect(result).toMatchObject({ truncated: true });
        expect(result.items).toHaveLength(1);
        expect(call(1).url).toContain('cursor=c1');
    });

    it('인원 수정(038)은 키 없이 바꾼 항목만 보내고, 응답이 없으면 확인으로 넘긴다', async () => {
        const desk = new MemberDesk(session(), scope);
        transport.mockResolvedValueOnce(
            json(200, { member: member({ isReserve: true }), eventRevision: '5' }),
        );
        expect(await desk.update(workerId, { isReserve: true }, '4')).toMatchObject({
            kind: 'done',
            data: { eventRevision: '5', member: { isReserve: true } },
        });
        expect(call(0)).toMatchObject({
            method: 'PATCH',
            key: null,
            body: { isReserve: true, expectedEventRevision: '4' },
        });
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect(await desk.update(workerId, { isReserve: false }, '5')).toEqual({ kind: 'check' });
        transport.mockResolvedValueOnce(failure(409, 'VERSION_CONFLICT'));
        expect((await desk.update(workerId, { isReserve: false }, '5')).kind).toBe('conflict');
        expect(transport).toHaveBeenCalledTimes(3);
    });

    it('다른 인원의 수정 응답은 쓰지 않는다', async () => {
        transport.mockResolvedValueOnce(
            json(200, { member: member({ memberId }), eventRevision: '5' }),
        );
        expect((await new MemberDesk(session(), scope).update(workerId, {}, '4')).kind).toBe(
            'check',
        );
    });

    const transferred = (patch: Record<string, unknown> = {}) => ({
        previousChief: {
            memberId,
            participationRevision: '1',
            active: true,
            eventRole: 'WORKER',
            workGroupId: lightGroupId,
        },
        currentChief: {
            memberId: workerId,
            participationRevision: '2',
            active: true,
            eventRole: 'CHIEF',
            workGroupId: groupId,
        },
        eventRevision: '6',
        ...patch,
    });

    it('총괄 인계(042)는 키 없이 후임/내 그룹/기대 revision을 보내고 역할 전환을 확인한다', async () => {
        const desk = new MemberDesk(session(), scope);
        transport.mockResolvedValueOnce(json(200, transferred()));
        expect(await desk.transferChief(memberId, workerId, lightGroupId, '5')).toMatchObject({
            kind: 'done',
            data: { eventRevision: '6', currentChief: { memberId: workerId } },
        });
        expect(call(0)).toMatchObject({
            method: 'POST',
            key: null,
            body: {
                successorMemberId: workerId,
                previousChiefWorkGroupId: lightGroupId,
                expectedEventRevision: '5',
            },
        });
        expect(call(0).url).toContain(`/events/${eventId}/chief-transfer`);
    });

    it('총괄 인계 응답이 요청과 다르면 쓰지 않고, 충돌/거절/결과 불명을 구분하며 다시 보내지 않는다', async () => {
        const desk = new MemberDesk(session(), scope);
        // 역할이 바뀌지 않은 응답(계약 예시와 같은 모양)
        transport.mockResolvedValueOnce(
            json(
                200,
                transferred({
                    previousChief: { ...transferred().previousChief, eventRole: 'CHIEF' },
                }),
            ),
        );
        expect((await desk.transferChief(memberId, workerId, lightGroupId, '5')).kind).toBe(
            'check',
        );
        transport.mockResolvedValueOnce(failure(409, 'VERSION_CONFLICT'));
        expect((await desk.transferChief(memberId, workerId, lightGroupId, '5')).kind).toBe(
            'conflict',
        );
        transport.mockResolvedValueOnce(failure(422, 'INVALID_REFERENCE'));
        expect(await desk.transferChief(memberId, workerId, lightGroupId, '5')).toMatchObject({
            kind: 'rejected',
            failure: { code: 'INVALID_REFERENCE' },
        });
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect((await desk.transferChief(memberId, workerId, lightGroupId, '5')).kind).toBe(
            'check',
        );
        expect(transport).toHaveBeenCalledTimes(4);
    });

    it('미확정 초안이 없으면(404) 빈 초안으로 보고, 저장은 Idempotency-Key와 기대 revision/전체 본문을 보낸다', async () => {
        const desk = new DraftDesk(session(), scope);
        transport.mockResolvedValueOnce(failure(404, 'NOT_FOUND'));
        expect(await desk.read()).toBeNull();
        const body = {
            expectedRevision: '0',
            equipmentDraft: draft().equipmentDraft,
            venueDraft: draft().venueDraft,
            sourceJobId: null,
        };
        transport.mockResolvedValueOnce(
            json(200, { id: draft().id, revision: '1', eventRevision: '1' }),
        );
        expect(await desk.save(body)).toMatchObject({ kind: 'done', data: { revision: '1' } });
        // 계약(final327Q3-save)대로 키를 보낸다(#355)
        expect(call(1)).toMatchObject({
            method: 'PUT',
            key: expect.stringMatching(/^[0-9a-f-]{36}$/),
            body,
        });
        transport.mockResolvedValueOnce(failure(409, 'VERSION_CONFLICT'));
        expect((await desk.save(body)).kind).toBe('conflict');
    });

    it('미확정 초안은 현재 공연의 것만 받고, 뺀 장비를 가리키는 후보를 찾는다', () => {
        expect(parseUnconfirmedDraft(draft(), eventId).revision).toBe('1');
        expect(() =>
            parseUnconfirmedDraft(
                draft({ eventId: '10000000-0000-4000-8000-0000000000ff' }),
                eventId,
            ),
        ).toThrow();
        const value = parseUnconfirmedDraft(draft(), eventId).equipmentDraft;
        expect(danglingAssetRefs(value)).toEqual([]);
        expect(danglingAssetRefs({ ...value, assets: value.assets.slice(0, 1) })).toEqual([
            'tmp-stagebox',
        ]);
    });
});
