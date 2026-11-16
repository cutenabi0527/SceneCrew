import { useMemo, useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import { toLocalInput } from '../plan-workflow/time';
import type { Member } from '../members/contract';
import { ChangeSetPanel } from '../response/ChangeSetPanel';
import type { Responder } from '../response/client';
import type { ChangeSet } from '../response/contract';
import { failureText, resultText } from '../response/ui';
import type { SafetyReader } from './client';
import {
    SUPPORT_MAX_PICK,
    eligibilityText,
    supportAction,
    supportSelection,
    windowProblem,
    type SupportCandidate,
    type SupportCandidates,
    type SupportQuery,
} from './contract';

// 안전 인력 지원(#361, V9-D57). 총괄이 관측한 구역에 보낼 인원을 고른다.
// 후보는 서버가 규칙으로 고르고(원 구역 최소 유지 인원·지원 시간 업무), 화면은 서버 사유를 그대로 보인다.
// 고른 인원은 066 supportSelection으로 보내 변경안을 만들고, 기존 069 미리보기 → 070 승인으로 이어진다.
// 관측만으로 인원을 옮기지 않는다. 팀원 앱의 변경 확인·도착은 기존 업무 기능을 쓴다.

/** 지원 대상이 될 수 있는 구역. 최신 관측이 있는 구역만 넘긴다. */
export interface SupportTarget {
    zoneId: string;
    observationId: string;
    label: string;
}

interface Option {
    id: string;
    name: string;
}

/** 기본 지원 시간: 지금부터 30분 */
const SUPPORT_MINUTES = 30;

/** datetime-local 값을 서버 UTC 문자열로 바꾼다(초 이하 없음) */
function toUtc(local: string) {
    const d = new Date(local);
    return Number.isNaN(d.getTime()) ? null : d.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

export function SupportPanel({
    reader,
    responder,
    targets,
    zones,
    groups,
    members,
    eventRevision,
    onRefresh,
}: {
    reader: SafetyReader;
    responder: Responder;
    targets: SupportTarget[];
    zones: Option[];
    groups: Option[];
    members: Member[];
    eventRevision: string;
    onRefresh: () => Promise<void>;
}) {
    const [targetZoneId, setTargetZoneId] = useState(targets[0]?.zoneId ?? '');
    const [sourceZoneId, setSourceZoneId] = useState('');
    const [workGroupId, setWorkGroupId] = useState(groups[0]?.id ?? '');
    const [startAt, setStartAt] = useState(() => toLocalInput(new Date().toISOString()));
    const [endAt, setEndAt] = useState(() =>
        toLocalInput(new Date(Date.now() + SUPPORT_MINUTES * 60_000).toISOString()),
    );
    const [found, setFound] = useState<{ query: SupportQuery; result: SupportCandidates } | null>(
        null,
    );
    const [picked, setPicked] = useState<Set<string>>(new Set());
    const [created, setCreated] = useState<ChangeSet | null>(null);
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);

    // 관측이 사라진 구역을 고르고 있었으면 첫 대상으로 돌린다
    const target = targets.find((t) => t.zoneId === targetZoneId) ?? targets[0] ?? null;
    const names = useMemo(
        () => new Map(members.map((m) => [m.memberId, m.displayName])),
        [members],
    );

    // 조건이 바뀌면 이전 조건으로 찾은 후보·지원안을 쓰지 않는다
    const change = (set: (v: string) => void) => (e: { target: { value: string } }) => {
        set(e.target.value);
        setFound(null);
        setPicked(new Set());
        setCreated(null);
    };

    if (!targets.length)
        return (
            <section aria-label="안전 인력 지원" className="stack">
                <b>안전 인력 지원</b>
                <p className="small">
                    최신 관측이 있는 구역이 없어 지원 후보를 찾을 수 없습니다. 카메라 관측이
                    들어오면 다시 확인해주세요.
                </p>
            </section>
        );

    const query = (): SupportQuery | string => {
        if (!target) return '지원할 구역을 골라주세요.';
        if (!sourceZoneId) return '인원을 보낼 원래 구역을 골라주세요.';
        if (sourceZoneId === target.zoneId) return '원래 구역과 지원할 구역이 같습니다.';
        if (!workGroupId) return '보낼 직무 그룹을 골라주세요.';
        const start = toUtc(startAt);
        const end = toUtc(endAt);
        if (!start || !end) return '지원 시간을 확인해주세요.';
        const problem = windowProblem(start, end);
        if (problem) return problem;
        return {
            observationId: target.observationId,
            sourceZoneId,
            targetZoneId: target.zoneId,
            workGroupId,
            startAt: start,
            endAt: end,
        };
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

    // 후보를 버리고 최신 상태로 다시 고르게 한다
    const reselect = async (text: string) => {
        setFound(null);
        setPicked(new Set());
        await onRefresh();
        setMessage(`${text} 지원 후보 찾기를 다시 눌러주세요.`);
    };

    const search = () =>
        run(async () => {
            const q = query();
            if (typeof q === 'string') {
                setMessage(q);
                return;
            }
            try {
                const result = await reader.supportCandidates(q, eventRevision);
                setFound({ query: q, result });
                setPicked(new Set());
                setCreated(null);
                if (!result.candidates.length)
                    setMessage(
                        '지금 보낼 수 있는 인원이 없습니다. 원래 구역 최소 유지 인원과 지원 시간의 업무 때문일 수 있습니다. 다른 구역·그룹·시간으로 찾아보세요.',
                    );
            } catch (e) {
                const f = classifyFailure(e);
                if (f.kind === 'cancelled') return;
                if (f.kind === 'version-conflict')
                    return reselect('공연 정보가 그사이 바뀌었습니다.');
                if (supportAction(f.reason) === 'reselect')
                    return reselect(failureText(f, '지원 후보 찾기'));
                setMessage(failureText(f, '지원 후보 찾기'));
            }
        });

    const create = () =>
        run(async () => {
            if (!found) return;
            const chosen = found.result.candidates.filter((c) => picked.has(c.memberId));
            if (!chosen.length) {
                setMessage('보낼 인원을 한 명 이상 골라주세요.');
                return;
            }
            if (chosen.length > SUPPORT_MAX_PICK) {
                setMessage(`한 번에 ${SUPPORT_MAX_PICK}명까지 보낼 수 있습니다.`);
                return;
            }
            const result = await responder.createSupport({
                selection: supportSelection(found.query, chosen),
                expectedEventRevision: found.result.eventRevision,
            });
            if (result.kind === 'done') {
                setCreated(result.data);
                setMessage('지원안을 만들었습니다. 아래에서 미리보기한 뒤 승인해주세요.');
                return;
            }
            if (result.kind === 'conflict') return reselect('공연 정보가 그사이 바뀌었습니다.');
            if (result.kind === 'rejected' && supportAction(result.failure.reason) === 'reselect')
                return reselect(failureText(result.failure, '지원안 만들기'));
            if (result.kind === 'check') await onRefresh();
            // 최소 인원 부족·최소 기준/시간 문제는 고른 후보를 남겨 두고 조정하게 한다
            setMessage(resultText(result, '지원안 만들기'));
        });

    const toggle = (c: SupportCandidate) =>
        setPicked((prev) => {
            const next = new Set(prev);
            if (next.has(c.memberId)) next.delete(c.memberId);
            else if (next.size < SUPPORT_MAX_PICK) next.add(c.memberId);
            return next;
        });

    return (
        <section aria-label="안전 인력 지원" className="stack">
            <b>안전 인력 지원</b>
            <p className="xs">
                관측한 구역에 다른 구역의 인원을 보냅니다. 서버가 원래 구역의 최소 유지 인원을
                남기고 보낼 수 있는 인원만 후보로 냅니다. 승인해야 팀원 앱에 요청이 갑니다.
            </p>
            <label className="small">
                지원할 구역
                <select value={target?.zoneId ?? ''} onChange={change(setTargetZoneId)}>
                    {targets.map((t) => (
                        <option key={t.zoneId} value={t.zoneId}>
                            {t.label}
                        </option>
                    ))}
                </select>
            </label>
            <label className="small">
                인원을 보낼 원래 구역
                <select value={sourceZoneId} onChange={change(setSourceZoneId)}>
                    <option value="">골라주세요</option>
                    {zones
                        .filter((z) => z.id !== target?.zoneId)
                        .map((z) => (
                            <option key={z.id} value={z.id}>
                                {z.name}
                            </option>
                        ))}
                </select>
            </label>
            <label className="small">
                보낼 직무 그룹
                <select value={workGroupId} onChange={change(setWorkGroupId)}>
                    <option value="">골라주세요</option>
                    {groups.map((g) => (
                        <option key={g.id} value={g.id}>
                            {g.name}
                        </option>
                    ))}
                </select>
            </label>
            <label className="small">
                지원 시작
                <input type="datetime-local" value={startAt} onChange={change(setStartAt)} />
            </label>
            <label className="small">
                지원 종료
                <input type="datetime-local" value={endAt} onChange={change(setEndAt)} />
            </label>
            <button className="btn" type="button" disabled={busy} onClick={search}>
                지원 후보 찾기
            </button>
            {found && found.result.candidates.length > 0 && (
                <>
                    <ul aria-label="지원 후보" className="stack">
                        {found.result.candidates.map((c) => (
                            <li key={c.memberId} className="small">
                                <label>
                                    <input
                                        type="checkbox"
                                        checked={picked.has(c.memberId)}
                                        onChange={() => toggle(c)}
                                    />{' '}
                                    {names.get(c.memberId) ?? '이름을 확인하지 못한 인원'}
                                    {' · 맡은 업무를 넘기고 이동'}
                                </label>
                                <ul className="xs">
                                    {c.eligibilityReasons.map((r, i) => (
                                        <li key={i}>{eligibilityText(r)}</li>
                                    ))}
                                </ul>
                            </li>
                        ))}
                    </ul>
                    <button
                        className="btn primary"
                        type="button"
                        disabled={busy || !picked.size}
                        onClick={create}
                    >
                        고른 {picked.size}명으로 지원안 만들기
                    </button>
                </>
            )}
            {message && <p role="status">{message}</p>}
            {created && (
                <ChangeSetPanel
                    responder={responder}
                    set={created}
                    eventRevision={created.eventRevision}
                    onChanged={setCreated}
                    onRefresh={onRefresh}
                />
            )}
        </section>
    );
}
