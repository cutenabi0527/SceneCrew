import { describe, expect, it } from 'vitest';
import type { Draft } from './contract';
import { sameZoneNames, syncZones } from './zone-sync';

const draft: Draft = {
    workGroups: [{ id: 'g1', name: '음향', instructions: '' }],
    zones: [
        { id: 'z1', name: '무대', description: '무대 설명' },
        { id: 'z2', name: '객석', description: null },
    ],
    programs: [{ id: 'p1', name: '1부', zoneId: 'z2', startAt: '', endAt: '' }],
    tasks: [
        {
            id: 't1',
            workGroupId: 'g1',
            zoneId: 'z2',
            programId: null,
            bundleKey: '',
            title: '점검',
            startAt: '',
            endAt: '',
            steps: [],
            checklist: [],
        },
    ],
    instructions: [],
};

describe('평면도 구역 맞추기', () => {
    it('같은 이름은 id·설명을 잇고 새 이름은 새 구역, 사라진 구역의 참조는 옮긴다', () => {
        let n = 0;
        const next = syncZones(draft, ['구역 A', '무대'], () => `tmp-${++n}`);
        expect(next.zones).toEqual([
            { tempId: 'tmp-1', name: '구역 A', description: null },
            { id: 'z1', name: '무대', description: '무대 설명' },
        ]);
        expect(next.programs[0].zoneId).toBeNull();
        expect(next.tasks[0].zoneId).toBe('tmp-1');
        expect(sameZoneNames(next, ['구역 A', '무대'])).toBe(true);
        expect(sameZoneNames(draft, ['구역 A', '무대'])).toBe(false);
    });
});
