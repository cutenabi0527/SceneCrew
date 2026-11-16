import { parseCrop } from '../unconfirmed-draft/layout';
import type { BackgroundFile } from '../unconfirmed-draft/useBackground';
import { asUuid } from '../../shared/api/contract';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import {
    parseCamera,
    parseCameraPage,
    parseObservation,
    parseObservationPage,
    parseSafetyMap,
    parseSupportCandidates,
    type SupportQuery,
} from './contract';

// 안전 지도·관측·카메라 조회와 카메라 현재 프레임(Q2 Snapshot). 모두 현재 총괄 전용 GET이다.
// 명령(지도·카메라 설정, 인력 이동)은 보내지 않는다. 인력 이동은 지원 후보를 골라 만든 변경안(066)을 승인해서 한다(#361).

export interface Frame {
    blob: Blob;
    capturedAt: string | null;
    receivedAt: string | null;
    // 서버가 판정한 프레임 최신성. 모르는 값이면 STALE로 다루지 않고 그대로 '알 수 없음'으로 둔다.
    freshness: 'FRESH' | 'STALE' | 'UNKNOWN';
}

export class SafetyReader {
    constructor(
        private readonly session: IdentitySession,
        private readonly scope: ParticipationScope,
    ) {}

    private get<T>(path: string, parser: (d: unknown) => T, signal?: AbortSignal) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        return this.session.api.get(this.scope, path, parser, { signal });
    }

    map(signal?: AbortSignal) {
        return this.get(
            `/events/${this.scope.eventId}/safety-map`,
            (d) => parseSafetyMap(d, this.scope.eventId),
            signal,
        );
    }

    observations(signal?: AbortSignal) {
        return this.get(
            `/events/${this.scope.eventId}/safety-observations?limit=50`,
            (d) => parseObservationPage(d, this.scope.eventId),
            signal,
        );
    }

    observation(id: string, signal?: AbortSignal) {
        const oid = asUuid(id, 'observationId');
        return this.get(
            `/safety-observations/${oid}`,
            (d) => parseObservation(d, this.scope.eventId, oid),
            signal,
        );
    }

    /** 지원 후보(#361). 규칙으로 고른 후보이며 운영 상태를 바꾸지 않는다. 후보가 없으면 빈 목록이다. */
    supportCandidates(query: SupportQuery, expectedEventRevision: string, signal?: AbortSignal) {
        const params = new URLSearchParams({
            observationId: asUuid(query.observationId, 'observationId'),
            sourceZoneId: asUuid(query.sourceZoneId, 'sourceZoneId'),
            targetZoneId: asUuid(query.targetZoneId, 'targetZoneId'),
            workGroupId: asUuid(query.workGroupId, 'workGroupId'),
            startAt: query.startAt,
            endAt: query.endAt,
            expectedEventRevision,
        });
        return this.get(
            `/events/${this.scope.eventId}/safety-support-candidates?${params.toString()}`,
            parseSupportCandidates,
            signal,
        );
    }

    cameras(signal?: AbortSignal) {
        return this.get(
            `/events/${this.scope.eventId}/safety-cameras?limit=50`,
            (d) => parseCameraPage(d, this.scope.eventId),
            signal,
        );
    }

    camera(id: string, signal?: AbortSignal) {
        const cid = asUuid(id, 'cameraId');
        return this.get(
            `/safety-cameras/${cid}`,
            (d) => parseCamera(d, this.scope.eventId, cid),
            signal,
        );
    }

    /** V9-D62: 발행 때 저장한 평면도 배경 그림(PNG). 없으면 404로 실패한다. */
    async background(signal?: AbortSignal): Promise<BackgroundFile> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const result = await this.session.api.getBinary(
            this.scope,
            `/events/${this.scope.eventId}/safety-map/background`,
            'image/png',
            { signal },
        );
        return { blob: result.blob, crop: parseCrop(result.headers.get('X-Floor-Plan-Crop')) };
    }

    /** 카메라의 현재 프레임 한 장. 캐시·영구 주소를 만들지 않고 받은 바이트만 돌려준다. */
    async snapshot(cameraId: string, signal?: AbortSignal): Promise<Frame> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const cid = asUuid(cameraId, 'cameraId');
        const result = await this.session.api.getBinary(
            this.scope,
            `/safety-cameras/${cid}/snapshot`,
            'image/jpeg',
            {
                signal,
            },
        );
        const freshness = result.headers.get('X-Frame-Freshness');
        return {
            blob: result.blob,
            capturedAt: result.headers.get('X-Captured-At'),
            receivedAt: result.headers.get('X-Received-At'),
            freshness: freshness === 'FRESH' || freshness === 'STALE' ? freshness : 'UNKNOWN',
        };
    }
}
