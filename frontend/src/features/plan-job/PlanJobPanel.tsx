import { useSyncExternalStore } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { createApi } from '../../shared/api/api';
import { classifyFailure } from '../../shared/api/failure';
import { ContractViolationError } from '../../shared/api/contract';
import type { ParticipationScope } from '../../shared/scope/scope';
import type { PlanQuestion } from './contract';
import { planJobQuery } from './query';
import { questionHint } from './question-target';

const MESSAGES: Record<string, string> = {
    INVALID_REQUEST: '계획 입력을 확인한 뒤 다시 생성해주세요.',
    AI_TIMEOUT: '계획 생성 시간이 초과됐습니다. 입력을 확인한 뒤 다시 생성할 수 있습니다.',
    AI_REVIEW_FAILED: '검토를 통과하지 못했습니다. 계획 입력을 보완해주세요.',
    AI_UNAVAILABLE:
        '현재 계획 생성 서비스를 사용할 수 없습니다. 잠시 후 입력 화면에서 다시 시도해주세요.',
    STALE_CONTEXT: '계획 생성 중 입력 정보가 바뀌었습니다. 최신 입력을 확인해주세요.',
};

// 입력 편집/새 생성과 READY 후보 적용은 호출자가 제공한다. 조회 성공만으로 DRAFT를 바꾸지 않는다.
export function PlanJobPanel({
    api,
    scope,
    jobId,
    onRevise,
    onOpenDraft,
}: {
    api: Pick<ReturnType<typeof createApi>, 'get'>;
    scope: ParticipationScope;
    jobId: string;
    onRevise?: (questions: PlanQuestion[]) => void;
    onOpenDraft?: () => void;
}) {
    const current = useSyncExternalStore(
        (listener) => {
            scope.signal.addEventListener('abort', listener);
            return () => scope.signal.removeEventListener('abort', listener);
        },
        () => scope.isCurrent(),
    );
    const query = useQuery({
        ...planJobQuery(api, scope, jobId),
        enabled: current,
        refetchInterval: (q) =>
            !q.state.error &&
            (q.state.data?.state === 'QUEUED' || q.state.data?.state === 'RUNNING')
                ? 2000
                : false,
        refetchOnWindowFocus: (q) =>
            !q.state.error &&
            (q.state.data?.state === 'QUEUED' || q.state.data?.state === 'RUNNING'),
        refetchOnReconnect: (q) =>
            !q.state.error &&
            (q.state.data?.state === 'QUEUED' || q.state.data?.state === 'RUNNING'),
    });
    if (!current)
        return (
            <section className="card" role="status">
                행사 참여가 변경됐습니다. 행사 정보를 다시 열어주세요.
            </section>
        );
    if (query.error) {
        const failure = classifyFailure(query.error);
        const denied = failure.kind === 'access-denied' || failure.kind === 'unauthenticated';
        return (
            <section className="card stack" role="alert">
                <h2 className="h2">
                    {denied
                        ? '접근 정보를 확인해주세요'
                        : query.error instanceof ContractViolationError
                          ? '생성 결과 형식을 확인해주세요'
                          : '생성 결과를 확인하지 못했습니다'}
                </h2>
                <p>
                    {denied
                        ? '로그인과 현재 행사 참여 상태를 확인해주세요.'
                        : '기존 계획은 유지됩니다. 연결을 확인한 뒤 같은 작업의 조회를 재개할 수 있습니다.'}
                </p>
                <button
                    type="button"
                    className="btn"
                    disabled={query.isFetching}
                    onClick={() => void query.refetch()}
                >
                    조회 재개
                </button>
            </section>
        );
    }
    const job = query.data;
    if (!job)
        return (
            <section className="card" role="status" aria-busy="true">
                계획 생성 상태를 확인하고 있습니다.
            </section>
        );
    if (job.state === 'QUEUED' || job.state === 'RUNNING')
        return (
            <section className="card stack" role="status" aria-busy="true">
                <h2 className="h2">
                    {job.state === 'QUEUED' ? '계획 생성 대기 중' : '공연 계획을 생성하고 있습니다'}
                </h2>
                <p>입력한 자료를 바탕으로 공연 순서와 준비 업무를 정리합니다.</p>
            </section>
        );
    if (job.state === 'FAILED')
        return (
            <section className="card stack" role="alert">
                <h2 className="h2">
                    {job.error.code === 'STALE_CONTEXT'
                        ? '입력 정보가 변경됐습니다'
                        : '계획 생성을 마치지 못했습니다'}
                </h2>
                <p>
                    {Object.hasOwn(MESSAGES, job.error.code)
                        ? MESSAGES[job.error.code]
                        : '계획 생성 중 문제가 발생했습니다. 입력 정보를 확인해주세요.'}
                </p>
                {onRevise && (
                    <button type="button" className="btn" onClick={() => onRevise([])}>
                        입력 확인
                    </button>
                )}
            </section>
        );
    if (job.state !== 'SUCCEEDED') return null;
    const result = job.result;
    if (result.status === 'NEEDS_INFO')
        return (
            <section className="card stack" role="status">
                <h2 className="h2">추가 확인이 필요합니다</h2>
                <p>아래 내용을 보완한 뒤 다시 생성해주세요. 기존 계획은 유지됩니다.</p>
                <ul>
                    {result.questions.map((q, index) => {
                        const hint = questionHint(q, false);
                        return (
                            <li key={`${q.key}-${index}`}>
                                {q.question}
                                {hint && <span className="small muted"> {hint}</span>}
                            </li>
                        );
                    })}
                </ul>
                {onRevise && (
                    <button
                        type="button"
                        className="btn"
                        onClick={() => onRevise(result.questions)}
                    >
                        입력 보완
                    </button>
                )}
            </section>
        );
    return (
        <section className="card stack" role="status">
            <h2 className="h2">계획 초안이 준비됐습니다</h2>
            <p>
                직무 그룹 {result.summary.workGroups}개 / 공연 {result.summary.programs}개 / 업무{' '}
                {result.summary.tasks}개
            </p>
            {result.layoutZones > 0 && (
                <p>
                    자료의 공연장 평면도에서 구역 {result.layoutZones}개를 읽었습니다. 초안을
                    검토하면 아래 미확정 장비·장소 초안에서 평면도를 확인하고 고칠 수 있습니다.
                </p>
            )}
            <p>초안을 검토하고 필요한 내용을 수정해주세요.</p>
            {onOpenDraft && (
                <button type="button" className="btn" onClick={onOpenDraft}>
                    초안 검토
                </button>
            )}
        </section>
    );
}
