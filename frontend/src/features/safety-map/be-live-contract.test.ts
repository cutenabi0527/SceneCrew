import { describe, expect, it } from 'vitest';
import { checkV9Shape } from '../../shared/api/v9';
import samples from './be-live-samples.json';
import {
    parseCamera,
    parseCameraPage,
    parseObservation,
    parseObservationPage,
    parseSafetyMap,
} from './contract';

// 로컬 BE의 실제 안전 응답(EXT10·11·13·14·21~24)을 FE의 엄격한 검사기로 읽는다(합성 데이터, 2026-11-08 수집).
// BE 응답 모양이 계약과 어긋나면 이 시험이 먼저 실패한다.
const eventId = samples.map.eventId;

describe('BE 안전 지도/카메라 실제 응답', () => {
    it('안전 지도(EXT10)에 저장한 배치도/구역 프로필과 카메라 최신 관측이 보인다', () => {
        const map = parseSafetyMap(samples.map, eventId);
        expect(map.layout?.coordinateSystem).toBe('normalized-image');
        expect(map.profiles).toHaveLength(1);
        expect(map.observations).toHaveLength(1);
    });

    it('카메라 목록/조회(EXT21, 23)와 수정 영수증(EXT24)을 읽는다', () => {
        const page = parseCameraPage(samples.cameras, eventId);
        expect(page.items).toHaveLength(1);
        const camera = parseCamera(samples.camera, eventId, samples.camera.id);
        // 연결 상태와 관측 최신성은 따로 온다.
        expect(camera.connectionState).toBe('DISABLED');
        expect(camera.freshness).toBe('UNKNOWN');
        expect(() => checkV9Shape('FinalReceipt', samples.receipt)).not.toThrow();
    });

    it('관측 목록/조회(EXT13, 14)를 읽고, 미확인 밀도는 null로 남는다', () => {
        const page = parseObservationPage(samples.observations, eventId);
        expect(page.nextCursor).toBeNull();
        const one = parseObservation(samples.observation, eventId, samples.observation.id);
        expect(one.estimates[0].densityPerM2).toBeNull();
    });

    it('카메라 웹 연결(#393) 응답의 추가 칸·등록 안됨(UNLINKED)·연결 식별자 없음을 받고, 다른 위반은 계속 거절한다', () => {
        const web = {
            ...samples.camera,
            sourceBindingRef: null,
            connectionState: 'UNLINKED',
            inputKind: null,
            linkTaskId: '00000000-0000-4000-8000-000000000123',
            linkMemberId: null,
            linkedAt: null,
        };
        const camera = parseCamera(web, eventId, samples.camera.id);
        expect(camera.connectionState).toBe('UNLINKED');
        expect(camera.sourceBindingRef).toBeNull();
        expect(camera.linkTaskId).toBe('00000000-0000-4000-8000-000000000123');
        const page = parseCameraPage({ ...samples.cameras, items: [web, samples.camera] }, eventId);
        expect(page.items.map((c) => c.connectionState)).toEqual(['UNLINKED', 'DISABLED']);
        // 계약에 없는 상태 값, 필수 칸 누락, 다른 공연은 그대로 거절한다
        expect(() => parseCamera({ ...web, connectionState: 'BOGUS' }, eventId, web.id)).toThrow();
        const noName: Record<string, unknown> = { ...web };
        delete noName.name;
        expect(() => parseCamera(noName, eventId, web.id)).toThrow();
        expect(() =>
            parseCameraPage({ ...samples.cameras, items: [{ ...web, eventId: 'other' }] }, eventId),
        ).toThrow();
    });
});
