import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import {
    parseAssetPage,
    parseDetail,
    parseReservationPage,
    type LiveAsset,
    type LiveReservation,
    type Page,
} from './live-contract';

// 장비 현황 조회(EXT01·EXT03·EXT05). 읽기만 하며 명령(등록·복구 계산·변경안)은 보내지 않는다.
// 권한은 서버가 판정한다: 총괄 또는 장비 조회 허용 그룹의 현재 팀원(V9-D13). FE는 403을 그대로 안내한다.

/** 한 번 열 때 따라가는 목록 페이지 상한. 더 있으면 화면에 잘림을 알린다. */
export const MAX_PAGES = 10;

export class EquipmentReader {
    constructor(
        private readonly session: IdentitySession,
        private readonly scope: ParticipationScope,
    ) {}

    private get<T>(path: string, parser: (data: unknown) => T, signal?: AbortSignal) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        return this.session.api.get(this.scope, path, parser, { signal });
    }

    private async all<T>(base: string, parser: (data: unknown) => Page<T>, signal?: AbortSignal) {
        const items: T[] = [];
        const seen = new Set<string>();
        let cursor: string | null = null;
        for (let page = 0; page < MAX_PAGES; page++) {
            const path: string = cursor ? `${base}?cursor=${encodeURIComponent(cursor)}` : base;
            const result: Page<T> = await this.get(path, parser, signal);
            items.push(...result.items);
            if (result.nextCursor === null) return { items, truncated: false };
            // 같은 cursor가 되돌아오면 무한 반복을 막고 잘림으로 표시한다
            if (seen.has(result.nextCursor)) return { items, truncated: true };
            seen.add(result.nextCursor);
            cursor = result.nextCursor;
        }
        return { items, truncated: true };
    }

    async overview(signal?: AbortSignal): Promise<{
        assets: LiveAsset[];
        reservations: LiveReservation[];
        truncated: boolean;
    }> {
        const eventId = this.scope.eventId;
        const [assets, reservations] = await Promise.all([
            this.all(`/events/${eventId}/equipment`, parseAssetPage, signal),
            this.all(`/events/${eventId}/equipment-reservations`, parseReservationPage, signal),
        ]);
        // 다른 행사의 값이 섞이면 늦은 응답이나 잘못된 응답으로 보고 쓰지 않는다
        if (
            assets.items.some((a) => a.eventId !== eventId) ||
            reservations.items.some((r) => r.eventId !== eventId)
        ) {
            throw new ScopeDisposedError();
        }
        return {
            assets: assets.items,
            reservations: reservations.items,
            truncated: assets.truncated || reservations.truncated,
        };
    }

    detail(assetId: string, signal?: AbortSignal) {
        return this.get(`/equipment/${assetId}`, parseDetail, signal);
    }
}
