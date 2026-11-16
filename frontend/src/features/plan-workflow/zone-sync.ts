import { refId, type Draft, type Zone } from './contract';

/** 초안의 구역 이름 목록이 평면도 구역 이름 목록(순서 포함)과 같은가 */
export function sameZoneNames(draft: Draft, names: string[]) {
    return draft.zones.length === names.length && draft.zones.every((z, i) => z.name === names[i]);
}

/**
 * 계획 초안의 구역을 평면도에 올린 구역(이름·개수·순서)에 맞춘다. 구역은 평면도에서만 바꾼다.
 * 같은 이름의 기존 구역은 id·설명을 그대로 이어받고, 새 이름은 새 구역이 된다.
 * 사라진 구역을 가리키던 업무는 첫 구역으로, 공연 순서는 구역 없음으로 옮긴다.
 */
export function syncZones(draft: Draft, names: string[], newId: () => string): Draft {
    const pool = [...draft.zones];
    const zones: Zone[] = names.map((name) => {
        const at = pool.findIndex((z) => z.name === name);
        if (at >= 0) return pool.splice(at, 1)[0];
        return { tempId: newId(), name, description: null };
    });
    const kept = new Set(zones.map(refId));
    const first = zones[0] ? refId(zones[0]) : '';
    return {
        ...draft,
        zones,
        programs: draft.programs.map((p) =>
            p.zoneId && !kept.has(p.zoneId) ? { ...p, zoneId: null } : p,
        ),
        tasks: draft.tasks.map((t) => (kept.has(t.zoneId) ? t : { ...t, zoneId: first })),
    };
}
