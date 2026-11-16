import type {
    CameraLink,
    ObsFresh,
    ObsQuality,
    SafetyCamera,
    SafetyObservation,
    SafetyZone,
} from '../domain/types';
import {
    latestByZone,
    noFirstFrame,
    type Camera,
    type Observation,
    type RiskLevel,
    type SafetyMap,
} from '../features/safety-map/contract';
import { defaultLayout, rectOf, type Point } from '../features/unconfirmed-draft/layout';
import { kstTime } from './convert';

// 안전 지도·카메라(#372)를 디자인 W05 화면 모델로 바꾼다. 총괄만 읽는다.
// - 구역은 공연 계획의 구역이다. 도형은 안전 지도 배치도의 다각형을 감싸는 사각형(%)으로 그리고,
//   배치도가 없거나 도형이 없는 구역이 있으면 구역 이름으로 기본 배치(V9-D54, 무대 앞 한 줄)를 쓴다.
// - 위험 판정은 서버 riskLevel을 그대로 쓴다(화면에서 다시 계산하지 않는다).
// - 낡음·시각 불명·매핑 무효·처리 중 관측은 FRESH로 보지 않아 0명/정상으로 보이지 않는다.

export interface LiveSafety {
    map: SafetyMap;
    cameras: Camera[];
}

/** 화면 구역 도형(%)과 그 출처. 기본 배치면 총괄이 고쳐 저장하라고 알린다 */
export interface SafetyView {
    safetyZones: SafetyZone[];
    observations: SafetyObservation[];
    cameras: SafetyCamera[];
    /** 배치도에 도형이 없어 기본 배치로 그린 구역이 있다 */
    defaultArrangement: boolean;
}

const RISK: Record<RiskLevel, NonNullable<SafetyObservation['risk']>> = {
    EXCEEDS_CONFIRMED_RULE: 'OVER',
    ATTENTION: 'NEAR',
    WITHIN_CONFIRMED_RULE: 'OK',
    UNASSESSED: 'CHECK',
};

const LINK: Record<Camera['connectionState'], CameraLink> = {
    UNLINKED: 'NONE',
    CONNECTED: 'CONNECTED',
    CONNECTING: 'CONNECTING',
    OFFLINE: 'DISCONNECTED',
    ERROR: 'DISCONNECTED',
    DISABLED: 'DISCONNECTED',
};

const pct = (n: number) => Math.round(n * 1000) / 10;

/** 0~1 다각형 → 화면 도형 [x, y, w, h](%) */
export function rectPercent(points: Point[]): SafetyZone['rect'] {
    const r = rectOf(points);
    return [pct(r.x), pct(r.y), pct(r.width), pct(r.height)];
}

function fresh(o: Observation): ObsFresh {
    if (o.state === 'FAILED' || o.state === 'EXPIRED' || o.freshness === 'INVALID_MAPPING')
        return 'FAILED';
    if (o.state === 'RECEIVED' || o.state === 'PROCESSING') return 'PENDING';
    // 시각 불명(UNKNOWN)은 지금 값으로 쓰지 않는다
    return o.freshness === 'FRESH' && o.capturedAt ? 'FRESH' : 'STALE';
}

function quality(count: number | null, reasons: string[]): ObsQuality {
    if (count == null) return 'NONE';
    return reasons.length ? 'PARTIAL' : 'GOOD';
}

export function toSafety(
    raw: LiveSafety,
    zones: readonly { id: string; name: string }[],
    /** 구성원 id → 이름(웹으로 연결한 사람 표시) */
    names: ReadonlyMap<string, string> = new Map(),
    now = Date.now(),
): SafetyView {
    const layout = raw.map.layout;
    const polygon = new Map(layout?.zonePolygons.map((p) => [p.zoneId, p.points]) ?? []);
    const fallback = defaultLayout(zones.map((z) => ({ key: z.id, name: z.name })));
    const fallbackPoints = new Map(fallback?.zonePolygons.map((p) => [p.zoneId, p.points]) ?? []);
    let defaultArrangement = false;
    const safetyZones: SafetyZone[] = zones.map((z) => {
        const points = polygon.get(z.id);
        if (!points) defaultArrangement = true;
        const profile = raw.map.profiles.find((p) => p.zoneId === z.id);
        const crew = profile?.minimumCrewRule as { minimumCount?: unknown } | null | undefined;
        return {
            id: z.id,
            name: z.name,
            area: profile?.effectiveAreaM2 ?? null,
            // 기준 판정은 서버가 한다(risk). 화면이 명/㎡ 기준으로 다시 판단하지 않게 비운다
            limit: null,
            minStaff: typeof crew?.minimumCount === 'number' ? crew.minimumCount : 0,
            rect: rectPercent(points ?? fallbackPoints.get(z.id) ?? []),
        };
    });
    const known = new Set(zones.map((z) => z.id));
    const observations: SafetyObservation[] = [];
    for (const [zoneId, { estimate, observation }] of latestByZone(raw.map.observations)) {
        if (!known.has(zoneId)) continue;
        observations.push({
            zoneId,
            cameraId: observation.cameraId,
            count: estimate.estimatedCount,
            at: observation.capturedAt ? kstTime(observation.capturedAt) : null,
            quality: quality(estimate.estimatedCount, estimate.qualityReasons),
            fresh: fresh(observation),
            risk: RISK[estimate.riskLevel],
        });
    }
    const cameras: SafetyCamera[] = raw.cameras.map((c) => {
        const covered = c.coverage.filter((v) => known.has(v.zoneId));
        return {
            id: c.id,
            name: c.name,
            link: LINK[c.connectionState],
            active: c.enabled,
            zones: covered.map((v) => v.zoneId),
            // 구역마다 관측 범위가 정해져야 보정된 것으로 본다(일부만 비추면 ROI가 있어야 한다)
            calibrated:
                covered.length > 0 &&
                covered.every(
                    (v) => v.scope === 'FULL' || (v.scope === 'PARTIAL' && !!v.roi?.length),
                ),
            frameAt: c.lastCapturedAt ? kstTime(c.lastCapturedAt) : undefined,
            unlinked: c.connectionState === 'UNLINKED',
            input: c.inputKind,
            linkedBy: c.linkMemberId ? (names.get(c.linkMemberId) ?? '알 수 없는 사람') : null,
            noFirstFrame: noFirstFrame(c, now),
        };
    });
    return { safetyZones, observations, cameras, defaultArrangement };
}
