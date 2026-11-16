// 복구 계산(#308) 시험용 합성 응답. 실제 장비·공연 정보가 아니다.
// !128에는 v9-327 형식의 계산 결과 예시가 없어, v9-320 예시를 만료 시각 필수 형식에 맞추고
// 실행 가능한 후보(장비 예약 연산·미리보기)를 더해 만들었다.

export const simulationId = '30000000-0000-4000-8000-000000000001';
export const recoveryJobId = '30000000-0000-4000-8000-000000000002';
export const spareAssetId = '30000000-0000-4000-8000-000000000003';
const reservationId = '30000000-0000-4000-8000-000000000004';
const memberId = '30000000-0000-4000-8000-000000000005';
const inputHash = 'a'.repeat(64);

const evidence = (assetId: string) => ({
    kind: 'EquipmentAsset',
    id: assetId,
    revision: '1',
    contentHash: null,
    observedAt: '2026-11-18T01:00:00Z',
    preconditions: ['현재 상태를 승인 직전에 재확인'],
});

/** 실행 가능한 후보: 예비 마이크를 10분간 예약한다. */
export const feasible = (eventId: string, assetId = spareAssetId) => {
    const reservation = {
        assetIds: [assetId],
        programId: null,
        taskId: null,
        startAt: '2026-11-18T01:05:00Z',
        endAt: '2026-11-18T01:15:00Z',
    };
    return {
        candidateId: 'spare-mic',
        status: 'FEASIBLE',
        reasons: ['예비 무선 마이크가 사용 가능 확인 상태입니다'],
        evidenceRefs: [evidence(assetId)],
        equipmentIds: [assetId],
        componentIds: [],
        steps: [],
        occupancy: [reservation],
        readyAt: '2026-11-18T01:08:00Z',
        requiredReadyAt: '2026-11-18T01:10:00Z',
        slackSec: 120,
        shortfallSec: null,
        programImpacts: [],
        conflicts: [],
        operations: [
            {
                type: 'RESERVE_EQUIPMENT',
                payload: { reservation, evidenceRefs: [evidence(assetId)] },
            },
        ],
        preview: [
            {
                operationIndex: 0,
                type: 'RESERVE_EQUIPMENT',
                targetId: null,
                summary: '예비 무선 마이크를 01:05~01:15에 예약합니다',
                before: null,
                after: {
                    id: reservationId,
                    eventId,
                    ...reservation,
                    status: 'PLANNED',
                    sourceChangeSetId: null,
                    revision: '1',
                },
                evidenceRefs: [evidence(assetId)],
            },
        ],
    };
};

/** 판단할 수 없는 후보(!128 v9-320 예시와 같은 내용) */
export const undetermined = (assetId: string) => ({
    candidateId: 'candidate-fixture-1',
    status: 'UNDETERMINED',
    reasons: ['설치/재시험 시간과 호환 확인 필요'],
    evidenceRefs: [evidence(assetId)],
    equipmentIds: [assetId],
    componentIds: [],
    steps: [],
    occupancy: [],
    readyAt: null,
    requiredReadyAt: '2026-11-18T01:10:00Z',
    slackSec: null,
    shortfallSec: null,
    programImpacts: [],
    conflicts: [],
    operations: [],
    preview: [],
});

export const simulation = (
    eventId: string,
    targetAssetId: string,
    issueId: string | null,
    patch: Record<string, unknown> = {},
) => ({
    id: simulationId,
    eventId,
    issueId,
    targetAssetIds: [targetAssetId],
    createdByMemberId: memberId,
    baseRevision: '1',
    inputHash,
    evidenceRefs: [evidence(targetAssetId)],
    policyVersion: 'fixture-policy',
    assumptions: [
        { key: 'swap-time', description: '교체에 3분이 걸린다고 가정', confirmed: false },
    ],
    calculatedAt: '2026-11-18T01:01:00Z',
    state: 'READY',
    candidates: [feasible(eventId), undetermined(targetAssetId)],
    expiresAt: '2026-11-25T01:01:00Z',
    ...patch,
});

export const recoveryJob = (state: string) => ({
    jobId: recoveryJobId,
    kind: 'SIMULATE_RECOVERY',
    state,
    attempt: '0',
    result:
        state === 'SUCCEEDED'
            ? {
                  simulationId,
                  calculatedAt: '2026-11-18T01:01:00Z',
                  expiresAt: '2026-11-25T01:01:00Z',
              }
            : null,
    error: null,
});

export const pointer = (expired = false) => ({
    simulationId,
    calculatedAt: '2026-11-18T01:01:00Z',
    expiresAt: '2026-11-25T01:01:00Z',
    expired,
});
