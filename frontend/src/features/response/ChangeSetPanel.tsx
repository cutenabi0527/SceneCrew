import { useState } from 'react';
import { displayTime } from '../plan-workflow/time';
import { AlternativesView, CoordinationView } from './AgentRecords';
import type { Responder, Result } from './client';
import { CHANGE_STATUS_LABEL, type ChangeSet, type Reservation } from './contract';
import { ChangeSetEditor, EvidenceView, PastEvidence } from './ChangeSetEvidence';
import { resultText } from './ui';

// 변경안 비교·총괄 승인. 화면은 서버가 계산한 diff 요약을 그대로 보여 주고, 적용 결과를 추측하지 않는다.
// 적용은 미리보기에서 받은 previewHash와 두 revision을 그대로 보낸다. 응답을 못 받으면 같은 키로만 다시 보낸다.

function reservationText(r: Reservation | null | undefined) {
    if (!r) return '없음';
    return `${displayTime(r.startAt)} ~ ${displayTime(r.endAt)} / 장비 ${r.assetIds.length}대`;
}

export function ChangeSetPanel({
    responder,
    set,
    eventRevision,
    onChanged,
    onRefresh,
}: {
    responder: Responder;
    set: ChangeSet;
    eventRevision: string;
    onChanged: (set: ChangeSet) => void;
    // 현재 상태를 다시 조회한다(공연 revision·이슈·변경안)
    onRefresh: () => Promise<void>;
}) {
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [retry, setRetry] = useState<null | 'apply'>(null);
    const [editing, setEditing] = useState(false);
    const [history, setHistory] = useState(false);

    const finish = async (result: Result<ChangeSet>, action: string, done: string) => {
        if (result.kind === 'done') {
            onChanged(result.data);
            setMessage(done);
            setRetry(null);
            return;
        }
        setRetry(result.kind === 'retry' && action === '적용' ? 'apply' : null);
        // 결과를 모르거나 충돌·거절이면 현재 상태로 다시 판단한다
        if (result.kind !== 'retry') await onRefresh();
        setMessage(resultText(result, action));
    };

    const run = async (work: () => Promise<void>) => {
        if (busy) return;
        setBusy(true);
        setMessage('');
        try {
            await work();
        } finally {
            setBusy(false);
        }
    };

    const preview = () =>
        run(async () =>
            finish(
                await responder.preview(set.id, set.revision, eventRevision),
                '미리보기',
                '현재 공연 상태로 변경 내용을 계산했습니다. 내용을 확인한 뒤 승인하세요.',
            ),
        );
    const apply = () =>
        run(async () => {
            if (!set.previewHash) return;
            await finish(
                await responder.apply(set.id, {
                    expectedChangeSetRevision: set.revision,
                    previewHash: set.previewHash,
                    expectedEventRevision: eventRevision,
                }),
                '적용',
                '변경을 적용했습니다. 바뀐 업무의 담당자에게 확인 요청이 전달됩니다.',
            );
        });
    const discard = () =>
        run(async () =>
            finish(
                await responder.discard(set.id, set.revision),
                '폐기',
                '변경안을 폐기했습니다. 공연 계획은 바뀌지 않았습니다.',
            ),
        );

    const open = set.status === 'DRAFT' || set.status === 'READY';
    return (
        <section aria-label="변경안" className="stack">
            <h3 className="h3">
                {set.mode === 'AI' ? 'AI 대응안' : '수동 변경안'} /{' '}
                {CHANGE_STATUS_LABEL[set.status]}
            </h3>
            {set.rationale && <p className="small">근거: {set.rationale}</p>}
            <p className="xs">
                연산 {set.operations.length}개 / 변경안 revision {set.revision} / 기준 공연 revision{' '}
                {set.baseRevision}
            </p>
            <b>현재와 변경 후</b>
            <ul aria-label="변경 내용" className="small">
                {set.diff.map((item) => (
                    <li key={`${item.operationIndex}:${item.type}:${item.targetId ?? ''}`}>
                        {item.summary}
                        {item.type.includes('RESERVATION') || item.type === 'RESERVE_EQUIPMENT' ? (
                            <span className="xs">
                                {' '}
                                (현재 {reservationText(item.before)} → 변경 후{' '}
                                {reservationText(item.after)})
                            </span>
                        ) : null}
                    </li>
                ))}
                {!set.diff.length && (
                    <li>
                        {open
                            ? '미리보기를 하면 현재 상태와 비교한 변경 내용이 보입니다.'
                            : '변경 내용 없음'}
                    </li>
                )}
            </ul>
            {!!set.simulationRefs?.length && (
                <p className="xs">
                    복구 계산 결과 {set.simulationRefs.length}건을 근거로 사용합니다.
                </p>
            )}
            {set.evidenceSnapshot ? (
                <EvidenceView snap={set.evidenceSnapshot} title="이 변경안의 근거" />
            ) : (
                <p className="xs">이 변경안에 연결된 근거 기록이 없습니다.</p>
            )}
            <AlternativesView alternatives={set.alternatives} />
            <CoordinationView coordination={set.coordination} />
            {editing && open && (
                <ChangeSetEditor
                    responder={responder}
                    set={set}
                    eventRevision={eventRevision}
                    onDone={async (result, text) => {
                        if (result.kind === 'done') {
                            onChanged(result.data);
                            setEditing(false);
                            setRetry(null);
                        } else if (result.kind !== 'retry') await onRefresh();
                        setMessage(text);
                    }}
                />
            )}
            {history && <PastEvidence responder={responder} set={set} />}
            {message && <p role="status">{message}</p>}
            <div className="row">
                {set.status === 'DRAFT' && (
                    <button className="btn" type="button" disabled={busy} onClick={preview}>
                        현재 상태로 미리보기
                    </button>
                )}
                {(set.status === 'READY' || retry === 'apply') && (
                    <button className="btn primary" type="button" disabled={busy} onClick={apply}>
                        {retry === 'apply' ? '같은 승인으로 다시 보내기' : '승인하고 적용'}
                    </button>
                )}
                {open && (
                    <button
                        className="btn"
                        type="button"
                        disabled={busy}
                        aria-pressed={editing}
                        onClick={() => setEditing((v) => !v)}
                    >
                        {editing ? '편집 닫기' : '변경안 편집'}
                    </button>
                )}
                <button
                    className="btn"
                    type="button"
                    aria-pressed={history}
                    onClick={() => setHistory((v) => !v)}
                >
                    {history ? '근거 기록 닫기' : 'revision별 근거 기록'}
                </button>
                {open && (
                    <button className="btn" type="button" disabled={busy} onClick={discard}>
                        변경안 폐기
                    </button>
                )}
            </div>
        </section>
    );
}
