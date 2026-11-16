// candidate.3의 GET Job needsInfo 예시. 실제 추론 결과나 운영 데이터가 아니다.
// 제품은 이 파일을 import하지 않고 시험에서만 사용한다.
export const jobId = '6a93f560-90d5-5876-8a1f-36a5bf4f9a73';
export const needsInfo = {
    jobId,
    kind: 'GENERATE_PLAN',
    state: 'SUCCEEDED',
    attempt: '1',
    result: {
        status: 'NEEDS_INFO',
        questions: [{ key: 'zone', field: 'zones', question: '행사 구역을 확인해 주세요.' }],
        plan: null,
    },
    error: null,
};
export const ready = {
    ...needsInfo,
    result: {
        status: 'READY',
        questions: [],
        plan: {
            workGroups: [{ tempId: 'tmp-group', name: '음향', instructions: '' }],
            zones: [{ tempId: 'tmp-zone', name: '무대' }],
            programs: [],
            tasks: [
                {
                    tempId: 'tmp-task',
                    workGroupId: 'tmp-group',
                    bundleKey: 'setup',
                    zoneId: 'tmp-zone',
                    programId: null,
                    title: '마이크 점검',
                    startAt: '2026-11-18T01:00:00Z',
                    endAt: '2026-11-18T01:10:00Z',
                    steps: ['전원을 확인한다'],
                    checklist: [],
                },
            ],
            instructions: [],
        },
    },
};
