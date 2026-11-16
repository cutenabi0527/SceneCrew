import { describe, expect, it } from 'vitest';
import { issueGroupOf } from './selectors';
import type { EventData, Issue } from './types';

const issue = (patch: Partial<Issue>): Issue => ({
    id: 'I1',
    title: '마이크 끊김',
    summary: '',
    zone: '스테이지',
    status: 'RECEIVED',
    at: '10:00',
    reporter: 'm2',
    tasks: [],
    reports: [],
    ...patch,
});

const d = {
    tasks: [{ id: 'T1', groupId: 'sound' }],
    members: [{ id: 'm2', groupId: 'light' }],
} as unknown as EventData;

describe('이슈의 직무 그룹(#381 리뷰)', () => {
    it('이슈 그룹 → 연결 업무의 그룹 → 보고자의 그룹 순으로 정하고, 구역 이름은 쓰지 않는다', () => {
        expect(issueGroupOf(d, issue({ groupId: 'stage', tasks: ['T1'] }))).toBe('stage');
        expect(issueGroupOf(d, issue({ tasks: ['T1'] }))).toBe('sound');
        expect(issueGroupOf(d, issue({}))).toBe('light');
        expect(issueGroupOf(d, issue({ reporter: 'none' }))).toBeUndefined();
    });
});
