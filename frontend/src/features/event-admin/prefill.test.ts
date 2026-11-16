import { describe, expect, it } from 'vitest';
import { splitExtracted } from '../plan-workflow/extraction-fields';
import { prefillEdits } from './prefill';
import { toLocalInput } from './time';

describe('문서 추출 후보 채우기(#362)', () => {
    it('공연 기본정보 키는 기본정보로, 그 밖의 키는 계획 생성 조건으로 가른다', () => {
        expect(
            splitExtracted({
                name: '가을 콘서트',
                locationName: '올림픽홀',
                stageType: '돌출 무대',
            }),
        ).toEqual({
            basics: { name: '가을 콘서트', locationName: '올림픽홀' },
            conditions: { stageType: '돌출 무대' },
        });
    });

    it('시각은 입력 칸 형식으로, 날짜는 앞 10자로 바꾸고 읽을 수 없는 값은 채우지 않는다', () => {
        const openAt = '2026-11-18T10:00:00Z';
        expect(
            prefillEdits({
                name: '가을 콘서트',
                date: '2026-11-18',
                openAt,
                closeAt: '저녁 9시쯤',
                prepStartAt: '',
                totalCapacity: '20',
                unknownKey: '버림',
            }),
        ).toEqual({
            name: '가을 콘서트',
            date: '2026-11-18',
            openAt: toLocalInput(openAt),
            totalCapacity: '20',
        });
        expect(prefillEdits({ date: '11월 18일' })).toEqual({});
    });

    it('시각 칸은 날짜만 있거나 형식이 다른 값을 채우지 않는다(없던 09:00이 생기지 않게, 리뷰)', () => {
        expect(
            prefillEdits({
                openAt: '2026-11-18',
                closeAt: '11/18/2026',
                prepStartAt: '2026. 11. 18. 10:00',
            }),
        ).toEqual({});
        // 시간대가 없으면 이 기기 시각으로 본다
        expect(prefillEdits({ openAt: '2026-11-18 19:30' })).toEqual({
            openAt: '2026-11-18T19:30',
        });
    });

    it('추출기가 시각만 주면 같은 추출의 공연 날짜와 합친다(시연 PDF, !331)', () => {
        expect(
            prefillEdits({
                date: '2026-11-18',
                prepStartAt: '17:30:00',
                openAt: '18:00',
                closeAt: '18:35:00',
                teardownEndAt: '18:50:00',
            }),
        ).toEqual({
            date: '2026-11-18',
            prepStartAt: '2026-11-18T17:30',
            openAt: '2026-11-18T18:00',
            closeAt: '2026-11-18T18:35',
            teardownEndAt: '2026-11-18T18:50',
        });
    });

    it('시각만 있고 날짜가 없거나 읽을 수 없으면 채우지 않는다(날짜를 꾸며 내지 않게)', () => {
        expect(prefillEdits({ prepStartAt: '17:30:00' })).toEqual({});
        expect(prefillEdits({ date: '11월 18일', prepStartAt: '17:30:00' })).toEqual({});
        expect(prefillEdits({ date: '2026-11-18', openAt: '25:00', closeAt: '6시' })).toEqual({
            date: '2026-11-18',
        });
    });
});
