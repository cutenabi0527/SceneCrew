import type { ChangeAgentRole, ChangeAlternative, ChangeCoordination } from './contract';

// AI 대응안의 참고 기록(V9-D55, #389). 판단 근거나 승인 조건이 아니며 대안을 골라 새 변경안을 만들지 않는다.
// - 검토한 다른 방법: 근거 아래 글 목록
// - AI 처리 기록: 접힌 상태로 둔다. 어떤 Agent가 무엇을 했는지, 다시 맡긴 일과 해결한 충돌을 보인다.
// 기록이 없거나 비어 있으면 아무것도 그리지 않는다(수동 변경안, 옛 대응안).

const AGENT_ROLE_LABEL: Record<ChangeAgentRole, string> = {
    SUPERVISOR: '총괄 Agent',
    SCHEDULE: '일정 Agent',
    STAFFING: '인력 Agent',
    GUIDE: '안내 Agent',
    REVIEW: '검토 Agent',
};

const STATUS_LABEL: Record<ChangeCoordination['graph'][number]['status'], string> = {
    SUCCEEDED: '완료',
    NEEDS_INFO: '추가 정보 필요',
    FAILED: '실패',
};

export function AlternativesView({ alternatives }: { alternatives?: ChangeAlternative[] }) {
    if (!alternatives?.length) return null;
    return (
        <section aria-label="검토한 다른 방법" className="stack">
            <b className="small">검토한 다른 방법</b>
            <ul className="xs">
                {alternatives.map((a, i) => (
                    <li key={`${i}:${a.summary}`}>{a.summary}</li>
                ))}
            </ul>
            <p className="xs muted">
                AI가 함께 검토한 방법을 참고로 보입니다. 승인하면 이 변경안의 내용만 적용됩니다.
            </p>
        </section>
    );
}

export function CoordinationView({ coordination }: { coordination?: ChangeCoordination }) {
    if (!coordination) return null;
    const { graph, redelegations, resolvedConflicts, contributions } = coordination;
    if (
        !graph.length &&
        !redelegations.length &&
        !resolvedConflicts.length &&
        !contributions.length
    )
        return null;
    const roleOf = new Map(graph.map((g) => [g.taskId, AGENT_ROLE_LABEL[g.role]]));
    return (
        <details aria-label="AI 처리 기록">
            <summary className="small">AI 처리 기록</summary>
            <div className="stack xs">
                {!!graph.length && (
                    <ol aria-label="처리한 Agent">
                        {graph.map((g) => (
                            <li key={g.taskId}>
                                {AGENT_ROLE_LABEL[g.role]}: {STATUS_LABEL[g.status]}
                                {g.parentTaskId && roleOf.has(g.parentTaskId)
                                    ? ` (${roleOf.get(g.parentTaskId)}가 맡김)`
                                    : ''}
                            </li>
                        ))}
                    </ol>
                )}
                {!!contributions.length && (
                    <ul aria-label="Agent별 결과">
                        {contributions.map((c, i) => (
                            <li key={`${i}:${c.role}`}>
                                {AGENT_ROLE_LABEL[c.role]}: {c.summary}
                            </li>
                        ))}
                    </ul>
                )}
                {!!redelegations.length && (
                    <ul aria-label="다시 맡긴 일">
                        {redelegations.map((r, i) => (
                            <li key={`${i}:${r.taskId}`}>
                                다시 맡김({roleOf.get(r.taskId) ?? r.taskId}): {r.reason}
                            </li>
                        ))}
                    </ul>
                )}
                {!!resolvedConflicts.length && (
                    <ul aria-label="해결한 충돌">
                        {resolvedConflicts.map((c, i) => (
                            <li key={`${i}:${c.resolution}`}>
                                해결한 충돌: {c.resolution} (관련 {c.refs.join(', ')})
                            </li>
                        ))}
                    </ul>
                )}
                <p className="muted">
                    AI가 대응안을 만든 과정의 기록입니다. 판단 근거나 승인 조건이 아닙니다.
                </p>
            </div>
        </details>
    );
}
