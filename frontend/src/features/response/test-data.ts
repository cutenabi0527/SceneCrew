// 단위·브라우저 시험이 함께 쓰는 합성 응답. 실제 공연·사람 정보가 아니다.
import fixtures from './v9-changeset-fixtures.json';

export const eventId = '88acd0b1-dae5-5a8d-b2fe-6f38cbdf8588';
export const issueId = '5e7a9c50-3a41-5c9e-9d0c-6b2f1a0e4c11';
export const reportId = '7c1d2e3f-4a5b-5c6d-8e7f-90a1b2c3d4e5';
export const zoneId = '9faafc2f-df0e-5f44-9f7b-713c7fc84a61';
export const groupId = '18fab083-260f-51b1-86b6-dcb94d8e965f';
export const authorId = '322a1e11-0d1a-5939-8080-7784aa5929a0';
export const ackId = '2b3c4d5e-6f70-5182-93a4-b5c6d7e8f901';
export const analysisJobId = '0a1b2c3d-4e5f-5061-8273-8495a6b7c8d9';
export const changeJobId = '1b2c3d4e-5f60-5172-8384-95a6b7c8d9e0';
export const taskId = '6d7e8f90-a1b2-53c4-95d6-e7f8091a2b3c';

export const report = (patch: Record<string, unknown> = {}) => ({
    id: reportId,
    issueId,
    authorMemberId: authorId,
    text: '메인 무선 마이크 소리가 끊깁니다',
    audioId: null,
    kind: 'INITIAL',
    participationRevision: '1',
    ...patch,
});

export const issue = (patch: Record<string, unknown> = {}) => ({
    id: issueId,
    eventId,
    title: '메인 무선 마이크 끊김',
    status: 'RECEIVED',
    zoneId,
    workGroupId: groupId,
    taskId: null,
    relatedIssueId: null,
    reports: [report()],
    analysis: {
        analysisValidity: 'CURRENT',
        category: '장비 및 시스템 오류',
        reportedState: '소리 끊김',
        zoneCandidates: [],
    },
    eventRevision: '3',
    ...patch,
});

export const analysisJob = (patch: Record<string, unknown> = {}) => ({
    jobId: analysisJobId,
    kind: 'ANALYZE_REPORT',
    state: 'SUCCEEDED',
    attempt: '0',
    result: issue().analysis,
    error: null,
    ...patch,
});

/** AI 변경 생성 결과. 변경안은 !128 예시의 ADD_TASK 변경안이다. */
export const changeSetFixture = fixtures.cases[0];
export const changeJob = (patch: Record<string, unknown> = {}) => ({
    jobId: changeJobId,
    kind: 'GENERATE_CHANGE',
    state: 'SUCCEEDED',
    attempt: '0',
    result: {
        changeSetId: changeSetFixture.responses[0].id,
        operations: changeSetFixture.responses[0].operations,
        rationale: '예비 인원을 무대 왼쪽에 배치합니다',
        questions: [],
        evidenceRefs: [],
        simulationRefs: [],
        situation: null,
    },
    error: null,
    ...patch,
});

export const ack = (patch: Record<string, unknown> = {}) => ({
    id: ackId,
    participationRevision: '1',
    state: 'PENDING',
    targetsHash: 'hash-1',
    targets: [
        {
            taskId,
            title: '무대 왼쪽 마이크 교체',
            changeKinds: ['TIME', 'ZONE'],
            removed: false,
            changedAt: '2026-11-18T10:05:00Z',
        },
    ],
    acknowledgedAt: null,
    ...patch,
});

/** 미리보기를 마친 변경안. !128 예시에는 diff가 비어 있어 서버 요약 한 줄을 합성해 넣었다. */
export const readyWithDiff = {
    ...changeSetFixture.responses[1],
    diff: [
        {
            operationIndex: 0,
            type: 'ADD_TASK',
            targetId: null,
            summary: '무대 왼쪽에 예비 마이크 교체 업무를 추가합니다',
        },
    ],
};
