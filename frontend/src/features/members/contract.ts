import { ContractViolationError } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';

// 초대 링크(032)·그룹(036)·참여 인원(037)·인원 수정(038)의 응답 검사(#285·#286).
// 그룹 목록은 현재 참여자, 초대·인원·수정은 현재 총괄 전용이다.

export interface InviteDraft {
    eventId: string;
    inviteUrl: string;
    revision: string;
}

export interface WorkGroup {
    id: string;
    name: string;
    instructions?: string;
    active?: boolean;
}

export interface Member {
    memberId: string;
    displayName: string;
    workGroupId: string | null;
    eventRole: 'CHIEF' | 'WORKER';
    active: boolean;
    available: boolean;
    isReserve: boolean;
    participationRevision: string;
}

export interface MemberChanged {
    member: Member;
    eventRevision: string;
}

export interface Page<T> {
    items: T[];
    nextCursor: string | null;
}

export function parseInvite(data: unknown, eventId: string): InviteDraft {
    const invite = parseV9<InviteDraft>('InviteDraft', data);
    if (invite.eventId !== eventId)
        throw new ContractViolationError('invite.eventId', '현재 공연이어야 합니다.');
    // 복사해서 전달하는 값이라 http(s) 주소만 받는다(javascript: 같은 주소 차단)
    if (!/^https?:\/\//.test(invite.inviteUrl))
        throw new ContractViolationError('invite.inviteUrl', 'http(s) 주소여야 합니다.');
    return invite;
}

export function parseGroupPage(data: unknown): Page<WorkGroup> {
    const page = parseV9<Page<WorkGroup>>('WorkGroupPage', data);
    const ids = new Set(page.items.map((g) => g.id));
    if (ids.size !== page.items.length)
        throw new ContractViolationError('workGroups.id', '같은 그룹이 두 번 올 수 없습니다.');
    return page;
}

export function parseMemberPage(data: unknown): Page<Member> {
    return parseV9<Page<Member>>('MemberPage', data);
}

export interface TransferParticipation {
    memberId: string;
    participationRevision: string;
    active: boolean;
    eventRole: 'CHIEF' | 'WORKER';
    workGroupId: string | null;
}

export interface ChiefTransferResult {
    previousChief: TransferParticipation;
    currentChief: TransferParticipation;
    eventRevision: string;
}

/** 042 총괄 인계 결과. 요청한 두 사람의 역할이 실제로 바뀌었는지까지 확인한다. */
export function parseChiefTransfer(
    data: unknown,
    expected: { previousId: string; successorId: string; groupId: string },
): ChiefTransferResult {
    const result = parseV9<ChiefTransferResult>('ChiefTransferResult', data);
    const { previousChief: prev, currentChief: next } = result;
    if (prev.memberId !== expected.previousId)
        throw new ContractViolationError('previousChief.memberId', '요청한 총괄이어야 합니다.');
    if (next.memberId !== expected.successorId)
        throw new ContractViolationError('currentChief.memberId', '요청한 후임이어야 합니다.');
    if (prev.eventRole !== 'WORKER' || prev.workGroupId !== expected.groupId)
        throw new ContractViolationError('previousChief', '요청한 그룹의 팀원이어야 합니다.');
    if (next.eventRole !== 'CHIEF' || !next.active)
        throw new ContractViolationError('currentChief', '활성 총괄이어야 합니다.');
    return result;
}

export function parseMemberChanged(data: unknown, memberId: string): MemberChanged {
    const changed = parseV9<MemberChanged>('MemberChanged', data);
    if (changed.member.memberId !== memberId)
        throw new ContractViolationError('member.memberId', '요청한 인원이어야 합니다.');
    return changed;
}
