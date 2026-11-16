import {
    ContractViolationError,
    asArray,
    asBigIntString,
    asRecord,
    asString,
    asUtcTimestamp,
    asUuid,
} from '../../shared/api/contract';
import { checkV9Shape } from '../../shared/api/v9';

// 인증·참여 응답 검사. 먼저 v9 계약 스키마로 필수·형식·상한·enum을 검사하고(계약에 없는 필드는 허용),
// 그 위에 계약 문장으로만 적힌 규칙(채널별 토큰, 활성 팀원의 그룹 등)을 검사한 뒤 필요한 필드만 읽는다.

export type Channel = 'WEB' | 'ANDROID';
export function boolean(value: unknown, path: string): boolean {
    if (typeof value !== 'boolean') throw new ContractViolationError(path, '참/거짓이어야 합니다.');
    return value;
}
function choice<T extends string>(value: unknown, values: readonly T[], path: string): T {
    if (!values.includes(value as T))
        throw new ContractViolationError(path, '알려진 값이어야 합니다.');
    return value as T;
}
export function revision(value: unknown): string {
    const result = asBigIntString(value, 'revision');
    if (!/^(0|[1-9][0-9]*)$/.test(result) || BigInt(result) > 9223372036854775807n)
        throw new ContractViolationError('revision', '음이 아닌 signed64 문자열이어야 합니다.');
    return result;
}
export function account(data: unknown) {
    checkV9Shape('AccountSummary', data);
    const v = asRecord(data, 'account');
    const primaryEmail =
        v['primaryEmail'] === null ? null : asString(v['primaryEmail'], 'primaryEmail');
    const primaryEmailVerified = boolean(v['primaryEmailVerified'], 'primaryEmailVerified');
    if (primaryEmail === null && primaryEmailVerified)
        throw new ContractViolationError(
            'primaryEmailVerified',
            '이메일이 없으면 false여야 합니다.',
        );
    return {
        id: asUuid(v['id'], 'account.id'),
        displayName: asString(v['displayName'], 'displayName'),
        primaryEmail,
        primaryEmailVerified,
        status: choice(v['status'], ['ACTIVE', 'DISABLED'] as const, 'status'),
    };
}
export type Account = ReturnType<typeof account>;
export interface Tokens {
    accessToken: string;
    refreshToken: string;
}
export function credentials(
    data: unknown,
    channel: Channel,
    // 로그인·이메일 확인은 LoginResult, Refresh 회전은 AuthRefreshResult
    schema: 'LoginResult' | 'AuthRefreshResult',
): Tokens | null {
    checkV9Shape(schema, data);
    const v = asRecord(data, 'session');
    if (v['channel'] !== channel)
        throw new ContractViolationError('channel', '요청 채널과 같아야 합니다.');
    asUtcTimestamp(v['accessExpiresAt'], 'accessExpiresAt');
    asUtcTimestamp(v['refreshExpiresAt'], 'refreshExpiresAt');
    if (channel === 'WEB') {
        if ('accessToken' in v || 'refreshToken' in v)
            throw new ContractViolationError('session', 'WEB 본문에는 토큰이 없어야 합니다.');
        return null;
    }
    const accessToken = asString(v['accessToken'], 'accessToken');
    const refreshToken = asString(v['refreshToken'], 'refreshToken');
    if (!accessToken || !refreshToken)
        throw new ContractViolationError('session', '빈 토큰입니다.');
    return { accessToken, refreshToken };
}
export function participation(data: unknown) {
    const v = asRecord(data, 'participation');
    const result = {
        memberId: asUuid(v['memberId'], 'memberId'),
        participationRevision: revision(v['participationRevision']),
        active: boolean(v['active'], 'active'),
        eventRole: choice(v['eventRole'], ['CHIEF', 'WORKER'] as const, 'eventRole'),
        workGroupId: v['workGroupId'] === null ? null : asUuid(v['workGroupId'], 'workGroupId'),
    };
    if (result.active && result.eventRole === 'WORKER' && result.workGroupId === null)
        throw new ContractViolationError('workGroupId', '활성 팀원에게 그룹이 필요합니다.');
    return result;
}
export type Participation = ReturnType<typeof participation>;
/** 행사 하나. 단독 조회(GET /events/{e})면 schema='Event'를 넘긴다. 목록 항목은 목록 검사가 대신한다. */
export function event(data: unknown, schema?: 'Event') {
    if (schema) checkV9Shape(schema, data);
    const v = asRecord(data, 'event');
    return {
        id: asUuid(v['id'], 'event.id'),
        name: asString(v['name'], 'event.name'),
        state: choice(v['state'], ['DRAFT', 'RECRUITING', 'LIVE', 'ENDED'] as const, 'state'),
        revision: revision(v['revision']),
        myParticipation: participation(v['myParticipation']),
        // 일정·장소는 정해지지 않았으면 null이다. 목록 카드에 "미정"으로 보인다.
        date: optionalText(v['date']),
        openAt: optionalText(v['openAt']),
        closeAt: optionalText(v['closeAt']),
        locationName: optionalText(v['locationName']),
    };
}
export type EventSummary = ReturnType<typeof event>;
/** 비어 있을 수 있는 표시용 글. 문자열이 아니거나 비었으면 null로 둔다(형식 오류로 막지 않는다). */
function optionalText(value: unknown) {
    return typeof value === 'string' && value.trim() !== '' ? value : null;
}
export function eventPage(data: unknown) {
    checkV9Shape('EventPage', data);
    const v = asRecord(data, 'events');
    const cursor = v['nextCursor'];
    if (cursor !== null && (typeof cursor !== 'string' || cursor === '' || cursor === 'null'))
        throw new ContractViolationError('nextCursor', '불투명 문자열 또는 null이어야 합니다.');
    return {
        items: asArray(v['items'], 'items').map((item) => event(item)),
        nextCursor: cursor as string | null,
    };
}
export function inviteOptions(data: unknown) {
    checkV9Shape('InviteOptionsDraft', data);
    const v = asRecord(data, 'invite');
    return {
        eventId: asUuid(v['eventId'], 'eventId'),
        revision: revision(v['revision']),
        myParticipation: v['myParticipation'] === null ? null : participation(v['myParticipation']),
        groups: asArray(v['groups'], 'groups').map((data) => {
            const g = asRecord(data, 'group');
            return {
                workGroupId: asUuid(g['workGroupId'], 'workGroupId'),
                name: asString(g['name'], 'name'),
                taskSummary: asString(g['taskSummary'], 'taskSummary'),
                selectable: boolean(g['selectable'], 'selectable'),
            };
        }),
    };
}
/** 참여 변경 결과. 가용 변경은 ParticipationChanged, 이탈은 DepartureReceipt를 넘긴다. */
export function participationChanged(
    data: unknown,
    schema?: 'ParticipationChanged' | 'DepartureReceipt',
) {
    if (schema) checkV9Shape(schema, data);
    const v = asRecord(data, 'result');
    return {
        participation: participation(v['participation']),
        eventRevision: revision(v['eventRevision']),
    };
}
export function joinResult(data: unknown) {
    checkV9Shape('JoinResult', data);
    const v = asRecord(data, 'join');
    return {
        ...participationChanged(v),
        initialAssignment: choice(
            v['initialAssignment'],
            ['PENDING', 'ASSIGNED', 'NO_CANDIDATE', 'FAILED', 'UNKNOWN'] as const,
            'initialAssignment',
        ),
    };
}
export type Consents = { service: boolean; voice: boolean; push: boolean; externalAi: boolean };

/**
 * #379 011 소셜 교환에서 미연결 계정이 받는 가입 계속 응답(v9 OAuthPending).
 * 정식 세션이 아니므로 계정·채널·토큰이 함께 오면 계약 위반이다. `signupCompleted`가 없으면 로그인 성공
 * 응답으로 보고 null을 돌려준다. `signupExpiresAt`·`provider`는 #379에서 BE에 요청한 선택 필드다.
 */
export type OAuthPending = {
    signupTicket: string;
    availableActions: ('LOGIN_EXISTING' | 'COMPLETE_SIGNUP')[];
    signupExpiresAt: string | null;
    provider: 'GOOGLE' | 'KAKAO' | 'NAVER' | null;
};
export function oauthPending(data: unknown): OAuthPending | null {
    const v = asRecord(data, 'oauth');
    if (!('signupCompleted' in v)) return null;
    if (v['signupCompleted'] !== false)
        throw new ContractViolationError('signupCompleted', '가입 계속 응답은 false여야 합니다.');
    for (const key of ['account', 'channel', 'accessToken', 'refreshToken'])
        if (key in v)
            throw new ContractViolationError(
                key,
                '가입 계속 응답에는 로그인 정보가 없어야 합니다.',
            );
    const signupTicket = asString(v['signupTicket'], 'signupTicket');
    if (!signupTicket) throw new ContractViolationError('signupTicket', '빈 티켓입니다.');
    const availableActions = asArray(v['availableActions'], 'availableActions').map((a, i) =>
        choice(a, ['LOGIN_EXISTING', 'COMPLETE_SIGNUP'] as const, `availableActions[${i}]`),
    );
    if (!availableActions.length)
        throw new ContractViolationError('availableActions', '하나 이상이어야 합니다.');
    return {
        signupTicket,
        availableActions,
        signupExpiresAt:
            v['signupExpiresAt'] == null
                ? null
                : asUtcTimestamp(v['signupExpiresAt'], 'signupExpiresAt'),
        provider:
            v['provider'] == null
                ? null
                : choice(v['provider'], ['GOOGLE', 'KAKAO', 'NAVER'] as const, 'provider'),
    };
}
export function consents(data: unknown): Consents {
    checkV9Shape('ConsentSummary', data);
    const v = asRecord(asRecord(data, 'response')['consents'], 'consents');
    return {
        service: boolean(v['service'], 'service'),
        voice: boolean(v['voice'], 'voice'),
        push: boolean(v['push'], 'push'),
        externalAi: boolean(v['externalAi'], 'externalAi'),
    };
}
