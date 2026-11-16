import { describe, expect, it } from 'vitest';
import { seedData } from './seed';
import { viewer } from './selectors';

describe('장비 현황 조회 권한(V9-D13)', () => {
    it('총괄은 조회/관리, 장비 조회 허용 그룹의 팀원은 조회만, 다른 그룹은 볼 수 없다', () => {
        const d = seedData();
        const chief = d.members.find((m) => m.role === 'CHIEF')!;
        const equipmentGroups = new Set(d.groups.filter((g) => g.equipmentView).map((g) => g.id));
        const equipmentWorker = d.members.find(
            (m) => m.role !== 'CHIEF' && m.groupId && equipmentGroups.has(m.groupId),
        )!;
        const otherWorker = d.members.find(
            (m) => m.role !== 'CHIEF' && m.groupId && !equipmentGroups.has(m.groupId),
        )!;

        expect(viewer(d, chief.id)).toMatchObject({
            canViewEquipment: true,
            canManageEquipment: true,
        });
        expect(viewer(d, equipmentWorker.id)).toMatchObject({
            canViewEquipment: true,
            canManageEquipment: false,
        });
        expect(viewer(d, otherWorker.id)).toMatchObject({
            canViewEquipment: false,
            canManageEquipment: false,
        });
    });

    it('그룹 표시가 꺼지면 같은 팀원도 볼 수 없다', () => {
        const d = seedData();
        d.groups = d.groups.map((g) => ({ ...g, equipmentView: false }));
        const worker = d.members.find((m) => m.role !== 'CHIEF' && m.groupId)!;
        expect(viewer(d, worker.id).canViewEquipment).toBe(false);
    });
});
