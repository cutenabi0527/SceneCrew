import { useState } from 'react';
import { useWorkAction } from './action';
import type { Workflow } from './client';
import type { Task, TaskEquipment, TaskSafety } from './contract';
import { displayTime } from './time';
import { qualityReasonsText } from '../safety-map/quality';

// 업무 상세의 장비 지시·점검 기록(EXT09)과 구역 안전 요약(051 V9Task). 내 업무 화면 안에서만 쓴다.
// 점검 기록은 실제 담당자 본인의 인계·설치·재시험 결과이며 장비 상태를 화면이 직접 바꾸지 않는다.

const KIND_LABEL = { HANDOVER: '인계', INSTALLATION: '설치', RETEST: '재시험' } as const;
type Kind = keyof typeof KIND_LABEL;

const FRESHNESS_LABEL: Record<TaskSafety['freshness'], string> = {
    UNKNOWN: '관측 시각 미상',
    FRESH: '최신 관측',
    STALE: '오래된 관측',
    INVALID_MAPPING: '구역 매핑 확인 필요',
};

function EquipmentCheck({
    client,
    task,
    item,
    revision,
    onRecorded,
}: {
    client: Workflow;
    task: Task;
    item: TaskEquipment;
    revision: string;
    onRecorded: (eventRevision: string) => void;
}) {
    const [kind, setKind] = useState<Kind>('INSTALLATION');
    const [passed, setPassed] = useState(true);
    const [note, setNote] = useState('');
    const action = useWorkAction();
    const done = task.status === 'DONE' || task.status === 'CANCELED';
    const record = () =>
        action.run(
            () =>
                client.equipmentCheck(
                    task,
                    { assetId: item.assetId, kind, passed, note },
                    revision,
                ),
            (receipt) => {
                onRecorded(receipt.eventRevision);
                setNote('');
                action.setMessage(
                    `${KIND_LABEL[kind]} ${passed ? '통과' : '실패'}를 기록했습니다. 장비 상태는 총괄이 확인합니다.`,
                );
            },
        );
    return (
        <li className="stack">
            <span>{item.instruction}</span>
            {!!item.componentIds.length && (
                <span className="xs">구성품 {item.componentIds.length}개 포함</span>
            )}
            <fieldset className="row" disabled={done || action.busy}>
                <label className="field">
                    <span>기록 종류</span>
                    <select
                        aria-label={`${item.instruction} 기록 종류`}
                        value={kind}
                        onChange={(e) => setKind(e.target.value as Kind)}
                    >
                        {(Object.keys(KIND_LABEL) as Kind[]).map((k) => (
                            <option key={k} value={k}>
                                {KIND_LABEL[k]}
                            </option>
                        ))}
                    </select>
                </label>
                <label className="field">
                    <span>결과</span>
                    <select
                        aria-label={`${item.instruction} 결과`}
                        value={passed ? 'pass' : 'fail'}
                        onChange={(e) => setPassed(e.target.value === 'pass')}
                    >
                        <option value="pass">통과</option>
                        <option value="fail">실패</option>
                    </select>
                </label>
                <label className="field">
                    <span>메모(선택)</span>
                    <input
                        aria-label={`${item.instruction} 메모`}
                        value={note}
                        onChange={(e) => setNote(e.target.value)}
                    />
                </label>
                <button className="btn" type="button" onClick={() => void record()}>
                    점검 기록 남기기
                </button>
            </fieldset>
            {action.message && <p role="status">{action.message}</p>}
        </li>
    );
}

export function TaskContext({
    client,
    task,
    revision,
    onRevision,
}: {
    client: Workflow;
    task: Task;
    revision: string;
    onRevision: (eventRevision: string) => void;
}) {
    const equipment = task.equipment ?? [];
    const safety = task.safety ?? null;
    if (!equipment.length && !safety) return null;
    return (
        <>
            {!!equipment.length && (
                <section aria-label="장비 지시" className="stack">
                    <b>장비 지시/점검 기록</b>
                    <ul className="stack small">
                        {equipment.map((item) => (
                            <EquipmentCheck
                                key={item.assetId}
                                client={client}
                                task={task}
                                item={item}
                                revision={revision}
                                onRecorded={onRevision}
                            />
                        ))}
                    </ul>
                </section>
            )}
            {safety && (
                <section aria-label="구역 안전" className="stack small">
                    <b>구역 안전</b>
                    <span>
                        {FRESHNESS_LABEL[safety.freshness]}
                        {safety.estimatedCount !== null &&
                            ` / 추정 ${Math.round(safety.estimatedCount)}명`}
                        {safety.capturedAt && ` / ${displayTime(safety.capturedAt)} 관측`}
                    </span>
                    {!!safety.qualityReasons.length && (
                        <span className="xs">
                            관측 품질: {qualityReasonsText(safety.qualityReasons)}
                        </span>
                    )}
                    <ul>
                        {safety.instructions.map((text) => (
                            <li key={text}>{text}</li>
                        ))}
                    </ul>
                </section>
            )}
        </>
    );
}
