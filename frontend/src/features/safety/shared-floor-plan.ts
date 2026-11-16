import { useSyncExternalStore } from 'react';
import { useData, useEvent } from '../../domain/hooks';
import { useDemoState } from '../../domain/store-context';
import type { FloorPlan } from '../../domain/types';
import type { Rect } from './zone-detect';

/**
 * 실서버 공연의 평면도 그림. 서버는 평면도 그림을 받지 않으므로(안전 지도는 구역 도형만 저장한다)
 * 계획 수정에서 올린 평면도와 구역 상자를 이 브라우저에 공연별로 두고, 안전 탭 평면도가 같은 것을 쓴다.
 * 데모 공연은 데모 저장소(setFloorPlan)에 바로 저장하므로 여기를 쓰지 않는다.
 */
export type SharedFloorPlan = {
    plan: FloorPlan;
    /** 평면도 편집 창의 구역 상자 전부(계획 구역과 겹치면 그 id). 계획 수정의 구역 목록이 이 이름을 따른다 */
    zones: { id: string | null; name: string; rect: Rect }[];
    /** 안전 탭에서 구역 도형을 서버에 저장한 뒤에는 상자 위치 대신 서버 도형을 쓴다 */
    serverRects?: boolean;
};

const key = (eventId: string) => `scenecrew.floor-plan:${eventId}`;
const CHANGED = 'scenecrew:floor-plan-changed';

const cache = new Map<string, { raw: string | null; value: SharedFloorPlan | null }>();

function isShared(v: unknown): v is SharedFloorPlan {
    const s = v as SharedFloorPlan | null;
    return (
        !!s &&
        !!s.plan &&
        typeof s.plan.src === 'string' &&
        typeof s.plan.width === 'number' &&
        typeof s.plan.height === 'number' &&
        Array.isArray(s.zones)
    );
}

export function readSharedFloorPlan(eventId: string): SharedFloorPlan | null {
    let raw: string | null;
    try {
        raw = localStorage.getItem(key(eventId));
    } catch {
        return null;
    }
    const hit = cache.get(eventId);
    if (hit && hit.raw === raw) return hit.value;
    let value: SharedFloorPlan | null;
    try {
        const parsed: unknown = raw ? JSON.parse(raw) : null;
        value = isShared(parsed) ? parsed : null;
    } catch {
        value = null;
    }
    // 같은 글자면 같은 값을 돌려준다(useSyncExternalStore가 매번 다시 그리지 않게)
    cache.set(eventId, { raw, value });
    return value;
}

/** 저장하지 못하면(저장 공간 부족 등) false */
export function writeSharedFloorPlan(eventId: string, value: SharedFloorPlan | null): boolean {
    try {
        if (value) localStorage.setItem(key(eventId), JSON.stringify(value));
        else localStorage.removeItem(key(eventId));
    } catch {
        return false;
    }
    window.dispatchEvent(new Event(CHANGED));
    return true;
}

/** 안전 탭에서 구역 도형을 서버에 저장하면 서버 도형을 따른다(그림과 구역 이름은 그대로 둔다) */
export function forgetSharedZones(eventId: string) {
    const current = readSharedFloorPlan(eventId);
    if (current && !current.serverRects)
        writeSharedFloorPlan(eventId, { ...current, serverRects: true });
}

function subscribe(onChange: () => void) {
    window.addEventListener(CHANGED, onChange);
    window.addEventListener('storage', onChange);
    return () => {
        window.removeEventListener(CHANGED, onChange);
        window.removeEventListener('storage', onChange);
    };
}

export function useSharedFloorPlan(eventId: string | undefined) {
    return useSyncExternalStore(subscribe, () => (eventId ? readSharedFloorPlan(eventId) : null));
}

/**
 * 안전 평면도에 보일 그림과 구역. 실서버는 이 브라우저에 둔 평면도 그림을 쓰고, 같은 id의 구역은
 * 계획 수정에서 맞춘 상자 위치를 쓴다. 데모는 데모 저장소 그대로다.
 */
export function useFloorView(eventId?: string) {
    const d = useData();
    const ev = useEvent();
    const { live } = useDemoState();
    const shared = useSharedFloorPlan(live ? (eventId ?? ev.id) : undefined);
    if (!live || !shared) return { plan: d.floorPlan, zones: d.safetyZones, shared: false };
    const rects = new Map(
        shared.serverRects ? [] : shared.zones.flatMap((z) => (z.id ? [[z.id, z.rect]] : [])),
    );
    return {
        plan: shared.plan,
        zones: d.safetyZones.map((z) => {
            const rect = rects.get(z.id);
            return rect ? { ...z, rect } : z;
        }),
        shared: true,
    };
}

/**
 * 평면도에 올린 구역 이름(순서대로). 평면도가 아직 없으면 null이다(그때는 계획의 구역을 그대로 둔다).
 * 데모는 데모 저장소의 평면도 구역, 실서버는 이 브라우저에 둔 평면도의 구역 상자다.
 */
export function useFloorZoneNames(eventId?: string): string[] | null {
    const d = useData();
    const ev = useEvent();
    const { live } = useDemoState();
    const shared = useSharedFloorPlan(live ? (eventId ?? ev.id) : undefined);
    if (live) return shared ? shared.zones.map((z) => z.name ?? '') : null;
    return d.floorPlan ? d.safetyZones.map((z) => z.name) : null;
}

/** 계획 수정의 평면도 칸과 자르기 '평면도 비율'이 쓰는 가로/세로 비율(5177×2748) */
export const FLOOR_RATIO = 5177 / 2748;
