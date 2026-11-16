import { describe, expect, it } from 'vitest';
import { reducer, type Action } from './reducer';
import { initialState } from './seed';
import {
    assignRefusal,
    familyAssignRefusal,
    taskFamily,
    changeStaleReasons,
    cueSections,
    affectedOf,
    diffRows,
    formatDelay,
    restoreRefusal,
    responseQueues,
    supportCandidates,
    unassignedBundles,
    viewer,
    zoneReading,
} from './selectors';
import type { DemoState } from './types';

const live = (me = 'm1') =>
    reducer(reducer(initialState(), { type: 'login', me }), {
        type: 'enterEvent',
        eventId: 'e1',
    });
const run = (s: DemoState, ...actions: Action[]) => actions.reduce(reducer, s);

describe('데모 시나리오(18:22 메인 마이크 무음) / 기획 v9.0 정책', () => {
    it('총괄이 AI 대응안을 확정하면 revision이 오르고 예비 인원에게 투입 확인이 간다', () => {
        let s = reducer(live(), { type: 'aiChange', issueId: 'I5', id: 'C9' });
        const c = s.data.changes.find((x) => x.id === 'C9')!;
        expect(c.status).toBe('READY');
        expect(diffRows(s.data, s.events[0], c).map((r) => r.code)).toEqual([
            'P2',
            'T21',
            'T22',
            'T09',
            'T40',
        ]);

        const before = s.events[0].revision;
        s = reducer(s, { type: 'applyChange', id: 'C9' });
        expect(s.events[0].revision).toBe(before + 1);
        expect(s.data.changes.find((x) => x.id === 'C9')?.appliedAt).toBe('18:25');
        expect(s.events[0].programs.find((p) => p.id === 'P2')).toMatchObject({
            start: '18:33',
            end: '18:43',
        });
        const t40 = s.data.tasks.find((t) => t.id === 'T40')!;
        expect(t40.assignee).toBe('m4');
        expect(t40.firstAssignedAt).toBeDefined();
        const deploy = s.data.notices.find((n) => n.to === 'm4' && n.kind === 'deploy');
        expect(deploy?.ack).toBe(false);

        s = reducer({ ...s, me: 'm4' }, { type: 'ackNotice', id: deploy!.id });
        expect(s.data.tasks.filter((t) => t.assignee === 'm4').every((t) => t.ack)).toBe(true);
    });

    it('팀원은 변경을 확정할 수 없고, 낡은 변경안은 다시 확인하기 전까지 적용되지 않는다', () => {
        let s = reducer(live(), { type: 'aiChange', issueId: 'I5', id: 'C9' });
        expect(reducer({ ...s, me: 'm3' }, { type: 'applyChange', id: 'C9' })).toEqual({
            ...s,
            me: 'm3',
        });

        // 다른 변경이 먼저 적용되면 revision이 달라져 C9는 낡는다
        s = reducer(s, { type: 'applyChange', id: 'C0' });
        const stale = reducer(s, { type: 'applyChange', id: 'C9' });
        expect(stale.data.changes.find((x) => x.id === 'C9')?.status).toBe('READY');

        s = run(s, { type: 'rebaseChange', id: 'C9' }, { type: 'applyChange', id: 'C9' });
        expect(s.data.changes.find((x) => x.id === 'C9')?.status).toBe('APPLIED');
    });

    it('변경 비교는 바뀐 시간/담당/절차를 구분하고 같은 값은 숨긴다', () => {
        const s = reducer(live(), { type: 'aiChange', issueId: 'I5', id: 'C9' });
        const c = s.data.changes.find((x) => x.id === 'C9')!;
        const rows = diffRows(s.data, s.events[0], c);
        expect(rows[0].fields.map((f) => f.label)).toEqual(['시간']);
        expect(rows.find((r) => r.code === 'T40')?.fields.map((f) => f.label)).toEqual([
            '시간',
            '담당',
            '구역',
            '절차',
        ]);
        const add = rows.find((r) => r.code === 'T40')!;
        expect(add.fields.find((f) => f.label === '절차')?.after).toContain('마이크');
        for (const row of rows.filter((r) => r.kind === 'update')) {
            expect(row.fields.every((f) => f.before !== f.after)).toBe(true);
        }
        const update = c.ops.find((o) => o.kind === 'update')!;
        if (update.kind !== 'update') throw new Error('업무 수정 예시 필요');
        expect(
            diffRows(s.data, s.events[0], {
                ...c,
                ops: [{ ...update, after: { ...update.before } }],
            })[0].fields,
        ).toEqual([]);
    });

    it('투입 뒤 가용을 꺼도 기존 업무와 본인 공식 확인 대상은 유지한다', () => {
        let s = run(
            live(),
            { type: 'aiChange', issueId: 'I5', id: 'C9' },
            { type: 'applyChange', id: 'C9' },
        );
        const tasks = s.data.tasks.filter((t) => t.assignee === 'm4');
        const notices = s.data.notices.filter((n) => n.to === 'm4');
        expect(tasks.length).toBeGreaterThan(0);
        s = reducer({ ...s, me: 'm4' }, { type: 'setAvailable', available: false });
        expect(s.data.members.find((m) => m.id === 'm4')?.available).toBe(false);
        expect(s.data.tasks.filter((t) => t.assignee === 'm4')).toEqual(tasks);
        expect(s.data.notices.filter((n) => n.to === 'm4')).toEqual(notices);
    });

    it('현장 보고는 총괄에게만 알림이 가고, 팀원은 같은 그룹이어도 남의 업무 상세를 못 본다', () => {
        const s = reducer(live('m6'), {
            type: 'report',
            input: {
                id: 'I9',
                taskId: 'T24',
                issueId: null,
                zone: '스테이지',
                text: '마이크 스탠드가 부족합니다',
                via: '빠른 보고',
                kind: 'INITIAL',
            },
        });
        expect(responseQueues(s.data, 'm1').news.map((i) => i.id)).toContain('I9');
        expect(s.data.notices.filter((n) => n.issue === 'I9').map((n) => n.to)).toEqual(['m1']);
        const m6 = viewer(s.data, 'm6');
        expect(m6.canViewTask(s.data.tasks.find((t) => t.id === 'T21')!)).toBe(false);
        expect(responseQueues(s.data, 'm6').approve).toEqual([]);
    });

    it('그룹을 골라 참여하면 AI가 미배정 묶음 전체를 한 사람에게 처음 배정한다', () => {
        let s = run(live('a9'), { type: 'join', groupId: 'g4', reserve: false, available: true });
        expect(s.data.members.find((m) => m.id === 'a9')?.assign).toBe('WAITING');
        s = run(s, { type: 'assignRun', memberId: 'a9' }, { type: 'assignResult', memberId: 'a9' });
        const mine = s.data.tasks.filter((t) => t.assignee === 'a9').map((t) => t.id);
        expect(mine).toEqual(['T26', 'T27']);
        expect(s.data.members.find((m) => m.id === 'a9')?.assign).toBe('DONE');
        expect(unassignedBundles(s.data, 'g4')).toEqual([]);
        // 최초 배정 알림은 공식 변경 확인 대상이 아니다
        expect(s.data.notices.find((n) => n.to === 'a9')).toMatchObject({
            kind: 'assign',
            ack: true,
        });
    });

    it('맡을 묶음이 없으면 참여는 유지한 채 후보 없음이 되고, 예비 참여는 자동 배정하지 않는다', () => {
        let s = run(
            live('a9'),
            { type: 'join', groupId: 'g3', reserve: false, available: true },
            { type: 'assignRun', memberId: 'a9' },
            { type: 'assignResult', memberId: 'a9' },
        );
        expect(s.data.members.find((m) => m.id === 'a9')?.assign).toBe('NO_CANDIDATE');

        s = run(live('a9'), { type: 'join', groupId: 'g4', reserve: true, available: true });
        expect(s.data.members.find((m) => m.id === 'a9')).toMatchObject({
            isReserve: true,
            assign: undefined,
        });
    });
});

describe('사용자 정보 수정', () => {
    const profile = (name: string) => ({ name, avail: '13:00–18:00', skills: '', wait: '' });
    const member = (s: DemoState, id: string) => s.data.members.find((m) => m.id === id)!;

    it('총괄은 이름을 바로 바꾼다', () => {
        const s = reducer(live('m1'), { type: 'updateProfile', profile: profile('김총괄') });
        expect(member(s, 'm1').name).toBe('김총괄');
        expect(member(s, 'm1').nameRequest).toBeUndefined();
    });

    it('팀원의 이름 변경은 총괄 승인 뒤에만 반영되고, 반려하면 그대로 남는다', () => {
        let s = reducer(live('m2'), { type: 'updateProfile', profile: profile('박민수2') });
        const before = member(live('m2'), 'm2').name;
        expect(member(s, 'm2')).toMatchObject({ name: before, nameRequest: '박민수2' });
        expect(s.data.notices.some((n) => n.to === 'm1' && n.kind === 'profile')).toBe(true);

        // 팀원은 스스로 승인할 수 없다
        s = reducer(s, { type: 'nameDecision', memberId: 'm2', approve: true });
        expect(member(s, 'm2').name).toBe(before);

        const rejected = run(
            s,
            { type: 'persona', me: 'm1' },
            { type: 'nameDecision', memberId: 'm2', approve: false },
        );
        expect(member(rejected, 'm2')).toMatchObject({ name: before });
        expect(member(rejected, 'm2').nameRequest).toBeUndefined();

        const approved = run(
            s,
            { type: 'persona', me: 'm1' },
            { type: 'nameDecision', memberId: 'm2', approve: true },
        );
        expect(member(approved, 'm2').name).toBe('박민수2');
        expect(approved.data.notices.some((n) => n.to === 'm2' && n.kind === 'profile')).toBe(true);
    });
});

describe('v9 장비/안전/상황 유형', () => {
    it('복구 대안은 변경안만 만들고, 승인 뒤에야 예약과 장비 업무가 생긴다', () => {
        let s = reducer(live(), { type: 'recoveryChange', optionId: 'RC2', id: 'C8' });
        const before = s.data.reservations.length;
        expect(s.data.changes.find((c) => c.id === 'C8')?.status).toBe('READY');
        expect(s.data.tasks.some((t) => t.title === '예비 마이크 C 세트 전환')).toBe(false);

        s = reducer(s, { type: 'applyChange', id: 'C8' });
        // C 세트 3건 + 메인 마이크 A 2곡 1건이 생기고, A의 1곡 준비·1·2곡(R1·R2)은 해제된다(2곡 뒤 반납은 그대로)
        expect(s.data.reservations.length).toBe(before + 4 - 2);
        expect(s.data.reservations.some((r) => r.id === 'R1' || r.id === 'R2')).toBe(false);
        expect(
            s.data.reservations.filter((r) => r.assetId === 'MIC-A').map((r) => r.label),
        ).toEqual(['반납', '2곡(복구 확인 후)']);
        const t = s.data.tasks.find((x) => x.title === '예비 마이크 C 세트 전환')!;
        expect(t).toMatchObject({ assignee: 'm4', equipment: { assets: ['MIC-C'], records: {} } });
        // 보고자에게 본인 보고 처리 알림이 간다
        expect(s.data.notices.some((n) => n.to === 'm3' && n.issue === 'I5')).toBe(true);
    });

    it('불가/판단 불가 대안으로는 변경안을 만들 수 없고 팀원은 만들 수 없다', () => {
        expect(
            reducer(live(), { type: 'recoveryChange', optionId: 'RC3', id: 'C8' }).data.changes,
        ).toHaveLength(1);
        expect(
            reducer(live(), { type: 'recoveryChange', optionId: 'RC4', id: 'C8' }).data.changes,
        ).toHaveLength(1);
        expect(
            reducer(live('m6'), { type: 'recoveryChange', optionId: 'RC2', id: 'C8' }).data.changes,
        ).toHaveLength(1);
    });

    it('장비 인수→설치→재시험은 순서대로 한 번씩만 기록된다', () => {
        let s = run(
            live(),
            { type: 'recoveryChange', optionId: 'RC2', id: 'C8' },
            { type: 'applyChange', id: 'C8' },
        );
        const id = s.data.tasks.find((x) => x.title === '예비 마이크 C 세트 전환')!.id;
        s = { ...s, me: 'm4' };
        s = reducer(s, { type: 'equipmentRecord', taskId: id, step: 'retest' });
        expect(s.data.tasks.find((x) => x.id === id)!.equipment!.records).toEqual({});
        s = run(
            s,
            { type: 'equipmentRecord', taskId: id, step: 'pickup' },
            { type: 'equipmentRecord', taskId: id, step: 'install' },
            { type: 'equipmentRecord', taskId: id, step: 'retest' },
        );
        expect(Object.keys(s.data.tasks.find((x) => x.id === id)!.equipment!.records)).toEqual([
            'pickup',
            'install',
            'retest',
        ]);
        // 다른 사람은 기록할 수 없다
        const other = reducer(
            { ...live(), me: 'm6' },
            { type: 'equipmentRecord', taskId: 'T24', step: 'install' },
        );
        expect(other.data.tasks.find((x) => x.id === 'T24')!.equipment!.records.install).toBe(
            '18:25',
        );
    });

    it('면적/기준이 있는 구역만 밀집도를 계산하고, 부분/낡은 관측은 확인 필요로 둔다', () => {
        const d = live().data;
        expect(zoneReading(d, 'z-a')).toMatchObject({ density: 2.25, state: 'OVER' });
        expect(zoneReading(d, 'z-c')).toMatchObject({ density: null, state: 'CHECK' });
        expect(zoneReading(d, 'z-in')).toMatchObject({ usable: false, state: 'CHECK' });
    });

    it('안전 지원 후보는 원 구역 최소 유지/필수 업무를 검사하고 승인 뒤에만 업무가 생긴다', () => {
        const d = live().data;
        const c = supportCandidates(d, 'z-a');
        expect(c.find((x) => x.member.id === 'm2')?.verdict).toBe('NO');
        expect(c.find((x) => x.member.id === 'm8')?.verdict).toBe('NO');
        expect(c.filter((x) => x.verdict === 'OK').map((x) => x.member.id)).toEqual(['m9', 'm10']);

        let s = reducer(live(), { type: 'safetyChange', zoneId: 'z-a', memberId: 'm2', id: 'C7' });
        expect(s.data.changes).toHaveLength(1);
        s = run(
            live(),
            { type: 'safetyChange', zoneId: 'z-a', memberId: 'm10', id: 'C7' },
            { type: 'applyChange', id: 'C7' },
        );
        const t = s.data.tasks.find((x) => x.title === '객석 A 안전 지원')!;
        expect(t).toMatchObject({ assignee: 'm10', safety: { zoneId: 'z-a' } });
        s = run(
            { ...s, me: 'm10' },
            { type: 'safetyRecord', taskId: t.id, step: 'check' },
            { type: 'safetyRecord', taskId: t.id, step: 'arrive' },
        );
        expect(s.data.tasks.find((x) => x.id === t.id)!.safety!.records).toEqual({
            arrive: '18:25',
        });
    });

    it('총괄만 상황 유형을 정정하고 대표 유형은 연관 목록에서 뺀다', () => {
        const set = {
            type: 'setSituation',
            issueId: 'I6',
            primary: 'AUDIENCE_SAFETY',
            related: ['AUDIENCE_SAFETY', 'STAFF_GAP'],
        } as const;
        const s = reducer(live(), { ...set, related: [...set.related] });
        expect(s.data.issues.find((i) => i.id === 'I6')!.situation).toMatchObject({
            primary: 'AUDIENCE_SAFETY',
            related: ['STAFF_GAP'],
            source: '총괄 확인',
        });
        const w = reducer(live('m5'), { ...set, related: [] });
        expect(w.data.issues.find((i) => i.id === 'I6')!.situation?.source).toBe('모델 추천');
    });
    it('안전 지원 변경안은 근거 관측이 갱신되면 이유와 함께 승인을 막고, 다시 확인한 뒤에만 적용한다', () => {
        let s = reducer(live(), { type: 'safetyChange', zoneId: 'z-a', memberId: 'm10', id: 'C7' });
        const obs = s.data.observations.find((o) => o.zoneId === 'z-a')!;
        s = {
            ...s,
            data: {
                ...s.data,
                observations: [
                    { ...obs, at: '18:25:30' },
                    ...s.data.observations.filter((o) => o !== obs),
                ],
            },
        };
        const c = s.data.changes.find((x) => x.id === 'C7')!;
        expect(changeStaleReasons(s.data, s.events[0], c)[0]).toContain('관측이 갱신');
        const blocked = reducer(s, { type: 'applyChange', id: 'C7' });
        expect(blocked.data.changes.find((x) => x.id === 'C7')!.status).toBe('READY');
        s = run(s, { type: 'rebaseChange', id: 'C7' }, { type: 'applyChange', id: 'C7' });
        expect(s.data.changes.find((x) => x.id === 'C7')!.status).toBe('APPLIED');
    });

    it('변경 확인은 대상 지문을 남기고, 다시 확인해도 이전 확인 기록을 지우지 않는다', () => {
        let s = run(
            live(),
            { type: 'aiChange', issueId: 'I5', id: 'C9' },
            { type: 'applyChange', id: 'C9' },
        );
        const n = s.data.notices.find((x) => x.change === 'C9' && x.kind !== 'report')!;
        expect(n.targetIds?.length).toBeGreaterThan(0);
        expect(n.targetHash).toMatch(/^[0-9a-f]{8}$/);
        s = run(
            { ...s, me: n.to },
            { type: 'ackNotice', id: n.id },
            { type: 'ackNotice', id: n.id },
        );
        expect(s.data.notices.find((x) => x.id === n.id)!.acks).toHaveLength(2);
        // 다른 사람의 확인 요청은 확인할 수 없다
        const other = reducer({ ...s, me: 'm1' }, { type: 'ackNotice', id: n.id });
        expect(other.data.notices.find((x) => x.id === n.id)!.acks).toHaveLength(2);
    });

    it('종료된 행사에서는 새 보고/수행/변경 적용/카메라 조작을 막는다', () => {
        const ended = reducer(live(), { type: 'endEvent' });
        expect(ended.events[0].state).toBe('ENDED');
        const after = run(
            ended,
            {
                type: 'report',
                input: {
                    id: 'I99',
                    taskId: null,
                    issueId: null,
                    zone: '입구',
                    text: '종료 뒤 보고',
                    via: '텍스트',
                    kind: 'INITIAL',
                },
            },
            { type: 'cameraCalibrate', cameraId: 'CAM-1' },
            { type: 'aiChange', issueId: 'I5', id: 'C9' },
        );
        expect(after.data).toBe(ended.data);
    });

    it('카메라 보정 무효/단절은 구역 추정을 현재 값으로 쓰지 않고, 보정 확인 뒤 분석 대기가 된다', () => {
        let s = reducer(live(), { type: 'cameraInvalidate', cameraId: 'CAM-1', reason: '줌 변경' });
        expect(zoneReading(s.data, 'z-a')).toMatchObject({ usable: false, state: 'CHECK' });
        s = reducer(s, { type: 'cameraCalibrate', cameraId: 'CAM-1' });
        expect(s.data.cameras.find((c) => c.id === 'CAM-1')!.calibrationInvalid).toBeUndefined();
        expect(s.data.observations.find((o) => o.zoneId === 'z-a')!.fresh).toBe('PENDING');
        // 팀원은 카메라를 조작할 수 없다
        const worker = { ...s, me: 'm2' };
        expect(reducer(worker, { type: 'cameraActive', cameraId: 'CAM-1', active: false })).toBe(
            worker,
        );
    });

    it('가용/예비 변경은 소속과 이미 맡은 업무를 그대로 둔다', () => {
        const before = live();
        const tasks = before.data.tasks.filter((t) => t.assignee === 'm6').map((t) => t.id);
        const s = reducer(before, {
            type: 'memberManage',
            memberId: 'm6',
            patch: { available: false, isReserve: true },
        });
        const m = s.data.members.find((x) => x.id === 'm6')!;
        expect(m).toMatchObject({ available: false, isReserve: true, groupId: 'g4' });
        expect(s.data.tasks.filter((t) => t.assignee === 'm6').map((t) => t.id)).toEqual(tasks);
        expect(s.events[0].revision).toBe(before.events[0].revision + 1);
        expect(
            reducer(
                { ...before, me: 'm2' },
                { type: 'memberManage', memberId: 'm6', patch: { available: false } },
            ),
        ).toEqual({ ...before, me: 'm2' });
    });

    it('큐시트는 곡 순서대로 묶고 실제 시각 차이를 초 단위로 남긴다', () => {
        const s = live();
        const secs = cueSections(s.events[0], s.data.tasks);
        expect(secs.filter((x) => x.kind === 'program').map((x) => x.key)).toEqual([
            'P1',
            'P2',
            'P3',
            'P4',
        ]);
        expect(formatDelay(40)).toBe('+40초');
        expect(formatDelay(80)).toBe('+1분 20초');
        expect(formatDelay(-30)).toBe('-30초');
    });
    it('목록 배정: 부서로 옮기며 미배정으로 두거나 같은 부서 팀원에게 배정하고, 변경안/알림을 남긴다', () => {
        const before = live();
        // T26(음향·장비, 미배정) → 무대영상 부서 미배정
        let s = reducer(before, {
            type: 'reassignTask',
            id: 'C30',
            taskId: 'T26',
            groupId: 'g3',
            assignee: null,
        });
        expect(s.data.tasks.find((t) => t.id === 'T26')).toMatchObject({
            groupId: 'g3',
            assignee: null,
        });
        expect(s.data.changes.find((c) => c.id === 'C30')).toMatchObject({
            status: 'APPLIED',
            mode: 'MANUAL',
        });
        expect(s.events[0].revision).toBe(before.events[0].revision + 1);
        // 같은 부서 팀원에게 배정하면 그 사람에게 변경 확인 알림이 간다
        s = reducer(s, {
            type: 'reassignTask',
            id: 'C31',
            taskId: 'T26',
            groupId: 'g3',
            assignee: 'm7',
        });
        expect(s.data.tasks.find((t) => t.id === 'T26')!.assignee).toBe('m7');
        expect(s.data.notices.some((n) => n.change === 'C31' && n.to === 'm7' && !n.ack)).toBe(
            true,
        );
        // 다른 부서 사람·완료 업무·팀원 요청은 거절한다
        const t = (x: typeof s) => x.data.tasks.find((y) => y.id === 'T26')!.assignee;
        expect(
            t(
                reducer(s, {
                    type: 'reassignTask',
                    id: 'C32',
                    taskId: 'T26',
                    groupId: 'g3',
                    assignee: 'm3',
                }),
            ),
        ).toBe('m7');
        expect(
            reducer(s, {
                type: 'reassignTask',
                id: 'C33',
                taskId: 'T23',
                groupId: 'g3',
                assignee: null,
            }),
        ).toBe(s);
        expect(
            reducer(
                { ...s, me: 'm2' },
                { type: 'reassignTask', id: 'C34', taskId: 'T26', groupId: 'g4', assignee: null },
            ).data,
        ).toBe(s.data);
    });
    it('큐시트 배정: 두 부서에 모두 배정하면 원 업무는 두고 새 부서에 같은 업무를 만들고, 여러 명이면 한 건씩 받는다', () => {
        const before = live();
        const titleOf = before.data.tasks.find((t) => t.id === 'T26')!.title;
        // 두 부서 모두: T26(음향·장비, 미배정)은 그대로, 무대영상 m7이 같은 업무를 새로 받는다
        let s = reducer(before, {
            type: 'assignTask',
            id: 'C40',
            taskId: 'T26',
            targets: [{ groupId: 'g3', people: ['m7'] }],
            keep: true,
        });
        expect(s.data.tasks.find((t) => t.id === 'T26')).toMatchObject({
            groupId: 'g4',
            assignee: null,
        });
        expect(
            s.data.tasks.some(
                (t) =>
                    t.id !== 'T26' &&
                    t.title === titleOf &&
                    t.groupId === 'g3' &&
                    t.assignee === 'm7',
            ),
        ).toBe(true);
        expect(s.data.changes.find((c) => c.id === 'C40')).toMatchObject({ status: 'APPLIED' });
        // 같은 부서 두 명: 첫 사람이 원 업무를, 두 번째 사람이 같은 업무 한 건을 받는다
        s = reducer(s, {
            type: 'assignTask',
            id: 'C41',
            taskId: 'T26',
            targets: [{ groupId: 'g4', people: ['m3', 'm6'] }],
            keep: false,
        });
        expect(s.data.tasks.find((t) => t.id === 'T26')!.assignee).toBe('m3');
        expect(
            s.data.tasks.some(
                (t) =>
                    t.id !== 'T26' &&
                    t.title === titleOf &&
                    t.groupId === 'g4' &&
                    t.assignee === 'm6',
            ),
        ).toBe(true);
        // 그 부서 사람이 아니거나 같은 사람을 두 번 고르면 거절한다
        expect(
            reducer(s, {
                type: 'assignTask',
                id: 'C42',
                taskId: 'T26',
                targets: [{ groupId: 'g3', people: ['m3'] }],
                keep: false,
            }),
        ).toBe(s);
        expect(
            reducer(s, {
                type: 'assignTask',
                id: 'C43',
                taskId: 'T26',
                targets: [
                    { groupId: 'g4', people: ['m6'] },
                    { groupId: 'g4', people: ['m6'] },
                ],
                keep: false,
            }),
        ).toBe(s);
    });
    it('총괄은 모든 이슈를, 팀원은 본인 관련 이슈와 명시로 전달받은 요약만 본다', () => {
        const s0 = live();
        const ids = (s: typeof s0, me: string) =>
            s.data.issues.filter(viewer(s.data, me).canViewIssue).map((i) => i.id);
        expect(ids(s0, 'm1')).toEqual(s0.data.issues.map((i) => i.id));
        // I6은 m5 보고·T11 이슈다. 부서가 같은 m2도 자기 업무·보고가 아니면 못 본다
        expect(ids(s0, 'm2')).not.toContain('I6');
        expect(ids(s0, 'm3')).not.toContain('I6');
        expect(ids(s0, 'm7')).not.toContain('I6');

        // 부서 전체 전달: 무대영상(m7)이 보고 알림을 받는다
        let s = reducer(s0, {
            type: 'forwardIssue',
            issueId: 'I6',
            groupId: 'g3',
            memberId: null,
            note: '확인 부탁',
        });
        expect(ids(s, 'm7')).toContain('I6');
        expect(s.data.notices.some((n) => n.to === 'm7' && n.issue === 'I6')).toBe(true);

        // 특정 인물 전달: 음향·장비 m6에게만. 같은 부서 m3는 여전히 못 본다
        s = reducer(s, {
            type: 'forwardIssue',
            issueId: 'I6',
            groupId: 'g4',
            memberId: 'm6',
            note: '',
        });
        expect(ids(s, 'm6')).toContain('I6');
        expect(ids(s, 'm3')).not.toContain('I6');

        // @태그만으로도 전달한다
        s = reducer(s, {
            type: 'forwardIssue',
            issueId: 'I6',
            groupId: null,
            memberId: null,
            note: '@이지우 통로 확인',
        });
        expect(ids(s, 'm3')).toContain('I6');
        expect(s.data.issues.find((i) => i.id === 'I6')!.forwards!.at(-1)!.mentions).toEqual([
            'm3',
        ]);

        // 팀원은 전달할 수 없고, 다른 부서 사람을 그 부서 이름으로 고를 수 없다
        expect(
            reducer(
                { ...s0, me: 'm2' },
                { type: 'forwardIssue', issueId: 'I6', groupId: 'g3', memberId: null, note: '' },
            ).data,
        ).toBe(s0.data);
        expect(
            reducer(s0, {
                type: 'forwardIssue',
                issueId: 'I6',
                groupId: 'g3',
                memberId: 'm6',
                note: '',
            }),
        ).toBe(s0);
    });
    it('확정 전 변경안은 총괄이 변경 후 시간/담당을 고칠 수 있고, 고친 내용으로 확정된다', () => {
        let s = reducer(live(), { type: 'aiChange', issueId: 'I5', id: 'C9' });
        const c = s.data.changes.find((x) => x.id === 'C9')!;
        const k = c.ops.findIndex((o) => o.kind === 'update' && o.taskId === 'T21');
        const ops = structuredClone(c.ops);
        const o = ops[k];
        if (o.kind !== 'update') throw new Error('update op expected');
        o.after = { ...o.after, start: '18:34', end: '18:44' };
        s = reducer(s, { type: 'editChange', id: 'C9', ops });
        const edited = s.data.changes.find((x) => x.id === 'C9')!;
        expect(edited.previewHash).not.toBe(c.previewHash);
        // 거꾸로 된 시간·팀원 요청은 무시한다
        const badOps = structuredClone(edited.ops);
        const b = badOps[k];
        if (b.kind === 'update') b.after = { ...b.after, start: '19:00', end: '18:00' };
        expect(reducer(s, { type: 'editChange', id: 'C9', ops: badOps })).toBe(s);
        expect(reducer({ ...s, me: 'm2' }, { type: 'editChange', id: 'C9', ops })).toEqual({
            ...s,
            me: 'm2',
        });
        s = reducer(s, { type: 'applyChange', id: 'C9' });
        expect(s.data.tasks.find((t) => t.id === 'T21')).toMatchObject({
            start: '18:34',
            end: '18:44',
        });
    });
    it('변경안 편집: 확인 필요 사항/절차를 고치고 담당을 더하면 확정 때 사람마다 업무를 받는다', () => {
        let s = reducer(live(), { type: 'aiChange', issueId: 'I5', id: 'C9' });
        const c = s.data.changes.find((x) => x.id === 'C9')!;
        const k = c.ops.findIndex((o) => o.kind === 'update' && o.taskId === 'T21');
        const o0 = c.ops[k];
        if (o0.kind !== 'update') throw new Error('update op expected');
        const extra = s.data.members.find(
            (m) => m.role === 'WORKER' && m.available && m.id !== o0.after.assignee,
        )!;
        const oldSteps = s.data.tasks.find((t) => t.id === 'T21')!.steps;
        const ops = structuredClone(c.ops);
        const o = ops[k];
        if (o.kind !== 'update') throw new Error('update op expected');
        o.after = { ...o.after, steps: ['첫 단계', '    하위 단계'], extraAssignees: [extra.id] };
        s = reducer(s, { type: 'editChange', id: 'C9', ops, checks: '확인 1\n확인 2' });
        const edited = s.data.changes.find((x) => x.id === 'C9')!;
        expect(edited.checks).toBe('확인 1\n확인 2');
        expect(edited.previewHash).not.toBe(c.previewHash);
        expect(affectedOf(edited)).toContain(extra.id);
        const row = diffRows(
            s.data,
            s.events.find((e) => e.id === s.eventId)!,
            edited,
        )[k];
        expect(row.people).toContain(extra.id);
        expect(row.detail.find((f) => f.label === '절차')?.after).toBe(
            '- 첫 단계\n    - 하위 단계',
        );
        // 팀원이 아니거나 겹치는 추가 담당은 무시한다
        const dup = structuredClone(edited.ops);
        const n = dup[k];
        if (n.kind === 'update') n.after = { ...n.after, extraAssignees: [n.after.assignee!] };
        expect(reducer(s, { type: 'editChange', id: 'C9', ops: dup })).toBe(s);
        s = reducer(s, { type: 'applyChange', id: 'C9' });
        const tasks = s.data.tasks.filter((t) => t.steps.includes('    하위 단계'));
        expect(tasks.map((t) => t.assignee)).toEqual([o0.after.assignee, extra.id]);
        expect(tasks[1]).toMatchObject({ status: 'todo', ack: false });
        expect(s.data.notices.some((x) => x.to === extra.id && x.change === 'C9')).toBe(true);
        // 확정 뒤에도 바꾸기 전 절차를 비교할 수 있다
        const applied = s.data.changes.find((x) => x.id === 'C9')!;
        const done = diffRows(
            s.data,
            s.events.find((e) => e.id === s.eventId)!,
            applied,
        )[k];
        expect(done.detail.find((f) => f.label === '절차')?.before).toBe(
            oldSteps.map((x) => `- ${x}`).join('\n') || '없음',
        );
    });
    it('평면도/구역 저장: 기존 구역은 이름/영역만 바꾸고, 새 상자는 새 구역, 빠진 구역은 지운다', () => {
        const s0 = live();
        const plan = {
            src: 'data:image/jpeg;base64,AA',
            width: 800,
            height: 600,
            name: 'hall.png',
        };
        const s = reducer(s0, {
            type: 'setFloorPlan',
            plan,
            zones: [
                { id: 'z-a', name: '1층 객석', rect: [10, 20, 40, 30] },
                { id: null, name: '2층 발코니', rect: [55, 20, 40, 30] },
            ],
        });
        expect(s.data.floorPlan).toEqual(plan);
        expect(s.data.safetyZones.map((z) => z.name)).toEqual(['1층 객석', '2층 발코니']);
        // 이어받은 구역은 면적·기준을 유지하고, 지운 구역은 카메라 관측 구역에서도 빠진다
        expect(s.data.safetyZones[0]).toMatchObject({ id: 'z-a', area: 8, limit: 2 });
        expect(s.data.safetyZones[1]).toMatchObject({ area: null, limit: null, minStaff: 0 });
        expect(s.data.cameras.find((c) => c.id === 'CAM-1')!.zones).toEqual(['z-a']);
        // 같은 이름·빈 이름·범위 밖·팀원 요청은 거절한다
        const bad = (
            zones: { id: string | null; name: string; rect: [number, number, number, number] }[],
        ) => reducer(s0, { type: 'setFloorPlan', zones });
        expect(
            bad([
                { id: null, name: 'A', rect: [0, 0, 10, 10] },
                { id: null, name: 'A', rect: [20, 0, 10, 10] },
            ]),
        ).toBe(s0);
        expect(bad([{ id: null, name: ' ', rect: [0, 0, 10, 10] }])).toBe(s0);
        expect(bad([{ id: null, name: 'B', rect: [95, 0, 10, 10] }])).toBe(s0);
        const worker = { ...s0, me: 'm2' };
        expect(reducer(worker, { type: 'setFloorPlan', zones: [] })).toBe(worker);
    });
});

describe('큐시트 배정: 총괄이 맡은 업무', () => {
    it('총괄이 맡은 업무에 팀원을 더하면 총괄은 그대로 두고 팀원에게 같은 업무를 한 건씩 만든다', () => {
        const before = live();
        const s = reducer(before, {
            type: 'assignTask',
            id: 'C60',
            taskId: 'T01',
            targets: [
                { groupId: 'g1', people: ['m1'] },
                { groupId: 'g2', people: ['m2', 'm5'] },
            ],
            keep: false,
        });
        expect(s.data.changes.find((c) => c.id === 'C60')?.status).toBe('APPLIED');
        expect(s.data.tasks.find((t) => t.id === 'T01')?.assignee).toBe('m1');
        const copies = s.data.tasks.filter((t) => t.id !== 'T01' && t.title === '전체 순회');
        expect(copies.map((t) => t.groupId)).toEqual(['g2', 'g2']);
        expect(copies.map((t) => t.assignee).sort()).toEqual(['m2', 'm5']);
    });
    it('지금 담당자가 아닌 총괄은 여전히 배정할 수 없다', () => {
        const before = live();
        const s = reducer(before, {
            type: 'assignTask',
            id: 'C61',
            taskId: 'T09',
            targets: [{ groupId: 'g1', people: ['m1'] }],
            keep: false,
        });
        expect(s).toBe(before);
    });
});

describe('큐시트 배정 거부 이유', () => {
    it('배정할 수 없으면 이유를 알려 주고 reducer도 같은 이유로 거부한다', () => {
        const s = live();
        const d = s.data;
        // 지금 담당자가 아닌 총괄은 새로 배정할 수 없다
        expect(assignRefusal(d, 'T09', [{ groupId: 'g1', people: ['m1'] }], false)).toContain(
            '팀원이 아니어서',
        );
        // 다른 부서 사람
        expect(assignRefusal(d, 'T09', [{ groupId: 'g3', people: ['m2'] }], false)).toContain(
            '소속이 아닙니다',
        );
        // 바뀐 것 없음
        const t = d.tasks.find((x) => x.id === 'T09')!;
        expect(
            assignRefusal(
                d,
                'T09',
                [{ groupId: t.groupId, people: t.assignee ? [t.assignee] : [] }],
                false,
            ),
        ).toContain('바뀐 내용이 없습니다');
        // 받을 수 있는 배정
        expect(
            assignRefusal(d, 'T09', [{ groupId: 'g2', people: ['m2', 'm5'] }], false),
        ).toBeNull();
        expect(
            reducer(s, {
                type: 'assignTask',
                id: 'C62',
                taskId: 'T09',
                targets: [{ groupId: 'g3', people: ['m2'] }],
                keep: false,
            }),
        ).toBe(s);
    });
});

describe('같은 업무(여러 명 복사 배정) 일괄 변경', () => {
    const withCopies = () =>
        reducer(live(), {
            type: 'assignTask',
            id: 'C70',
            taskId: 'T01',
            targets: [
                { groupId: 'g1', people: ['m1'] },
                { groupId: 'g2', people: ['m2', 'm5', 'm9'] },
            ],
            keep: false,
        });
    it('복사본은 원 업무를 기억하고, 배정 변경 기록에 같은 업무를 모두 남긴다', () => {
        const s = withCopies();
        const fam = taskFamily(
            s.data,
            s.data.tasks.find((t) => t.id === 'T01')!,
        );
        expect(fam.map((t) => t.assignee)).toEqual(['m1', 'm2', 'm5', 'm9']);
        expect(fam.slice(1).every((t) => t.copyOf === 'T01')).toBe(true);
        expect(s.data.changes.find((c) => c.id === 'C70')?.related).toHaveLength(4);
    });
    it('모두 변경하면 변경안 하나로 빠진 사람의 업무를 새 사람에게 넘기거나 취소한다', () => {
        const s0 = withCopies();
        const changes = s0.data.changes.length;
        const s = reducer(s0, {
            type: 'assignFamily',
            id: 'C71',
            taskId: 'T01',
            targets: [
                { groupId: 'g1', people: ['m1'] },
                { groupId: 'g2', people: ['m2'] },
                { groupId: 'g4', people: ['m3'] },
            ],
        });
        expect(s.data.changes).toHaveLength(changes + 1);
        const c = s.data.changes.find((x) => x.id === 'C71')!;
        expect(c.status).toBe('APPLIED');
        expect(c.ops.map((o) => o.kind).sort()).toEqual(['cancel', 'update']);
        const fam = taskFamily(
            s.data,
            s.data.tasks.find((t) => t.id === 'T01')!,
        );
        expect(fam.map((t) => t.assignee).sort()).toEqual(['m1', 'm2', 'm3']);
        // 링크: 지금 같은 업무 + 취소된 업무
        expect(c.related).toHaveLength(4);
    });
    it('바뀐 것이 없으면 이유를 알려 주고 변경안을 만들지 않는다', () => {
        const s = withCopies();
        const targets = [
            { groupId: 'g1', people: ['m1'] },
            { groupId: 'g2', people: ['m2', 'm5', 'm9'] },
        ];
        expect(familyAssignRefusal(s.data, 'T01', targets)).toContain('바뀐 내용이 없습니다');
        expect(reducer(s, { type: 'assignFamily', id: 'C72', taskId: 'T01', targets })).toBe(s);
    });
});

describe('같은 업무를 같은 사람에게 두 번 배정하지 않는다', () => {
    const withCopies = () =>
        reducer(live(), {
            type: 'assignTask',
            id: 'C80',
            taskId: 'T09',
            targets: [{ groupId: 'g2', people: ['m2', 'm5'] }],
            keep: false,
        });
    it('복사본에서 여러 명을 고를 때 이미 같은 업무를 맡은 사람은 거부한다', () => {
        const s = withCopies();
        const copy = s.data.tasks.find((t) => t.copyOf === 'T09' && t.assignee === 'm5')!;
        const targets = [{ groupId: 'g2', people: ['m5', 'm2'] }];
        expect(assignRefusal(s.data, copy.id, targets, false)).toContain('이미 같은 업무');
        expect(
            reducer(s, { type: 'assignTask', id: 'C81', taskId: copy.id, targets, keep: false }),
        ).toBe(s);
        // 한 사람만 다른 사람으로 바꾸는 것도, 그 사람이 이미 같은 업무를 맡았으면 거부한다
        expect(
            reducer(s, {
                type: 'reassignTask',
                id: 'C82',
                taskId: copy.id,
                groupId: 'g2',
                assignee: 'm2',
            }),
        ).toBe(s);
    });
    it('모두 변경은 예전에 두 번 배정된 같은 업무를 한 건만 남기고 정리한다', () => {
        const s0 = withCopies();
        // 예전 데이터처럼 같은 사람에게 두 번 배정된 복사본을 만든다
        const dup: DemoState = {
            ...s0,
            data: {
                ...s0.data,
                tasks: [
                    ...s0.data.tasks,
                    {
                        ...s0.data.tasks.find((t) => t.id === 'T09')!,
                        id: 'T09-dup',
                        assignee: 'm5',
                        copyOf: 'T09',
                    },
                ],
            },
        };
        const s = reducer(dup, {
            type: 'assignFamily',
            id: 'C83',
            taskId: 'T09',
            targets: [{ groupId: 'g2', people: ['m2', 'm5'] }],
        });
        const fam = taskFamily(
            s.data,
            s.data.tasks.find((t) => t.id === 'T09')!,
        );
        expect(fam.map((t) => t.assignee).sort()).toEqual(['m2', 'm5']);
    });
});

describe('큐시트 업무 삭제', () => {
    it('삭제는 취소 변경안 하나로 기록되고, 같은 업무 여러 건도 변경안은 하나다', () => {
        const s0 = reducer(live(), {
            type: 'assignTask',
            id: 'C90',
            taskId: 'T09',
            targets: [{ groupId: 'g2', people: ['m2', 'm5'] }],
            keep: false,
        });
        const fam = taskFamily(
            s0.data,
            s0.data.tasks.find((t) => t.id === 'T09')!,
        );
        const changes = s0.data.changes.length;
        const s = reducer(s0, { type: 'deleteTasks', id: 'C91', taskIds: fam.map((t) => t.id) });
        expect(s.data.changes).toHaveLength(changes + 1);
        const c = s.data.changes.find((x) => x.id === 'C91')!;
        expect(c.status).toBe('APPLIED');
        expect(c.ops.every((o) => o.kind === 'cancel')).toBe(true);
        expect(c.related).toHaveLength(2);
        // 복사본은 원 업무와 같은 공연 구간에 놓인다(큐시트에서 원 업무 밑에 모인다)
        const root = s0.data.tasks.find((t) => t.id === 'T09')!;
        expect(fam.every((t) => t.program === root.program)).toBe(true);
        expect(
            fam.every((t) => s.data.tasks.find((x) => x.id === t.id)?.status === 'canceled'),
        ).toBe(true);
        // 이미 취소된 업무는 다시 삭제하지 않는다
        expect(reducer(s, { type: 'deleteTasks', id: 'C92', taskIds: ['T09'] })).toBe(s);
    });
});

describe('삭제된 업무 복원/영구 삭제', () => {
    const deleted = () => reducer(live(), { type: 'deleteTasks', id: 'C95', taskIds: ['T09'] });
    it('복원하면 업무가 돌아오고 담당자에게 다시 확인을 받는다', () => {
        const s0 = deleted();
        const s = reducer(s0, { type: 'restoreTask', taskId: 'T09' });
        const t = s.data.tasks.find((x) => x.id === 'T09')!;
        expect(t.status).toBe('todo');
        expect(t.ack).toBe(false);
        expect(s.events[0].revision).toBe(s0.events[0].revision + 1);
        expect(s.data.notices.some((n) => n.to === t.assignee && n.task === 'T09')).toBe(true);
    });
    it('같은 업무를 이미 맡은 사람의 업무는 복원하지 않는다', () => {
        let s = reducer(live(), {
            type: 'assignTask',
            id: 'C96',
            taskId: 'T09',
            targets: [{ groupId: 'g2', people: ['m2', 'm5'] }],
            keep: false,
        });
        const copy = s.data.tasks.find((t) => t.copyOf === 'T09')!;
        s = reducer(s, { type: 'deleteTasks', id: 'C97', taskIds: [copy.id] });
        // 지운 복사본의 담당자(m5)를 원 업무에 다시 배정해 같은 업무를 맡게 한다
        s = reducer(s, {
            type: 'reassignTask',
            id: 'C98',
            taskId: 'T09',
            groupId: 'g2',
            assignee: 'm5',
        });
        expect(restoreRefusal(s.data, copy.id)).toContain('이미 같은 업무');
        expect(reducer(s, { type: 'restoreTask', taskId: copy.id })).toBe(s);
    });
    it('영구 삭제하면 업무는 사라지고 변경 기록은 이름을 그대로 읽는다', () => {
        const s = reducer(deleted(), { type: 'purgeTask', taskId: 'T09' });
        expect(s.data.tasks.some((t) => t.id === 'T09')).toBe(false);
        const c = s.data.changes.find((x) => x.id === 'C95')!;
        expect(diffRows(s.data, s.events[0], c)[0].target).toBe('관객 입장 안내');
        // 삭제되지 않은 업무는 영구 삭제하지 않는다
        expect(reducer(s, { type: 'purgeTask', taskId: 'T11' })).toBe(s);
    });
});
