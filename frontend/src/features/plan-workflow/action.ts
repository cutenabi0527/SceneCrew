import { useCallback, useEffect, useRef, useState } from 'react';
import { classifyFailure, type Failure } from '../../shared/api/failure';
import { ContractViolationError } from '../../shared/api/contract';
import { AiRequestUnavailable, CommandNeedsReview, PlanContextChanged } from './client';

/** 결과를 모르는 채 남아 다른 요청을 막는 이전 요청의 화면 이름 */
const OPERATION_LABEL: Record<string, string> = {
    upload: '문서 등록',
    extract: '문서 추출',
    generate: '계획 생성',
    save: '계획 저장',
    input: '조건 저장',
    basics: '기본정보 저장',
    deleteDocuments: '문서 삭제',
};

export function useWorkAction() {
    const [busy, setBusy] = useState(false);
    const [message, setMessageState] = useState('');
    // 실패 안내인가. 화면 위 상태 줄에는 실패만 보이고 성공 안내('…했습니다')는 보이지 않는다
    const [failed, setFailed] = useState(false);
    // 이 함수를 의존하는 이펙트가 다시 돌지 않도록 늘 같은 함수로 둔다
    const setMessage = useCallback((text: string) => {
        setMessageState(text);
        setFailed(false);
    }, []);
    const active = useRef(true),
        pending = useRef(false);
    useEffect(() => {
        active.current = true;
        return () => {
            active.current = false;
        };
    }, []);
    /** explain: 서버가 확정 거절한 실패를 화면 말로 풀어 준다(예: 초안의 칸별 사유, #387). 없으면 기본 안내다. */
    const run = async <T>(
        work: () => Promise<T>,
        accept?: (value: T) => void,
        explain?: (failure: Failure) => string | undefined,
    ) => {
        if (pending.current) return;
        pending.current = true;
        setBusy(true);
        setMessage('');
        try {
            const result = await work();
            if (active.current) accept?.(result);
        } catch (error) {
            if (active.current) {
                setMessageState(workFailureText(error, explain));
                setFailed(true);
            }
        } finally {
            pending.current = false;
            if (active.current) setBusy(false);
        }
    };
    return { busy, message, error: failed ? message : '', setMessage, run };
}

/**
 * 계획 작업 실패를 화면 말로 바꾼다. 위쪽 상태 줄과 문서 업로드 실패 팝업이 같은 문구를 쓴다.
 * explain: 서버가 확정 거절한 실패를 칸별 사유 등으로 풀어 준다(#387).
 */
export function workFailureText(
    error: unknown,
    explain?: (failure: Failure) => string | undefined,
): string {
    const outcome = error instanceof CommandNeedsReview ? error.outcome : null;
    const failure = outcome?.status === 'failed' ? outcome.failure : classifyFailure(error);
    const code = 'code' in failure ? failure.code : undefined;
    const explained =
        failure.kind === 'rejected' && failure.intent === 'finished'
            ? explain?.(failure)
            : undefined;
    if (explained) return explained;
    if (error instanceof PlanContextChanged || error instanceof AiRequestUnavailable)
        return error.message;
    if (error instanceof CommandNeedsReview && error.blocking)
        return `이전 ${OPERATION_LABEL[error.blocking] ?? '요청'}의 결과를 아직 확인하지 못해 이 요청을 보내지 않았습니다. 같은 작업을 다시 눌러 결과를 확인하거나 화면을 새로 고쳐 주세요.`;
    if (failure.kind === 'cancelled') return '';
    if (failure.kind === 'version-conflict')
        return '공연 정보가 바뀌었습니다. 입력은 유지했습니다. 최신 상태를 확인한 뒤 다시 편집해주세요.';
    if (code === 'EXTERNAL_AI_CONSENT_REQUIRED')
        return '외부 AI 처리 동의가 필요합니다. 내 정보에서 외부 AI 처리 동의를 켠 뒤 다시 요청해주세요.';
    if (failure.kind === 'rate-limited')
        return '이미 진행 중인 AI 작업이 있거나 요청이 많습니다. 끝난 뒤 다시 요청해주세요.';
    // 서버가 확정 거절한 요청은 서버 안내를 보여 준다(예: 추출할 문서 없음)
    if (outcome?.status === 'failed' && failure.kind === 'rejected' && failure.message)
        return failure.message;
    if (error instanceof ContractViolationError)
        return '입력 또는 응답의 형식/상한/참조를 확인해주세요. 현재 계획을 다시 조회할 수 있습니다.';
    if (outcome)
        return '처리 결과를 확인하지 못했습니다. 현재 상태를 조회해주세요. 같은 요청을 자동으로 다시 보내지 않습니다.';
    // 보내기 전 확인(파일 형식·크기 등)에서 멈춘 경우는 그 안내를 그대로 쓴다
    // (클라이언트가 직접 던진 Error만. 연결 실패 TypeError 등은 아래 기본 안내다)
    if (error instanceof Error && Object.getPrototypeOf(error) === Error.prototype && error.message)
        return error.message;
    return '요청을 처리하지 못했습니다. 연결과 현재 권한을 확인해주세요.';
}

/** 작업 실패를 화면 안내로 바꾼다. PDF 탭 자동 진행(V9-D65)이 쓰는 이름으로, 계획 화면과 같은 문구(workFailureText)다. */
export const workFailureMessage = workFailureText;
