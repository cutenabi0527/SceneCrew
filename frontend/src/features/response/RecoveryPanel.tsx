import { useCallback, useEffect, useMemo, useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import { EquipmentReader } from '../equipment/live-client';
import { TECH_LABEL, type LiveAsset } from '../equipment/live-contract';
import type { Workflow } from '../plan-workflow/client';
import { displayTime } from '../plan-workflow/time';
import type { Responder } from './client';
import {
    CANDIDATE_LABEL,
    SIMULATION_STATE_LABEL,
    type ChangeSet,
    type RecoveryCandidate,
    type RecoverySimulation,
} from './contract';
import { JOB_LABEL, readText, resultText, useJob } from './ui';

// 장비 이상 보고의 복구 대안(#308, 총괄 WEB). 대상 장비를 골라 서버에 계산을 요청하고(EXT06),
// 결과(EXT07)의 후보별 가능 여부·준비 시각·영향 공연·충돌·변경 미리보기를 비교한다.
// 계산은 운영 상태를 바꾸지 않는다. 고른 후보는 변경안(066)으로 만들어 기존 미리보기·승인을 거친다.
// 결과가 만료되면(완료 7일 뒤 410) 자동으로 다시 계산하지 않고 새 계산을 직접 요청하게 한다.

function seconds(value: number | null) {
    if (value === null) return '알 수 없음';
    const m = Math.round(value / 60);
    return m >= 1 ? `${m}분` : `${Math.round(value)}초`;
}

function CandidateCard({
    candidate,
    names,
    busy,
    onChoose,
}: {
    candidate: RecoveryCandidate;
    names: Map<string, string>;
    busy: boolean;
    onChoose: () => void;
}) {
    const usable =
        (candidate.status === 'FEASIBLE' || candidate.status === 'CONDITIONAL') &&
        candidate.operations.length > 0;
    return (
        <li className="stack">
            <b>
                대안 {candidate.candidateId} / {CANDIDATE_LABEL[candidate.status]}
            </b>
            <p className="small">
                준비 {candidate.readyAt ? displayTime(candidate.readyAt) : '시각 미정'} / 필요{' '}
                {displayTime(candidate.requiredReadyAt)}
                {candidate.shortfallSec
                    ? ` / ${seconds(candidate.shortfallSec)} 부족`
                    : candidate.slackSec !== null
                      ? ` / 여유 ${seconds(candidate.slackSec)}`
                      : ''}
            </p>
            {!!candidate.equipmentIds.length && (
                <p className="xs">
                    사용 장비:{' '}
                    {candidate.equipmentIds.map((id) => names.get(id) ?? '다른 장비').join(', ')}
                </p>
            )}
            {!!candidate.reasons.length && (
                <ul aria-label={`대안 ${candidate.candidateId} 판단 이유`} className="xs">
                    {candidate.reasons.map((r) => (
                        <li key={r}>{r}</li>
                    ))}
                </ul>
            )}
            {!!candidate.conflicts.length && (
                <p className="xs">충돌: {candidate.conflicts.join(' / ')}</p>
            )}
            {!!candidate.programImpacts.length && (
                <p className="xs">
                    영향 공연: {candidate.programImpacts.map((p) => p.description).join(' / ')}
                </p>
            )}
            <ul aria-label={`대안 ${candidate.candidateId} 변경 미리보기`} className="small">
                {candidate.preview.map((d) => (
                    <li key={`${d.operationIndex}:${d.type}`}>{d.summary}</li>
                ))}
                {!candidate.preview.length && <li>변경할 계획 없음</li>}
            </ul>
            {usable && (
                <button className="btn" type="button" disabled={busy} onClick={onChoose}>
                    이 대안으로 변경안 만들기
                </button>
            )}
        </li>
    );
}

export function RecoveryPanel({
    client,
    responder,
    issueId,
    revision,
    onCreated,
}: {
    client: Workflow;
    responder: Responder;
    issueId: string;
    revision: string;
    // 후보로 변경안을 만들었다. 부모가 목록에 넣고 미리보기·승인 패널을 연다.
    onCreated: (set: ChangeSet) => void;
}) {
    const reader = useMemo(() => new EquipmentReader(client.session, client.scope), [client]);
    const [assets, setAssets] = useState<LiveAsset[]>([]);
    const [targets, setTargets] = useState<string[]>([]);
    const [jobId, setJobId] = useState<string | null>(null);
    const [simulationId, setSimulationId] = useState<string | null>(null);
    const [simulation, setSimulation] = useState<RecoverySimulation | null>(null);
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);

    // 장비 목록과 이 이슈의 최근 완료 계산을 읽는다. 최근 계산이 없으면(404) 새 계산만 안내한다.
    useEffect(() => {
        let active = true;
        reader.overview().then(
            (v) => {
                if (active) setAssets(v.assets);
            },
            (e: unknown) => {
                if (active) setMessage(readText(e));
            },
        );
        responder.latestSimulation(issueId).then(
            (pointer) => {
                if (!active) return;
                if (pointer.expired)
                    setMessage(
                        `${displayTime(pointer.calculatedAt)}에 계산한 결과는 만료되었습니다. 필요하면 새로 계산해주세요.`,
                    );
                else setSimulationId(pointer.simulationId);
            },
            (e: unknown) => {
                if (!active) return;
                const f = classifyFailure(e);
                if (f.kind !== 'access-denied' && f.kind !== 'cancelled') setMessage(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [reader, responder, issueId]);

    const readJob = useCallback(
        (id: string, signal: AbortSignal) => responder.recoveryJob(id, signal),
        [responder],
    );
    const job = useJob(jobId, readJob);
    const finished = job.job?.state === 'SUCCEEDED' ? job.job.result.simulationId : null;
    const current = finished ?? simulationId;

    useEffect(() => {
        if (!current) return;
        let active = true;
        responder.simulation(current).then(
            (value) => {
                if (active) setSimulation(value);
            },
            (e: unknown) => {
                if (!active) return;
                setSimulation(null);
                setMessage(
                    classifyFailure(e).code === 'RECOVERY_RESULT_EXPIRED'
                        ? '복구 계산 결과가 만료되었습니다. 필요하면 새로 계산해주세요.'
                        : readText(e),
                );
            },
        );
        return () => {
            active = false;
        };
    }, [responder, current]);

    const names = new Map(assets.map((a) => [a.id, a.name]));
    const toggle = (id: string) =>
        setTargets((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

    const calculate = async () => {
        if (busy || !targets.length) return;
        setBusy(true);
        setMessage('');
        try {
            const result = await responder.recover({
                issueId,
                targetAssetIds: targets,
                expectedEventRevision: revision,
            });
            if (result.kind === 'job') {
                setSimulation(null);
                setSimulationId(null);
                setJobId(result.jobId);
                setMessage('복구 대안 계산을 요청했습니다. 계산은 계획을 바꾸지 않습니다.');
            } else setMessage(resultText(result, '복구 계산'));
        } finally {
            setBusy(false);
        }
    };

    const choose = async (candidate: RecoveryCandidate) => {
        if (busy || !simulation) return;
        setBusy(true);
        setMessage('');
        try {
            const result = await responder.createFromCandidate({
                simulation,
                candidate,
                issueId,
                expectedEventRevision: revision,
            });
            if (result.kind === 'done') {
                setMessage(
                    `대안 ${candidate.candidateId}로 변경안을 만들었습니다. 아래에서 미리보기 후 승인하세요.`,
                );
                onCreated(result.data);
            } else setMessage(resultText(result, '변경안 생성'));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section aria-label="복구 대안" className="stack">
            <b>복구 대안</b>
            <fieldset className="stack">
                <legend className="small">문제가 생긴 장비</legend>
                {assets.map((a) => (
                    <label key={a.id} className="small">
                        <input
                            type="checkbox"
                            checked={targets.includes(a.id)}
                            onChange={() => toggle(a.id)}
                        />{' '}
                        {a.name} / {TECH_LABEL[a.technicalState]}
                    </label>
                ))}
                {!assets.length && <p className="xs">조회한 장비가 없습니다.</p>}
            </fieldset>
            <button
                className="btn"
                type="button"
                disabled={busy || !targets.length}
                onClick={() => void calculate()}
            >
                {responder.pending('recover', issueId)
                    ? '같은 계산 요청 다시 보내기'
                    : '복구 대안 계산'}
            </button>
            {job.job && (
                <p role="status" className="xs">
                    복구 계산 {JOB_LABEL[job.job.state]}
                    {job.job.state === 'FAILED' && ` (${job.job.error.code})`}
                </p>
            )}
            {job.error && <p role="status">{job.error}</p>}
            {message && <p role="status">{message}</p>}
            {simulation && (
                <>
                    <p className="small">
                        {SIMULATION_STATE_LABEL[simulation.state]} / 계산{' '}
                        {displayTime(simulation.calculatedAt)} / {displayTime(simulation.expiresAt)}
                        까지 조회 가능
                    </p>
                    {!!simulation.assumptions.length && (
                        <ul aria-label="확인되지 않은 가정" className="xs">
                            {simulation.assumptions.map((a) => (
                                <li key={a.key}>확인 안 된 가정: {a.description}</li>
                            ))}
                        </ul>
                    )}
                    <ul aria-label="복구 후보" className="stack">
                        {simulation.candidates.map((c) => (
                            <CandidateCard
                                key={c.candidateId}
                                candidate={c}
                                names={names}
                                busy={busy}
                                onChoose={() => void choose(c)}
                            />
                        ))}
                        {!simulation.candidates.length && (
                            <li className="small">계산된 후보가 없습니다.</li>
                        )}
                    </ul>
                </>
            )}
        </section>
    );
}
