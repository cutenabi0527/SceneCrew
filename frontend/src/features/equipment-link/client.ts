import type { Failure } from '../../shared/api/failure';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import {
    parseLinkCode,
    parseLinkReceipt,
    parseRelease,
    type LinkCode,
    type ProgramRelease,
} from './contract';

// 장비 웹 연결 API(W5~W7, #394). 코드 발급·연결·해제는 요청 키를 받지 않는 비멱등 명령이라,
// 결과를 모르면 다시 보내지 않고 내 연결 목록을 다시 읽어 판단한다(카메라 연결과 같은 방식).

const CODE = 'equipment-link:code';
const LINK = 'equipment-link:link';
const UNLINK = 'equipment-link:unlink';

export type CodeResult =
    | { kind: 'issued'; code: LinkCode }
    | { kind: 'rejected'; failure: Failure }
    | { kind: 'check-state' };

export type EquipmentLinkResult =
    | { kind: 'linked' }
    /** 다른 연결이 살아 있거나 그 수신기가 다른 장비 칸에 연결돼 있다. 확인 뒤 takeover로 다시 보낸다 */
    | { kind: 'taken' }
    | { kind: 'rejected'; failure: Failure }
    | { kind: 'check-state' };

export type EquipmentUnlinkResult = 'unlinked' | 'already' | 'rejected' | 'check-state';

/** 수집 프로그램 내려받기 정보 파일(#399). 로그인 없이 받는 정적 파일이다 */
export const RELEASE_URL = '/downloads/scenecrew-equipment.json';

export class EquipmentLinkDesk {
    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {}

    private assert() {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
    }

    /** W5 연결 코드(10분·한 번). 원문은 이 응답에만 있다 */
    async issueCode(): Promise<CodeResult> {
        this.assert();
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: CODE,
                intentId,
                method: 'POST',
                path: `/events/${this.scope.eventId}/equipment-links/codes`,
                body: {},
                idempotency: 'none',
                response: 'data',
            },
            parseLinkCode,
        );
        this.session.scopes.intentKeys.release(this.scope, CODE, intentId);
        if (outcome.status === 'succeeded' && outcome.data)
            return { kind: 'issued', code: outcome.data };
        if (outcome.status === 'failed') {
            const f = outcome.failure;
            if (f.intent === 'finished' || f.kind === 'unauthenticated')
                return { kind: 'rejected', failure: f };
        }
        return { kind: 'check-state' };
    }

    /** W6 이 수신기 = 이 장비 칸. 지금은 수신기 하나 = 장비 칸 하나라 채널은 1이다(#394) */
    async link(
        assetId: string,
        receiver: { collectorId: string; receiverKey: string },
        takeover = false,
    ): Promise<EquipmentLinkResult> {
        this.assert();
        const intentId = crypto.randomUUID();
        const body = {
            collectorId: receiver.collectorId,
            receiverKey: receiver.receiverKey,
            channel: 1,
            ...(takeover ? { takeover: true } : {}),
        };
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: LINK,
                intentId,
                method: 'PUT',
                path: `/equipment/${assetId}/link`,
                body,
                idempotency: 'none',
                response: 'data',
            },
            parseLinkReceipt,
        );
        this.session.scopes.intentKeys.release(this.scope, LINK, intentId);
        if (outcome.status === 'succeeded') return { kind: 'linked' };
        if (outcome.status === 'failed') {
            const f = outcome.failure;
            if (f.code === 'EQUIPMENT_LINK_TAKEN') return { kind: 'taken' };
            if (f.intent === 'finished' || f.kind === 'unauthenticated')
                return { kind: 'rejected', failure: f };
        }
        return { kind: 'check-state' };
    }

    /** W7 연결 해제. 연결된 장비가 남지 않은 수집기는 서버가 토큰을 폐기해 프로그램이 멈춘다 */
    async unlink(assetId: string): Promise<EquipmentUnlinkResult> {
        this.assert();
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: UNLINK,
                intentId,
                method: 'DELETE',
                path: `/equipment/${assetId}/link`,
                idempotency: 'none',
                response: 'data',
            },
            parseLinkReceipt,
        );
        this.session.scopes.intentKeys.release(this.scope, UNLINK, intentId);
        if (outcome.status === 'succeeded') return 'unlinked';
        if (outcome.status === 'failed') {
            if (outcome.failure.code === 'EQUIPMENT_UNLINKED') return 'already';
            if (outcome.failure.intent === 'finished') return 'rejected';
        }
        return 'check-state';
    }
}

/** 내려받기 정보를 읽는다. 없거나 형식이 다르면 null(화면은 링크 대신 안내를 보인다) */
export async function readRelease(signal?: AbortSignal): Promise<ProgramRelease | null> {
    try {
        const res = await fetch(RELEASE_URL, { cache: 'no-store', signal });
        if (!res.ok) return null;
        return parseRelease(await res.json());
    } catch {
        return null;
    }
}

/** 화면에 보일 실패 사유. 서버 코드를 사람 말로 바꾼다 */
export function linkFailureText(f: Failure): string {
    switch (f.code) {
        case 'COLLECTOR_LINK_FORBIDDEN':
            return '이 장비를 연결할 자격이 없습니다. 연결 담당 업무를 맡았는지, 업무가 대기·진행 중인지 확인하세요.';
        case 'NOT_AUTO_OBSERVED':
            return '자동 관측 장비가 아닙니다. 사람이 상태를 기록하는 장비입니다.';
        case 'INVALID_REFERENCE':
            return '수신기를 찾지 못했습니다. 수집 프로그램이 켜져 있는지 확인하고 목록이 갱신되면 다시 고르세요.';
        case 'EVENT_CLOSED':
            return '공연이 끝나 연결할 수 없습니다.';
        default:
            break;
    }
    if (f.kind === 'unauthenticated') return '로그인이 끝났습니다. 다시 로그인한 뒤 연결하세요.';
    if (f.status === 404) return '장비나 참여 정보를 찾지 못했습니다. 목록을 다시 확인하세요.';
    if (f.status === 409) return '지금 상태에서는 할 수 없습니다. 목록을 다시 확인하세요.';
    return '요청을 처리하지 못했습니다. 잠시 뒤 다시 시도하세요.';
}
