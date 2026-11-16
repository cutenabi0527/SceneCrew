import { describe, expect, it } from 'vitest';
import { ContractViolationError } from '../../shared/api/contract';
import { assets, micDetail, reservations } from '../../../tests/fixtures/equipment-data';
import { parseAssetPage, parseDetail, parseReservationPage } from './live-contract';

const clone = <T>(v: T): T => structuredClone(v);

describe('장비 현황 응답 검사', () => {
    it('초안 형식의 목록/예약/상세를 읽는다', () => {
        const page = parseAssetPage({ items: assets, nextCursor: null });
        expect(page.items.map((a) => a.technicalState)).toEqual([
            'VERIFIED_USABLE',
            'UNUSABLE',
            'CHECK_REQUIRED',
        ]);
        expect(page.items[1].stateEvidence.sourceKind).toBe('AUTOMATIC_MEASUREMENT');
        expect(page.items[0].stateEvidence.observedAt).toBeNull();

        const r = parseReservationPage({ items: reservations, nextCursor: 'next-1' });
        expect(r.nextCursor).toBe('next-1');
        expect(r.items[0].status).toBe('ACTIVE');

        const d = parseDetail(micDetail);
        expect(d.connections[0].toPort).toBe('ch-1');
        expect(d.requirements[0]).toMatchObject({ quantity: 1, scheduleMode: 'FIXED' });
        expect(d.eventRevision).toBe('3');
    });

    it('자동 관측(#398)은 있을 때만 읽고, 모르는 값은 null로 두며 정해지지 않은 상태는 거절한다', () => {
        const withAuto = {
            ...clone(assets[0]),
            autoObservation: {
                kind: 'QLXD4',
                inputKind: null,
                state: 'UNLINKED',
                transmitterOn: null,
                batteryBars: null,
                rfLevel: null,
                audioLevel: null,
                lastReceivedAt: null,
            },
        };
        const page = parseAssetPage({ items: [withAuto, assets[1]], nextCursor: null });
        expect(page.items[0].autoObservation).toEqual(withAuto.autoObservation);
        expect(page.items[1].autoObservation).toBeUndefined();
        const bad = clone(withAuto);
        bad.autoObservation.state = 'OK';
        expect(() => parseAssetPage({ items: [bad], nextCursor: null })).toThrow();
    });

    it('목록 끝은 JSON null만 인정한다', () => {
        for (const nextCursor of ['', 'null', 0, undefined]) {
            const body: Record<string, unknown> = { items: [] };
            if (nextCursor !== undefined) body.nextCursor = nextCursor;
            expect(() => parseAssetPage(body)).toThrow(ContractViolationError);
        }
    });

    it('모르는 기술 상태를 정상으로 바꾸지 않고 거절한다', () => {
        const bad = clone(assets[0]);
        bad.technicalState = 'OK';
        expect(() => parseAssetPage({ items: [bad], nextCursor: null })).toThrow(/technicalState/);
    });

    it('확인된 호환 조합은 필수이며 형식을 검사한다(#327 fixture와 같은 판정)', () => {
        const ok = clone(assets[0]) as Record<string, unknown>;
        ok.verifiedCompatibility = [
            {
                assetIds: [assets[0].id],
                portKeys: ['out-1'],
                componentAssetIds: [],
                evidence: clone(assets[0].stateEvidence),
            },
        ];
        const page = parseAssetPage({ items: [ok], nextCursor: null });
        expect(page.items[0].verifiedCompatibility[0].portKeys).toEqual(['out-1']);

        const missing = clone(assets[0]) as Record<string, unknown>;
        delete missing.verifiedCompatibility;
        expect(() => parseAssetPage({ items: [missing], nextCursor: null })).toThrow(
            /verifiedCompatibility/,
        );
        const empty = clone(ok);
        (empty.verifiedCompatibility as { assetIds: string[] }[])[0].assetIds = [];
        expect(() => parseAssetPage({ items: [empty], nextCursor: null })).toThrow(/assetIds/);
    });

    it('관측 시각은 null을 허용하지만 필드 생략은 거절한다', () => {
        const bad = clone(assets[0]) as Record<string, unknown>;
        const ev = { ...(bad.stateEvidence as Record<string, unknown>) };
        delete ev.observedAt;
        bad.stateEvidence = ev;
        expect(() => parseAssetPage({ items: [bad], nextCursor: null })).toThrow(/observedAt/);
    });

    it('revision 숫자와 끝이 시작보다 빠른 예약을 거절한다', () => {
        const numeric = clone(assets[0]) as Record<string, unknown>;
        numeric.revision = 1;
        expect(() => parseAssetPage({ items: [numeric], nextCursor: null })).toThrow(/revision/);

        const reversed = clone(reservations[0]);
        reversed.endAt = reversed.startAt;
        expect(() => parseReservationPage({ items: [reversed], nextCursor: null })).toThrow(
            /endAt/,
        );
    });

    it('필요 수량이 1 미만이면 거절한다', () => {
        const bad = clone(micDetail);
        bad.requirements[0].requirements[0].quantity = 0;
        expect(() => parseDetail(bad)).toThrow(/quantity/);
    });
});
