import { describe, expect, it } from 'vitest';
import { reducer, type Action } from './reducer';
import { initialState } from './seed';
import { changeStaleReasons, viewer, zoneReading } from './selectors';
import type { DemoState } from './types';

const live = (): DemoState => ({ ...initialState(), me: 'm1', eventId: 'e1' });
const proposed = () => reducer(live(), { type: 'aiChange', issueId: 'I5', id: 'review' });

describe('운영 화면 리뷰 회귀', () => {
    it.each([
        ['', '18:43'],
        ['18:33', ''],
        ['25:00', '26:00'],
        ['18:60', '19:00'],
        ['잘못된 시각', '19:00'],
        ['18:43', '18:33'],
        ['18:33', '18:33'],
    ])('잘못된 시각 %s–%s는 편집과 저장된 변경안 승인에서 모두 거절한다', (start, end) => {
        const s = proposed();
        const c = s.data.changes.find((x) => x.id === 'review')!;
        const ops = structuredClone(c.ops);
        const op = ops.find((o) => o.kind === 'update' && o.taskId === 'T21')!;
        if (op.kind !== 'update') throw new Error('업무 수정 예시 필요');
        op.after = { ...op.after, start, end };
        expect(reducer(s, { type: 'editChange', id: c.id, ops })).toBe(s);

        // 이전 탭에 저장된 잘못된 값도 새 검사에서 적용하지 않는다.
        const old = structuredClone(s);
        old.data.changes.find((x) => x.id === c.id)!.ops = ops;
        delete old.data.changes.find((x) => x.id === c.id)!.previewHash;
        expect(reducer(old, { type: 'applyChange', id: c.id })).toBe(old);
        expect(old.data.tasks.find((t) => t.id === 'T21')!.start).toBe('18:30');
    });

    it('진행 중인 담당 업무의 부서와 상관없이 소속 이동을 거절한다', () => {
        const s = live();
        const t = s.data.tasks.find((x) => x.id === 'T09')!;
        t.groupId = 'g4';
        expect(t.status).toBe('doing');
        expect(reducer(s, { type: 'memberManage', memberId: 'm2', patch: { groupId: 'g1' } })).toBe(
            s,
        );
    });

    it('소속 이동은 대기 업무 담당만 해제하고 최초 배정 시각과 완료/취소 기록을 보존한다', () => {
        const s = live();
        const waiting = s.data.tasks.find((t) => t.id === 'T32')!;
        const done = s.data.tasks.find((t) => t.id === 'T31')!;
        const canceled = {
            ...structuredClone(waiting),
            id: 'canceled',
            status: 'canceled' as const,
        };
        s.data.tasks.push(canceled);
        const next = reducer(s, { type: 'memberManage', memberId: 'm7', patch: { groupId: 'g1' } });
        expect(next.data.members.find((m) => m.id === 'm7')!.groupId).toBe('g1');
        expect(next.data.tasks.find((t) => t.id === waiting.id)).toMatchObject({
            assignee: null,
            firstAssignedAt: waiting.firstAssignedAt,
            status: 'todo',
        });
        expect(next.data.tasks.find((t) => t.id === done.id)).toEqual(done);
        expect(next.data.tasks.find((t) => t.id === canceled.id)).toEqual(canceled);
        expect(next.events[0].revision).toBe(s.events[0].revision + 1);
        expect(next.data.log.at(-1)?.type).toBe('인원');
    });

    it.each<Action>([
        { type: 'cameraInvalidate', cameraId: 'CAM-1', reason: '이동' },
        { type: 'cameraActive', cameraId: 'CAM-1', active: false },
        { type: 'cameraLink', cameraId: 'CAM-1', link: 'DISCONNECTED' },
    ])('카메라 근거가 무효면 다시 확인을 눌러도 지원안을 적용하지 않는다: $type', (action) => {
        let s = reducer(live(), {
            type: 'safetyChange',
            zoneId: 'z-a',
            memberId: 'm10',
            id: 'support',
        });
        s = reducer(s, action);
        expect(zoneReading(s.data, 'z-a').usable).toBe(false);
        s = reducer(s, { type: 'rebaseChange', id: 'support' });
        const c = s.data.changes.find((x) => x.id === 'support')!;
        expect(changeStaleReasons(s.data, s.events[0], c).length).toBeGreaterThan(0);
        expect(reducer(s, { type: 'applyChange', id: c.id })).toBe(s);
    });

    it('보정 확인 뒤 첫 최신 분석을 받으면 다시 검토하고 지원안을 적용할 수 있다', () => {
        let s = reducer(live(), {
            type: 'safetyChange',
            zoneId: 'z-a',
            memberId: 'm10',
            id: 'support',
        });
        s = reducer(s, { type: 'cameraInvalidate', cameraId: 'CAM-1', reason: '이동' });
        s = reducer(s, { type: 'cameraCalibrate', cameraId: 'CAM-1' });
        expect(reducer(s, { type: 'applyChange', id: 'support' })).toBe(s);
        s = structuredClone(s);
        const obs = s.data.observations.find((o) => o.zoneId === 'z-a')!;
        obs.fresh = 'FRESH';
        obs.at = '18:26:00';
        s = reducer(s, { type: 'rebaseChange', id: 'support' });
        s = reducer(s, { type: 'applyChange', id: 'support' });
        expect(s.data.changes.find((c) => c.id === 'support')!.status).toBe('APPLIED');
    });

    it('구역 추가/삭제/추가를 반복해도 구역 ID는 고유하고 기존 구역 ID는 유지한다', () => {
        let s = live();
        const zones = s.data.safetyZones.map(({ id, name, rect }) => ({ id, name, rect }));
        s = reducer(s, {
            type: 'setFloorPlan',
            zones: [...zones, { id: null, name: '새 구역 A', rect: [10, 10, 10, 10] }],
        });
        const first = s.data.safetyZones.map(({ id, name, rect }) => ({ id, name, rect }));
        const a = first.find((z) => z.name === '새 구역 A')!;
        s = reducer(s, {
            type: 'setFloorPlan',
            zones: [...first.slice(1), { id: null, name: '새 구역 B', rect: [20, 20, 10, 10] }],
        });
        expect(new Set(s.data.safetyZones.map((z) => z.id)).size).toBe(s.data.safetyZones.length);
        expect(s.data.safetyZones.find((z) => z.name === a.name)!.id).toBe(a.id);
        expect(s.data.safetyZones.find((z) => z.name === '새 구역 B')!.id).not.toBe(a.id);
    });

    it('후속 보고 작성자는 담당 해제 후에도 자기 보고를 보고 타인 원문/분석은 보지 않는다', () => {
        const s = live();
        const i = s.data.issues.find((x) => x.id === 'I6')!;
        expect(viewer(s.data, 'm2').canViewIssue(i)).toBe(false);
        i.reports.push({
            kind: 'FOLLOWUP',
            by: 'm2',
            at: '18:26',
            via: '텍스트',
            text: '내 후속 보고',
        });
        const v = viewer(s.data, 'm2');
        expect(v.canViewIssue(i)).toBe(true);
        expect(i.reports.filter(v.canViewReport).map((r) => r.text)).toEqual(['내 후속 보고']);
        expect(v.canViewIssueAnalysis(i)).toBe(false);
        expect(i.reports.filter(viewer(s.data, 'm1').canViewReport)).toHaveLength(2);
    });
});
