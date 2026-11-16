import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { scopedKey } from '../../shared/scope/scope';
import type { Workflow } from './client';
import { extractionLabel, splitExtracted, type ExtractedInput } from './extraction-fields';

export function ExtractionPanel({
    client,
    id,
    onUse,
    onSucceeded,
}: {
    client: Workflow;
    id: string;
    onUse: (input: ExtractedInput) => void;
    /** 추출이 결과와 함께 끝난 것을 보면(완료·일부 추출) 작업 id로 알린다 */
    onSucceeded?: (jobId: string) => void;
}) {
    const query = useQuery({
        queryKey: scopedKey(client.scope, 'extraction', id),
        queryFn: ({ signal }) => client.extraction(id, signal),
        retry: false,
        staleTime: 0,
        refetchInterval: (q) =>
            !q.state.error &&
            (q.state.data?.state === 'QUEUED' || q.state.data?.state === 'RUNNING')
                ? 2000
                : false,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
    });
    const doneId =
        query.data && (query.data.state === 'SUCCEEDED' || query.data.state === 'PARTIAL')
            ? query.data.jobId
            : null;
    useEffect(() => {
        if (doneId) onSucceeded?.(doneId);
    }, [doneId, onSucceeded]);
    if (query.error)
        return (
            <section className="card">
                <p role="alert">추출 결과를 확인하지 못했습니다.</p>
                <button
                    className="btn"
                    onClick={() => {
                        void query.refetch();
                    }}
                >
                    추출 조회 재개
                </button>
            </section>
        );
    const job = query.data;
    if (!job || job.state === 'QUEUED' || job.state === 'RUNNING')
        return <p role="status">문서에서 공연 입력을 추출하고 있습니다.</p>;
    if (job.state === 'FAILED')
        return (
            <p role="alert">
                문서 추출에 실패했습니다. 문서와 현재 입력을 확인해주세요. 기존 입력은 유지됩니다.
            </p>
        );
    const result = job.result;
    if (!result)
        return <p role="alert">추출 결과가 비어 있습니다. 문서를 확인한 뒤 다시 요청해주세요.</p>;
    const entries = Object.entries(result.fields);
    return (
        <section className="card stack" aria-label="문서 추출 결과">
            <h3>문서 추출 결과</h3>
            {result.resultStatus === 'PARTIAL' && (
                <p role="status">일부만 추출됐습니다. 누락/상충/읽지 못한 문서를 확인해주세요.</p>
            )}
            <p className="small muted">
                추출 값은 후보입니다. 입력 칸에 채운 뒤 직접 확인하고 저장해야 반영됩니다.
            </p>
            {entries.length ? (
                <dl className="kv">
                    {entries.map(([key, v]) => (
                        <div key={key}>
                            <dt>{extractionLabel(key)}</dt>
                            <dd>{v}</dd>
                        </div>
                    ))}
                </dl>
            ) : (
                <p>문서에서 읽은 값이 없습니다.</p>
            )}
            <p>
                확인할 누락 항목: {result.missingRequired.map(extractionLabel).join(', ') || '없음'}
            </p>
            {result.conflictingValues.map((v) => (
                <p key={v.field}>
                    서로 다른 값({extractionLabel(v.field)}): {v.values.join(' / ')}
                </p>
            ))}
            {result.unreadableFiles.length > 0 && (
                <p>
                    읽지 못한 문서 {result.unreadableFiles.length}개가 있습니다(
                    {[...new Set(result.unreadableFiles.map((f) => f.code))].join(', ')}). 문서를
                    바꾸거나 직접 입력해주세요.
                </p>
            )}
            {!!result.floorPlanPages?.length && (
                <p>
                    평면도로 보이는 페이지:{' '}
                    {result.floorPlanPages.map((p) => `${p.pageNumber}쪽`).join(', ')}.{' '}
                    {result.floorPlanPages.length > 1
                        ? '여러 장이면 계획 생성은 첫 장만 읽습니다. '
                        : ''}
                    계획을 생성하면 이 평면도로 구역 도형을 그립니다.
                </p>
            )}
            {entries.length > 0 && (
                <button className="btn" onClick={() => onUse(splitExtracted(result.fields))}>
                    추출 내용을 입력 칸에 채우기
                </button>
            )}
        </section>
    );
}
