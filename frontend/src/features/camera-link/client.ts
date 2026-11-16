import { classifyFailure, type Failure } from '../../shared/api/failure';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import {
    parseCameraLink,
    parseCameraUnlink,
    parseFrameAck,
    parseMyLinks,
    type CameraLink,
    type FrameAck,
    type MyLinks,
} from './contract';

// 카메라 웹 연결 API(W1~W4, #393). 연결·해제는 요청 키를 받지 않는 비멱등 명령이라, 결과를 모르면 다시 보내지 않고
// 내 연결 목록을 다시 읽어 판단한다(상태 조회). 프레임은 바이너리 POST(postBlob)로 보낸다.

const LINK = 'camera-link:link';
const UNLINK = 'camera-link:unlink';

export type LinkResult =
    | { kind: 'linked'; link: CameraLink }
    /** 다른 브라우저가 보내는 중이거나 막 연결했다. 넘겨받기를 확인한 뒤 takeover로 다시 보낸다 */
    | { kind: 'taken' }
    /** 칸이 바뀌었다(revision). 목록을 다시 읽는다 */
    | { kind: 'conflict' }
    | { kind: 'rejected'; failure: Failure }
    /** 결과를 모른다. 다시 보내지 않고 목록을 다시 읽는다 */
    | { kind: 'check-state' };

export type UnlinkResult = 'unlinked' | 'already' | 'rejected' | 'check-state';

export class CameraLinkDesk {
    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {}

    private assert() {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
    }

    /** W1 내 연결 목록. 팀원은 내 담당 카메라만, 총괄은 모든 카메라 */
    myLinks(signal?: AbortSignal): Promise<MyLinks> {
        this.assert();
        return this.session.api.get(
            this.scope,
            `/events/${this.scope.eventId}/my-links`,
            parseMyLinks,
            { signal },
        );
    }

    /** W2 연결 시작·넘겨받기 */
    async link(
        cameraId: string,
        expectedCameraRevision: string,
        takeover = false,
    ): Promise<LinkResult> {
        this.assert();
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: LINK,
                intentId,
                method: 'POST',
                path: `/safety-cameras/${cameraId}/browser-link`,
                body: takeover
                    ? { expectedCameraRevision, takeover: true }
                    : { expectedCameraRevision },
                idempotency: 'none',
                response: 'data',
            },
            parseCameraLink,
        );
        // 다음 판단은 항상 목록 조회로 한다. 남은 키는 재전송에 쓰지 않으므로 정리한다.
        this.session.scopes.intentKeys.release(this.scope, LINK, intentId);
        if (outcome.status === 'succeeded' && outcome.data)
            return { kind: 'linked', link: outcome.data };
        if (outcome.status === 'failed') {
            const f = outcome.failure;
            if (f.code === 'CAMERA_LINK_TAKEN') return { kind: 'taken' };
            if (f.kind === 'version-conflict') return { kind: 'conflict' };
            if (f.intent === 'finished' || f.kind === 'unauthenticated')
                return { kind: 'rejected', failure: f };
        }
        return { kind: 'check-state' };
    }

    /** W3 연결 해제. 결과를 모르면 목록을 다시 읽는다 */
    async unlink(cameraId: string): Promise<UnlinkResult> {
        this.assert();
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: UNLINK,
                intentId,
                method: 'DELETE',
                path: `/safety-cameras/${cameraId}/browser-link`,
                idempotency: 'none',
                response: 'data',
            },
            parseCameraUnlink,
        );
        this.session.scopes.intentKeys.release(this.scope, UNLINK, intentId);
        if (outcome.status === 'succeeded') return 'unlinked';
        if (outcome.status === 'failed') {
            if (outcome.failure.code === 'CAMERA_UNLINKED') return 'already';
            if (outcome.failure.intent === 'finished') return 'rejected';
        }
        return 'check-state';
    }

    /** W4 프레임 한 장. 실패는 그대로 던진다(보내기 모듈이 stopReason으로 멈출지 정한다) */
    sendFrame(
        cameraId: string,
        frame: { linkId: string; session: string; sequence: number; jpeg: Blob },
        signal?: AbortSignal,
    ): Promise<FrameAck> {
        this.assert();
        return this.session.api.postBlob(
            this.scope,
            `/safety-cameras/${cameraId}/frames`,
            frame.jpeg,
            {
                'X-SceneCrew-Link': frame.linkId,
                'X-SceneCrew-Stream-Session': frame.session,
                'X-SceneCrew-Sequence': String(frame.sequence),
            },
            (d) => parseFrameAck(d, frame.sequence),
            { signal },
        );
    }
}

/**
 * 프레임 실패를 "멈춤 이유"로 바꾼다. null이면 일시 오류라 다음 차례에 새 사진을 보낸다.
 * 403(자격 상실)·409(넘겨받힘·해제·사용 안 함)는 즉시 멈춘다(#396 완료 조건).
 */
export function stopReason(error: unknown): string | null {
    const f = classifyFailure(error);
    if (f.kind === 'cancelled') return null;
    switch (f.code) {
        case 'CAMERA_LINK_TAKEN':
            return '다른 사람이 연결을 넘겨받았습니다. 이 탭은 보내기를 멈췄습니다.';
        case 'CAMERA_UNLINKED':
            return '연결이 해제되었습니다. 다시 보내려면 연결을 새로 시작하세요.';
        case 'CAMERA_LINK_FORBIDDEN':
            return '이 카메라를 보낼 자격이 없어졌습니다. 연결 담당 업무가 바뀌었는지 확인하세요.';
        case 'CAMERA_DISABLED':
            return '총괄이 이 카메라를 사용 안 함으로 바꿨습니다.';
        default:
            break;
    }
    if (f.kind === 'unauthenticated') return '로그인이 끝났습니다. 다시 로그인한 뒤 연결하세요.';
    if (f.status === 404) return '카메라나 참여 정보를 찾지 못했습니다. 목록을 다시 확인하세요.';
    if (f.status === 403) return '이 카메라를 보낼 자격이 없습니다.';
    // 순번이 밀림(409 OUTDATED)·크기 초과(413)·일시 오류는 이번 차례만 버리고 다음 사진을 보낸다
    return null;
}
