import { useEffect, useState } from 'react';
import { classifyFailure, type Failure } from '../../shared/api/failure';
import type { Result } from './client';

// 결과 안내 문구와 Job 상태 조회. 서버 오류 원문(details)은 화면에 그대로 내보내지 않는다.

const TARGET_LABEL: Record<string, string> = {
    event: '공연 정보가',
    changeSet: '변경안이',
    reservation: '장비 예약이',
    equipment: '장비 상태가',
};

const CODE_TEXT: Record<string, string> = {
    EQUIPMENT_UNAVAILABLE:
        '선택한 장비를 지금 쓸 수 없습니다. 장비 상태를 확인하고 다른 대응안을 만들어주세요.',
    EQUIPMENT_PORT_OCCUPIED: '연결하려는 장비 포트가 이미 사용 중입니다. 장비 연결을 확인해주세요.',
    RECOVERY_RESULT_EXPIRED: '복구 계산 결과가 만료되었습니다. 복구 계산을 새로 요청해주세요.',
    INVALID_STATE: '지금 상태에서는 할 수 없는 요청입니다. 현재 상태를 다시 조회해주세요.',
    INVALID_REFERENCE: '참조한 구역/업무/인원이 현재와 맞지 않습니다. 최신 계획을 확인해주세요.',
    TIME_OVERLAP: '시간이 겹치는 업무가 있습니다. 변경 내용을 확인해주세요.',
    STALE_CONTEXT: '분석하는 동안 공연 정보가 바뀌었습니다. 최신 상태에서 다시 요청해주세요.',
};

// 409 INVALID_STATE의 details.reason별 안내(안전 인력 지원, #361). 접두어로 묶인 사유는 reasonText에서 고른다.
const REASON_TEXT: Record<string, string> = {
    SUPPORT_MINIMUM_CREW:
        '원래 구역의 최소 유지 인원이 부족해 보낼 수 없습니다. 고른 인원을 줄여주세요.',
    SUPPORT_CREW_RULE_UNAVAILABLE:
        '원래 구역의 최소 인원 기준이 없거나 지원 시간 범위를 쓸 수 없습니다. 최소 기준과 지원 시간을 확인해주세요.',
    SUPPORT_CANDIDATE_CHANGED:
        '고른 인원의 참여 회차나 원래 배치가 바뀌었습니다. 지원 후보를 다시 조회해 다시 골라주세요.',
    SUPPORT_CANDIDATE_DUPLICATE: '같은 인원을 두 번 골랐습니다. 고른 인원을 확인해주세요.',
    SUPPORT_WINDOW_OR_ZONE:
        '지원 시간이나 구역이 맞지 않습니다. 종료 시각과 원래 구역·지원할 구역을 확인해주세요.',
    SUPPORT_WINDOW_NOT_CURRENT:
        '지원 시간은 지금 시각을 포함해야 합니다. 시작과 종료 시각을 다시 정해주세요.',
    SUPPORT_TEST_OBSERVATION:
        '시험용 카메라 관측은 지원 근거로 쓸 수 없습니다. 실제 관측과 지원 후보를 다시 조회해주세요.',
    SUPPORT_OPERATIONS_CHANGED:
        '지원안 내용이 지원 선택과 달라졌습니다. 지원 후보를 다시 조회해 지원안을 새로 만들어주세요.',
    SUPPORT_ALREADY_IN_PROGRESS:
        '이미 지원 업무를 진행 중인 인원이 있습니다. 지원 후보를 다시 조회해 다시 골라주세요.',
};
const SUPPORT_OBSERVATION_TEXT =
    '이 관측은 더 이상 지원 근거로 쓸 수 없습니다. 최신 관측과 지원 후보를 다시 조회해주세요.';

function reasonText(reason: string | undefined) {
    if (!reason) return null;
    if (reason.startsWith('SUPPORT_OBSERVATION_')) return SUPPORT_OBSERVATION_TEXT;
    return REASON_TEXT[reason] ?? null;
}

export function failureText(f: Failure, action: string) {
    const byReason = reasonText(f.reason);
    if (byReason) return byReason;
    if (f.code && CODE_TEXT[f.code]) return CODE_TEXT[f.code];
    switch (f.kind) {
        case 'cancelled':
            return '';
        case 'access-denied':
            return `${action}을(를) 할 수 있는 권한이 없습니다. 참여 정보를 다시 확인해주세요.`;
        case 'unauthenticated':
            return '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.';
        case 'rate-limited':
            return '요청이 많습니다. 잠시 뒤 다시 시도해주세요.';
        case 'unavailable':
            return '서버가 잠시 응답하지 않습니다. 같은 내용으로 다시 보낼 수 있습니다.';
        case 'request-in-progress':
            return '같은 요청을 처리하고 있습니다. 잠시 뒤 같은 내용으로 다시 확인해주세요.';
        default:
            return `${action}을(를) 처리하지 못했습니다.${f.code ? ` (${f.code})` : ''}`;
    }
}

/** 명령 결과의 안내 문구. 성공·Job 접수는 호출한 화면이 따로 안내한다. */
export function resultText(result: Result<unknown>, action: string) {
    switch (result.kind) {
        case 'done':
        case 'job':
            return '';
        case 'retry':
            return result.failure.kind === 'unknown-outcome'
                ? `${action} 응답을 받지 못했습니다. 같은 내용으로 다시 보낼 수 있습니다.`
                : failureText(result.failure, action);
        case 'check':
            return `${action} 결과를 확인하지 못했습니다. 현재 상태를 다시 조회했습니다. 같은 요청을 자동으로 다시 보내지 않습니다.`;
        case 'conflict':
            return `${TARGET_LABEL[result.target ?? ''] ?? '공연 정보가'} 그사이 바뀌었습니다. 최신 상태를 다시 조회했으니 내용을 확인한 뒤 진행해주세요.`;
        case 'rejected':
            return failureText(result.failure, action);
    }
}

export function readText(error: unknown) {
    const f = classifyFailure(error);
    if (f.kind === 'cancelled') return '';
    if (f.kind === 'access-denied')
        return '볼 수 있는 범위가 아닙니다. 참여 정보를 다시 확인해주세요.';
    if (f.kind === 'unauthenticated') return '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.';
    return '현재 상태를 조회하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.';
}

type Job = { state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' };

/** 진행 중인 Job만 반복 조회한다. 끝나거나 화면이 닫히면 멈춘다. 조회 실패는 다시 조회를 누르게 한다. */
export function useJob<T extends Job>(
    jobId: string | null,
    read: (jobId: string, signal: AbortSignal) => Promise<T>,
    intervalMs = 2000,
) {
    const [state, setState] = useState<{ id: string; job: T | null; error: string } | null>(null);
    const [again, setAgain] = useState(0);
    useEffect(() => {
        if (!jobId) return;
        const abort = new AbortController();
        let timer: number | undefined;
        const tick = () => {
            read(jobId, abort.signal)
                .then((job) => {
                    setState({ id: jobId, job, error: '' });
                    if (job.state === 'QUEUED' || job.state === 'RUNNING')
                        timer = window.setTimeout(tick, intervalMs);
                })
                .catch((error: unknown) => {
                    const text = readText(error);
                    if (text)
                        setState((prev) => ({ id: jobId, job: prev?.job ?? null, error: text }));
                });
        };
        tick();
        return () => {
            abort.abort();
            window.clearTimeout(timer);
        };
    }, [jobId, read, intervalMs, again]);
    const current = state?.id === jobId ? state : null;
    return {
        job: current?.job ?? null,
        error: current?.error ?? '',
        retry: () => setAgain((n) => n + 1),
    };
}

export const JOB_LABEL = {
    QUEUED: '접수됨',
    RUNNING: '처리 중',
    SUCCEEDED: '완료',
    FAILED: '실패',
} as const;
