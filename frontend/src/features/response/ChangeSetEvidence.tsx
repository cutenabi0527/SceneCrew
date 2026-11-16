import { useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import { displayTime } from '../plan-workflow/time';
import { Field } from '../plan-workflow/ui';
import type { Responder, Result } from './client';
import { OPERATION_LABEL, type ChangeSet, type EvidenceSnapshot } from './contract';
import { readText, resultText } from './ui';

// 변경안 편집(068)과 revision별 근거 기록(R2).
// - 편집은 근거 문장과 연산 빼기만 한다. 새 연산은 대응안 생성·복구 후보로 만든다.
// - 편집하면 서버가 미리보기를 지우므로 다시 미리보기한 뒤 승인한다.
// - 근거 기록은 서버가 revision마다 불변으로 보관한 요약이다. 기록이 없으면 없다고 알리고 빈 근거로 채우지 않는다.

export function EvidenceView({ snap, title }: { snap: EvidenceSnapshot; title: string }) {
    return (
        <section aria-label={title} className="stack">
            <b className="small">
                {title} / revision {snap.changeSetRevision} / 기록 {displayTime(snap.capturedAt)}
            </b>
            <ul className="xs">
                {snap.evidence.map((e) => (
                    <li key={`${e.kind}:${e.id}:${e.revision ?? ''}`}>
                        {e.summary}
                        {e.confirmedAt
                            ? ` / 확인 ${displayTime(e.confirmedAt)}`
                            : ' / 확인 시각 없음'}
                        {!!e.preconditions.length && ` / 전제: ${e.preconditions.join(' / ')}`}
                    </li>
                ))}
                {snap.selections.map((s) => (
                    <li key={`${s.simulationId}:${s.candidateId}`}>
                        선택한 복구 후보: {s.summary} / 이유: {s.reason}
                    </li>
                ))}
                {!snap.evidence.length && !snap.selections.length && <li>연결된 근거 없음</li>}
            </ul>
        </section>
    );
}

export function PastEvidence({ responder, set }: { responder: Responder; set: ChangeSet }) {
    const current = BigInt(set.revision);
    const options: string[] = [];
    for (let r = current; r >= 1n && options.length < 20; r--) options.push(r.toString());
    const [revision, setRevision] = useState(options[0] ?? '');
    const [snap, setSnap] = useState<EvidenceSnapshot | null>(null);
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);

    const load = async () => {
        if (busy || !revision) return;
        setBusy(true);
        setMessage('');
        setSnap(null);
        try {
            setSnap(await responder.evidence(set.id, revision));
        } catch (error) {
            const f = classifyFailure(error);
            setMessage(
                f.status === 404
                    ? `revision ${revision}의 근거 기록이 없습니다. 근거 저장 전에 만든 변경안일 수 있습니다.`
                    : readText(error),
            );
        } finally {
            setBusy(false);
        }
    };

    if (!options.length) return null;
    return (
        <section aria-label="revision별 근거 기록" className="stack">
            <div className="row">
                <label className="small">
                    근거 기록 revision{' '}
                    <select value={revision} onChange={(e) => setRevision(e.target.value)}>
                        {options.map((r) => (
                            <option key={r} value={r}>
                                {r}
                                {r === set.revision ? ' (현재)' : ''}
                            </option>
                        ))}
                    </select>
                </label>
                <button className="btn" type="button" disabled={busy} onClick={() => void load()}>
                    근거 기록 보기
                </button>
            </div>
            {message && <p role="status">{message}</p>}
            {snap && <EvidenceView snap={snap} title="조회한 근거 기록" />}
        </section>
    );
}

export function ChangeSetEditor({
    responder,
    set,
    eventRevision,
    onDone,
}: {
    responder: Responder;
    set: ChangeSet;
    eventRevision: string;
    // 편집 결과. 결과를 모르면 null로 알려 호출자가 현재 상태를 다시 조회한다.
    onDone: (result: Result<ChangeSet>, message: string) => void | Promise<void>;
}) {
    const [rationale, setRationale] = useState(set.rationale);
    const [removed, setRemoved] = useState<Set<number>>(new Set());
    const [busy, setBusy] = useState(false);
    const kept = set.operations.filter((_, i) => !removed.has(i));
    const changedText = rationale.trim() !== set.rationale.trim();
    const changedOps = removed.size > 0;

    const toggle = (i: number) =>
        setRemoved((prev) => {
            const next = new Set(prev);
            if (next.has(i)) next.delete(i);
            else next.add(i);
            return next;
        });

    const save = async () => {
        if (busy || (!changedText && !changedOps) || !kept.length) return;
        setBusy(true);
        try {
            const result = await responder.edit(
                set.id,
                {
                    ...(changedOps ? { operations: kept } : {}),
                    ...(changedText ? { rationale: rationale.trim() } : {}),
                },
                set.revision,
                eventRevision,
            );
            await onDone(
                result,
                result.kind === 'done'
                    ? '변경안을 고쳤습니다. 현재 상태로 다시 미리보기한 뒤 승인하세요.'
                    : resultText(result, '편집'),
            );
        } finally {
            setBusy(false);
        }
    };

    return (
        <section aria-label="변경안 편집" className="stack">
            <Field label="근거 문장" value={rationale} change={setRationale} multiline />
            <ul aria-label="편집할 연산" className="small">
                {set.operations.map((op, i) => (
                    <li key={i}>
                        <label>
                            <input
                                type="checkbox"
                                checked={!removed.has(i)}
                                onChange={() => toggle(i)}
                            />{' '}
                            {i + 1}. {OPERATION_LABEL[op.type]}
                        </label>
                    </li>
                ))}
            </ul>
            {!kept.length && <p role="alert">연산을 하나 이상 남겨야 합니다.</p>}
            <p className="xs">
                연산 내용은 여기서 바꾸지 않습니다. 다른 조치가 필요하면 대응안을 새로 만드세요.
            </p>
            <button
                className="btn"
                type="button"
                disabled={busy || (!changedText && !changedOps) || !kept.length}
                onClick={() => void save()}
            >
                편집 저장
            </button>
        </section>
    );
}
