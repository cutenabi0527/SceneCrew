import { describe, expect, it } from 'vitest';
import { backZoneId, cameras, safetyMap } from '../../tests/fixtures/safety-data';
import { zoneId } from '../../tests/fixtures/workflow-data';
import type { Workflow } from '../features/plan-workflow/client';
import { parseCameraPage, parseSafetyMap } from '../features/safety-map/contract';
import { linkSafety, supportTargets } from './safety-link';

const eventId = safetyMap.eventId;
const map = parseSafetyMap(structuredClone(safetyMap), eventId);
const parts = {
    map,
    cameras: parseCameraPage(structuredClone(cameras), eventId).items,
    zones: [
        { id: zoneId, name: 'A구역 무대 앞' },
        { id: backZoneId, name: 'B구역 객석 뒤쪽' },
    ],
    groups: [],
    members: [],
    eventRevision: '9',
};
// 조회기·명령은 만들기만 하고 부르지 않는다
const scope = () => ({ eventId, onDispose: () => undefined });
const client = { session: {}, scope: scope() } as unknown as Workflow;

describe('실서버 안전 연결(#372 2차)', () => {
    it('지원 대상은 최신(FRESH) 관측 구역만이고 서버 판정·추정 인원을 적는다', () => {
        const link = linkSafety(null, client, parts);
        const targets = supportTargets(link);
        // 객석 뒤쪽은 오래된 관측이라 빠진다
        expect(targets.map((t) => t.zoneId)).toEqual([zoneId]);
        expect(targets[0].label).toBe('A구역 무대 앞 / 기준 초과 / 추정 180명');
    });

    it('화면에서 고른 구역을 맨 앞에 둔다', () => {
        const fresh = structuredClone(safetyMap);
        for (const o of fresh.observations) o.freshness = 'FRESH';
        const link = linkSafety(null, client, { ...parts, map: parseSafetyMap(fresh, eventId) });
        expect(supportTargets(link, backZoneId)[0].zoneId).toBe(backZoneId);
        expect(supportTargets(link, zoneId)[0].zoneId).toBe(zoneId);
    });

    it('같은 공연 연결이면 조회기를 그대로 써서 현재 프레임 조회가 다시 시작되지 않는다', () => {
        const first = linkSafety(null, client, parts);
        const again = linkSafety(first, client, { ...parts, eventRevision: '10' });
        expect(again.reader).toBe(first.reader);
        expect(again.eventRevision).toBe('10');
        const other = { session: {}, scope: scope() } as unknown as Workflow;
        expect(linkSafety(again, other, parts).reader).not.toBe(first.reader);
    });
});
