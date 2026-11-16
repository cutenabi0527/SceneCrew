import { describe, expect, it } from 'vitest';
import { cameraViewable } from '../../domain/selectors';
import { zonesFromCondition } from './extraction-fields';

describe('문서 추출 구역을 계획 구역으로(#401 운영 확인)', () => {
    it('쉼표로 나누고 빈 이름·같은 이름은 빼며 10개까지만 쓴다', () => {
        expect(zonesFromCondition('A구역 무대 앞, B구역 객석 뒤쪽')).toEqual([
            'A구역 무대 앞',
            'B구역 객석 뒤쪽',
        ]);
        expect(zonesFromCondition(' A , ,A、B，C ')).toEqual(['A', 'B', 'C']);
        expect(zonesFromCondition(undefined)).toEqual([]);
        const many = Array.from({ length: 12 }, (_, i) => `구역${i}`).join(', ');
        expect(zonesFromCondition(many)).toHaveLength(10);
    });
});

describe('카메라 현재 화면을 여는 공연 상태(#401 운영 확인)', () => {
    it('참여 모집과 운영 중에 열고, 작성 중·종료 뒤에는 닫는다', () => {
        expect(cameraViewable('RECRUITING')).toBe(true);
        expect(cameraViewable('LIVE')).toBe(true);
        expect(cameraViewable('DRAFT')).toBe(false);
        expect(cameraViewable('ENDED')).toBe(false);
    });
});
