import { describe, expect, it } from 'vitest';
import { ContractViolationError } from '../../shared/api/contract';
import { v9Examples } from '../../shared/api/v9/testing';
import { cameras, safetyMap } from '../../../tests/fixtures/safety-data';
import { eventId } from '../../../tests/fixtures/workflow-data';
import {
    bindingProblem,
    connectionHint,
    type Camera,
    latestByZone,
    parseCamera,
    parseCameraPage,
    parseSupportCandidates,
    eligibilityText,
    supportAction,
    supportSelection,
    windowProblem,
    parseSafetyMap,
    type Observation,
} from './contract';

const example = (op: string) =>
    structuredClone(v9Examples.find((e) => e.op === op && e.valid)!.value) as Record<
        string,
        unknown
    >;
const EVENT = '10000000-0000-4000-8000-000000000001';

describe('안전 지도 응답 검사', () => {
    it('계약 예시를 읽고 시계 미상 관측을 0명/정상으로 바꾸지 않는다', () => {
        const map = parseSafetyMap(example('GET /events/{e}/safety-map'), EVENT);
        const estimate = map.observations[0].estimates[0];
        expect(estimate.estimatedCount).toBeNull();
        expect(estimate.riskLevel).toBe('UNASSESSED');
        expect(map.observations[0].freshness).toBe('UNKNOWN');
    });
    it('브라우저 시험 자료도 계약을 지킨다', () => {
        expect(
            parseSafetyMap(structuredClone(safetyMap), eventId).layout?.zonePolygons,
        ).toHaveLength(2);
        expect(parseCameraPage(structuredClone(cameras), eventId).items).toHaveLength(2);
    });
    it('다른 공연의 지도는 표시하지 않는다', () => {
        expect(() =>
            parseSafetyMap(
                example('GET /events/{e}/safety-map'),
                '10000000-0000-4000-8000-0000000000ff',
            ),
        ).toThrow(ContractViolationError);
    });
    it('계약에 없는 위험도 값은 거절한다', () => {
        const value = example('GET /events/{e}/safety-map');
        const obs = (value.observations as Record<string, unknown>[])[0];
        (obs.estimates as Record<string, unknown>[])[0].riskLevel = 'DANGER';
        expect(() => parseSafetyMap(value, EVENT)).toThrow();
    });
    it('요청한 카메라가 아닌 응답은 거절한다', () => {
        const value = example('GET /safety-cameras/{c}');
        expect(() => parseCamera(value, EVENT, '10000000-0000-4000-8000-0000000000ff')).toThrow(
            ContractViolationError,
        );
    });
});

describe('구역별 최신 관측', () => {
    const obs = (id: string, capturedAt: string | null, receivedAt: string, count: number) =>
        ({
            id,
            receivedAt,
            capturedAt,
            estimates: [{ zoneId: 'z1', estimatedCount: count }],
        }) as unknown as Observation;

    it('같은 구역은 더하지 않고 가장 최근 촬영의 값을 고른다', () => {
        const found = latestByZone([
            obs('a', '2026-11-02T03:00:10Z', '2026-11-02T03:00:11Z', 30),
            obs('b', '2026-11-02T03:00:00Z', '2026-11-02T03:00:20Z', 10),
        ]).get('z1')!;
        expect(found.observation.id).toBe('a');
        expect(found.estimate.estimatedCount).toBe(30);
    });
    it('촬영 시각이 없으면 수신 시각으로 비교한다', () => {
        const found = latestByZone([
            obs('a', '2026-11-02T03:00:00Z', '2026-11-02T03:00:01Z', 30),
            obs('b', null, '2026-11-02T03:00:05Z', 12),
        ]).get('z1')!;
        expect(found.observation.id).toBe('b');
    });
});

describe('카메라 연결 식별자 안내(#309)', () => {
    it('BE와 같은 형식만 받고 주소·계정 모양은 거절한다', () => {
        expect(bindingProblem('webcam-1')).toBeNull();
        expect(bindingProblem('laptop1.cam:1_A')).toBeNull();
        expect(bindingProblem('')).toBeNull();
        expect(bindingProblem('rtsp://10.0.0.2/cam')).toMatch(/주소나 계정/);
        expect(bindingProblem('user@cam')).toMatch(/주소나 계정/);
        expect(bindingProblem('웹캠 1')).toMatch(/영문·숫자/);
        expect(bindingProblem('a'.repeat(101))).toMatch(/100자 이하/);
    });
    it('연결 상태마다 총괄이 할 일을 알린다', () => {
        const base = { sourceBindingRef: 'webcam-1' } as Camera;
        expect(connectionHint({ ...base, connectionState: 'CONNECTING' })).toContain(
            'SCENECREW_CAMERA_SOURCE=webcam-1',
        );
        expect(connectionHint({ ...base, connectionState: 'OFFLINE' })).toMatch(/15초 넘게/);
        expect(connectionHint({ ...base, connectionState: 'CONNECTED' })).toBeNull();
    });
});

describe('안전 인력 지원 후보(#361)', () => {
    it('409 INVALID_STATE의 details.reason으로 할 일을 고른다', () => {
        expect(supportAction('SUPPORT_MINIMUM_CREW')).toBe('reduce');
        expect(supportAction('SUPPORT_CREW_RULE_UNAVAILABLE')).toBe('rule');
        expect(supportAction('SUPPORT_CANDIDATE_CHANGED')).toBe('reselect');
        expect(supportAction('SUPPORT_OBSERVATION_STALE')).toBe('reselect');
        expect(supportAction('SUPPORT_OBSERVATION_ZONE_MISMATCH')).toBe('reselect');
        expect(supportAction('SUPPORT_CANDIDATE_DUPLICATE')).toBe('reduce');
        expect(supportAction('SUPPORT_WINDOW_OR_ZONE')).toBe('rule');
        expect(supportAction('SUPPORT_WINDOW_NOT_CURRENT')).toBe('rule');
        expect(supportAction('SUPPORT_TEST_OBSERVATION')).toBe('reselect');
        expect(supportAction('SUPPORT_OPERATIONS_CHANGED')).toBe('reselect');
        expect(supportAction('SUPPORT_ALREADY_IN_PROGRESS')).toBe('reselect');
        expect(supportAction('SOMETHING_ELSE')).toBeNull();
        expect(supportAction(undefined)).toBeNull();
    });
    const candidate = {
        memberId: 'b1111111-1111-4111-8111-111111111111',
        participationRevision: '2',
        sourceTaskId: 'c1111111-1111-4111-8111-111111111111',
        eligibilityReasons: ['CURRENT_PLACEMENT', 'AVAILABLE', 'MINIMUM_CREW_SATISFIED'],
    };
    it('후보 사유 코드는 화면 문구로, 모르는 값은 그대로 보인다', () => {
        expect(eligibilityText('MINIMUM_CREW_SATISFIED')).toBe(
            '보내도 원래 구역 최소 인원이 유지됨',
        );
        expect(eligibilityText('NEW_REASON')).toBe('NEW_REASON');
    });
    it('지원 구간은 지금 시각을 포함해야 한다', () => {
        const now = Date.parse('2026-11-24T09:10:00Z');
        expect(windowProblem('2026-11-24T09:00:00Z', '2026-11-24T09:30:00Z', now)).toBeNull();
        expect(windowProblem('2026-11-24T09:30:00Z', '2026-11-24T09:00:00Z', now)).toMatch(
            /늦어야/,
        );
        expect(windowProblem('2026-11-24T09:20:00Z', '2026-11-24T09:30:00Z', now)).toMatch(/지금/);
        expect(windowProblem('2026-11-24T08:00:00Z', '2026-11-24T09:10:00Z', now)).toMatch(/지났/);
    });
    it('후보·빈 목록을 받고, 계약 밖 값은 거절한다', () => {
        expect(
            parseSupportCandidates({ eventRevision: '7', candidates: [candidate] }).candidates,
        ).toHaveLength(1);
        expect(parseSupportCandidates({ eventRevision: '7', candidates: [] }).candidates).toEqual(
            [],
        );
        for (const bad of [
            { eventRevision: '7', candidates: [{ ...candidate, eligibilityReasons: [] }] },
            { eventRevision: '7', candidates: [{ ...candidate, name: '김' }] },
            { eventRevision: '7', candidates: [{ ...candidate, sourceTaskId: null }] },
            { eventRevision: 7, candidates: [] },
            { eventRevision: '7', candidates: [candidate, { ...candidate }] },
            // 이전 제안의 감싼 키(items)는 받지 않는다
            { eventRevision: '7', items: [] },
            { candidates: [] },
        ])
            expect(() => parseSupportCandidates(bad)).toThrow(ContractViolationError);
    });
    it('066에는 조회 조건과 고른 후보의 회차·원 업무만 보낸다', () => {
        const query = {
            observationId: 'a1111111-1111-4111-8111-111111111111',
            sourceZoneId: 'a2222222-2222-4222-8222-222222222222',
            targetZoneId: 'a3333333-3333-4333-8333-333333333333',
            workGroupId: 'a4444444-4444-4444-8444-444444444444',
            startAt: '2026-11-24T09:00:00Z',
            endAt: '2026-11-24T09:30:00Z',
        };
        expect(supportSelection(query, [candidate])).toEqual({
            ...query,
            candidates: [
                {
                    memberId: candidate.memberId,
                    participationRevision: '2',
                    sourceTaskId: candidate.sourceTaskId,
                },
            ],
        });
    });
});
