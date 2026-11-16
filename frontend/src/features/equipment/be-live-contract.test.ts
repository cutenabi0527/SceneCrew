import { describe, expect, it } from 'vitest';
import { checkV9Shape } from '../../shared/api/v9';
import samples from './be-live-samples.json';
import { parseReceipt } from './commands';
import { parseAssetPage, parseDetail, parseReservationPage } from './live-contract';

// 로컬 BE의 실제 장비 응답(EXT01·02·03·04·05, 관계 R1)을 FE의 엄격한 검사기로 읽는다(합성 데이터, 2026-11-08 수집).
// BE 응답 모양이 계약과 어긋나면 이 시험이 먼저 실패한다.
describe('BE 장비 운영 실제 응답', () => {
    it('장비 목록(EXT01)과 다음 쪽 cursor를 읽는다', () => {
        expect(() => checkV9Shape('V9EquipmentAssetPage', samples.assetPage)).not.toThrow();
        const page = parseAssetPage(samples.assetPage);
        expect(page.items.map((a) => a.name)).toEqual(['무선 마이크 1', '무선 수신기 A']);
        // 시뮬레이션 가정 근거는 확인되지 않은 것으로 남는다.
        expect(page.items[0].stateEvidence.confirmed).toBe(false);
        const first = parseAssetPage(samples.assetPageFirst);
        expect(first.items).toHaveLength(1);
        expect(first.nextCursor).not.toBeNull();
    });

    it('장비 상세(EXT03)에 상태 기록(EXT04) 결과와 예약이 함께 보인다', () => {
        expect(() => checkV9Shape('V9EquipmentDetail', samples.detail)).not.toThrow();
        const detail = parseDetail(samples.detail);
        expect(detail.asset.technicalState).toBe('VERIFIED_USABLE');
        expect(detail.reservations).toHaveLength(1);
    });

    it('예약 시간표(EXT05)와 등록 영수증(EXT02)을 읽는다', () => {
        expect(() =>
            checkV9Shape('V9EquipmentReservationPage', samples.reservationPage),
        ).not.toThrow();
        expect(parseReservationPage(samples.reservationPage).items[0].status).toBe('PLANNED');
        expect(parseReceipt(samples.receipt).revision).toBe('1');
    });

    it('장비 관계 문맥(R1)과 추가/해제 영수증을 읽는다', () => {
        expect(() => checkV9Shape('FinalEquipmentContext', samples.context)).not.toThrow();
        expect(samples.context.connections).toHaveLength(1);
        expect(samples.context.requirements).toHaveLength(1);
        expect(samples.context.dependencies).toHaveLength(1);
        expect(parseReceipt(samples.connectionReceipt).revision).toBe('1');
        // 선후 관계는 revision이 없어 영수증 revision이 공연 revision이다.
        const dependency = parseReceipt(samples.dependencyReceipt);
        expect(dependency.revision).toBe(dependency.eventRevision);
        expect(() =>
            checkV9Shape('FinalRelationDeleteReceipt', samples.releaseReceipt),
        ).not.toThrow();
    });
});
