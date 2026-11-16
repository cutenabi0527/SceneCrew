import { describe, expect, it } from 'vitest';
import { qualityReasonText, qualityReasonsText } from './quality';

describe('관측 품질 사유 문구', () => {
    it('AI·BE 코드를 한국어 문구로 바꾼다', () => {
        expect(qualityReasonText('LOW_LIGHT')).toBe('어두움');
        expect(qualityReasonText('OUT_OF_VIEW')).toBe('구역이 화면 밖');
        expect(qualityReasonText('PARTIAL_COVERAGE')).toBe('구역 일부만 관측');
        expect(qualityReasonText('TEST_VALUE')).toBe('시험값(실제 인원 아님)');
    });

    it('모르는 코드는 코드를 보이지 않고, 코드가 아닌 글은 그대로 둔다', () => {
        expect(qualityReasonText('NEW_REASON_2')).toBe('기타 관측 품질 문제');
        expect(qualityReasonText('역광')).toBe('역광');
        expect(qualityReasonText('constructor')).toBe('constructor');
    });

    it('같은 뜻의 코드는 한 번만 쓴다', () => {
        expect(qualityReasonsText(['UNDETERMINED_SCOPE', 'COVERAGE_UNDETERMINED', 'BLURRED'])).toBe(
            '관측 범위 판정 불가 / 화면 흐림',
        );
    });
});
