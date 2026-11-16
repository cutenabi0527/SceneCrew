import { describe, expect, it } from 'vitest';
import { enterPath } from './enter-path';

const n = '10000000-0000-4000-8000-000000000051';

describe('공연을 연 뒤 갈 화면(#307)', () => {
    it('푸시로 열면 그 알림을 열 알림함으로, 형식이 다른 id는 알림함 목록으로 간다', () => {
        expect(enterPath('notice', n)).toBe(`/e/notices?open=${n}`);
        expect(enterPath('notice', '../account')).toBe('/e/notices');
        expect(enterPath('notice', null)).toBe('/e/notices');
    });
    it('계획 입력과 기본 화면은 그대로다', () => {
        expect(enterPath('plan-draft', null)).toBe('/e/plan/draft');
        expect(enterPath(null, null)).toBe('/e/home');
        expect(enterPath('other', n)).toBe('/e/home');
    });
    it('계획 입력의 하위 탭은 PDF·직접 입력만 넘긴다(V9-D65)', () => {
        expect(enterPath('plan-draft', null, 'pdf')).toBe('/e/plan/draft?tab=pdf');
        expect(enterPath('plan-draft', null, 'direct')).toBe('/e/plan/draft?tab=direct');
        expect(enterPath('plan-draft', null, '../x')).toBe('/e/plan/draft');
        expect(enterPath(null, null, 'pdf')).toBe('/e/home');
    });
});
