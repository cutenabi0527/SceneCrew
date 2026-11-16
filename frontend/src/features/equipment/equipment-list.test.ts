import { describe, expect, it } from 'vitest';
import { parseEquipmentList } from './equipment-list';

describe('장비 자료 읽기(D01)', () => {
    it('CSV 머리글을 건너뛰고 이름/종류/위치를 읽는다', () => {
        expect(parseEquipmentList('이름,종류,위치\n마이크 D,무선 마이크,음향 부스\n')).toEqual([
            { name: '마이크 D', kind: '무선 마이크', place: '음향 부스', error: undefined },
        ]);
    });
    it('MD 표와 위치 없는 행을 읽고, 빠진 필수 값은 표시한다', () => {
        const rows = parseEquipmentList(
            '| 이름 | 종류 | 위치 |\n|---|---|---|\n| 믹서 B | 믹서 |  |\n| | 케이블 | 창고 |',
        );
        expect(rows[0]).toMatchObject({ name: '믹서 B', kind: '믹서', place: '미확인' });
        expect(rows[1].error).toBe('이름 없음');
    });
});
