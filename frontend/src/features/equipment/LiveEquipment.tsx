import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure } from '../../shared/api/failure';
import type { EventSummary } from '../identity/contract';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { displayTime } from '../plan-workflow/time';
import { equipmentCommands } from './commands';
import { ObserveAsset, RegisterAsset, RelationsPanel, type PlanRefs } from './EquipmentOps';
import { EquipmentReader } from './live-client';
import {
    RESERVATION_LABEL,
    SOURCE_LABEL,
    TECH_LABEL,
    type LiveAsset,
    type LiveDetail,
    type LiveReservation,
    type TechnicalState,
} from './live-contract';

// 실제 서버의 장비 현황(W03 조회). 총괄과 장비 조회 허용 그룹의 팀원이 같은 화면을 조회한다(V9-D13).
// 등록·복구 계산·변경안은 공개 계약(결과 조회 Q1 등)이 확정된 뒤 총괄 화면에 연결한다.

/** 장비 현황 자동 재조회 주기 */
export const REFRESH_MS = 15_000;

const ORDER: TechnicalState[] = ['UNUSABLE', 'CHECK_REQUIRED', 'UNKNOWN', 'VERIFIED_USABLE'];

export function LiveEquipmentRoute() {
    return (
        <EventAccess>
            {(client, event) => <LiveEquipment client={client} event={event} />}
        </EventAccess>
    );
}

function failureMessage(error: unknown) {
    const f = classifyFailure(error);
    switch (f.kind) {
        case 'cancelled':
            return '';
        case 'access-denied':
            return '장비 현황을 볼 수 없습니다. 총괄과 장비 담당 그룹만 볼 수 있으며, 서버에 장비 기능이 아직 열리지 않았을 수도 있습니다.';
        case 'unauthenticated':
            return '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.';
        case 'unavailable':
            return '서버가 잠시 응답하지 않습니다. 현장 확인으로 진행하고 잠시 뒤 다시 조회하세요.';
        default:
            return '장비 정보를 확인하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.';
    }
}

/** 조회 자격을 잃었는가(403·404 거절, 401 로그인 만료) */
function lostAccess(error: unknown) {
    const kind = classifyFailure(error).kind;
    return kind === 'access-denied' || kind === 'unauthenticated';
}

function LiveEquipment({ client, event }: { client: Workflow; event: EventSummary }) {
    const reader = useMemo(() => new EquipmentReader(client.session, client.scope), [client]);
    const chief = event.myParticipation?.eventRole === 'CHIEF';
    const commands = useMemo(() => equipmentCommands(client.session, client.scope), [client]);
    // 총괄 명령에 쓰는 현재 공연 revision과 계획의 구역·업무·프로그램
    const [ops, setOps] = useState<{ eventRevision: string; refs: PlanRefs } | null>(null);
    const [data, setData] = useState<{
        assets: LiveAsset[];
        reservations: LiveReservation[];
        truncated: boolean;
    } | null>(null);
    const [message, setMessage] = useState('장비 현황을 불러오고 있습니다.');
    const [reload, setReload] = useState(0);
    // 지금 화면에 장비 현황을 보여 주고 있는지(일시 실패 때 현황을 남길지 판단)
    const shown = useRef(false);
    const [selected, setSelected] = useState<string | null>(null);
    // 상세는 요청한 장비 id와 함께 둔다. 다른 장비를 고르면 이전 응답을 쓰지 않는다.
    const [loaded, setLoaded] = useState<{
        id: string;
        detail: LiveDetail | null;
        message: string;
    } | null>(null);

    // 회수할 때마다 1씩 늘린다. 회수 전에 보낸 조회의 늦은 응답이 지운 화면을 되살리지 않게 한다.
    const revision = useRef(0);
    // 조회 자격을 잃으면 목록·상세 어느 쪽에서 확인했든 열린 화면의 장비 정보를 모두 지운다(V9-D13)
    const revoke = useCallback((text: string) => {
        revision.current += 1;
        shown.current = false;
        setData(null);
        setSelected(null);
        setLoaded(null);
        setMessage(text);
    }, []);

    useEffect(() => {
        const abort = new AbortController();
        const started = revision.current;
        reader
            .overview(abort.signal)
            .then((value) => {
                if (revision.current !== started) return;
                shown.current = true;
                setData(value);
                setMessage(value.assets.length ? '' : '등록된 장비가 없습니다.');
            })
            .catch((error: unknown) => {
                if (revision.current !== started) return;
                const text = failureMessage(error);
                if (!text) return;
                if (lostAccess(error)) return revoke(text);
                // 일시적인 실패는 마지막으로 받은 현황을 남기고 알린다
                setMessage(
                    shown.current ? '새로 조회하지 못했습니다. 마지막으로 받은 현황입니다.' : text,
                );
            });
        return () => abort.abort();
    }, [reader, reload, revoke]);

    useEffect(() => {
        if (!chief) return;
        let active = true;
        client.read().then(
            (ctx) => {
                if (!active) return;
                setOps({
                    eventRevision: ctx.event.revision,
                    refs: {
                        zones: ctx.plan.zones.map((z) => ({ id: z.id, name: z.name })),
                        tasks: ctx.plan.tasks.map((t) => ({ id: t.id, title: t.title })),
                        programs: ctx.plan.programs.map((p) => ({ id: p.id, name: p.name })),
                    },
                });
            },
            () => {
                if (active) setOps(null);
            },
        );
        return () => {
            active = false;
        };
    }, [client, chief, reload]);

    // 화면이 보이는 동안 주기적으로 다시 조회해 상태 변화와 권한 회수를 반영한다
    useEffect(() => {
        const tick = () => {
            if (document.visibilityState === 'visible') setReload((n) => n + 1);
        };
        const timer = window.setInterval(tick, REFRESH_MS);
        document.addEventListener('visibilitychange', tick);
        return () => {
            window.clearInterval(timer);
            document.removeEventListener('visibilitychange', tick);
        };
    }, []);

    useEffect(() => {
        if (!selected) return;
        const abort = new AbortController();
        const started = revision.current;
        reader
            .detail(selected, abort.signal)
            .then((value) => {
                if (revision.current !== started) return;
                if (value.asset.id !== selected) throw new Error('다른 장비 응답');
                setLoaded({ id: selected, detail: value, message: '' });
            })
            .catch((error: unknown) => {
                if (revision.current !== started) return;
                const text = failureMessage(error);
                if (!text) return;
                if (lostAccess(error)) return revoke(text);
                // 일시적인 실패는 같은 장비의 마지막 상세를 남기고 알린다
                setLoaded((prev) =>
                    prev?.id === selected && prev.detail
                        ? {
                              ...prev,
                              message: '새로 조회하지 못했습니다. 마지막으로 받은 상세입니다.',
                          }
                        : { id: selected, detail: null, message: text },
                );
            });
        return () => abort.abort();
    }, [reader, selected, reload, revoke]);
    const current = loaded?.id === selected ? loaded : null;
    const detail = current?.detail ?? null;
    const detailMessage = current ? current.message : '장비 상세를 불러오고 있습니다.';

    const names = new Map(data?.assets.map((a) => [a.id, a.name]) ?? []);
    const sorted = [...(data?.assets ?? [])].sort(
        (a, b) =>
            ORDER.indexOf(a.technicalState) - ORDER.indexOf(b.technicalState) ||
            a.name.localeCompare(b.name, 'ko'),
    );
    const reservationsOf = (assetId: string) =>
        (data?.reservations ?? [])
            .filter((r) => r.assetIds.includes(assetId) && r.status !== 'CANCELED')
            .sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt));

    return (
        <EntryFrame title={chief ? '장비 운영' : '장비 현황'}>
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            <p className="small">
                {chief
                    ? '총괄: 장비를 등록하고 상태/관계(연결/필요 조건/선후 관계)를 기록합니다. 장비 이상의 복구 대안 계산과 변경안 승인은 보고/대응 화면에서 보고를 골라 진행합니다.'
                    : '조회 전용(장비 담당 그룹): 장비 상태와 사용 시간표를 봅니다. 등록/대안 선택/변경 승인은 총괄이 합니다.'}
            </p>
            <button className="btn" type="button" onClick={() => setReload((n) => n + 1)}>
                장비 현황 새로고침
            </button>
            {message && <p role="status">{message}</p>}
            {data?.truncated && (
                <p role="status">
                    목록이 길어 일부만 표시했습니다. 전체 확인은 서버 목록 기능이 확정된 뒤
                    제공합니다.
                </p>
            )}
            <ul aria-label="장비 목록" className="stack">
                {sorted.map((a) => {
                    const ev = a.stateEvidence;
                    return (
                        <li key={a.id}>
                            <button
                                type="button"
                                className="btn block"
                                aria-pressed={a.id === selected}
                                onClick={() => setSelected(a.id)}
                            >
                                <b>{a.name}</b>
                                {a.model && ` (${a.model})`} / {TECH_LABEL[a.technicalState]}
                            </button>
                            <p className="xs">
                                근거: {SOURCE_LABEL[ev.sourceKind]}
                                {ev.confirmed ? ' / 확인됨' : ' / 미확인'} / 관측{' '}
                                {ev.observedAt ? displayTime(ev.observedAt) : '시각 미상'} / 기록{' '}
                                {displayTime(ev.recordedAt)}
                            </p>
                            <ul className="xs" aria-label={`${a.name} 사용 시간표`}>
                                {reservationsOf(a.id).map((r) => (
                                    <li key={r.id}>
                                        {displayTime(r.startAt)} ~ {displayTime(r.endAt)} /{' '}
                                        {RESERVATION_LABEL[r.status]}
                                    </li>
                                ))}
                                {!reservationsOf(a.id).length && <li>예약 없음</li>}
                            </ul>
                        </li>
                    );
                })}
            </ul>
            {selected && (
                <section aria-label="장비 상세" className="stack">
                    {detailMessage && <p role="status">{detailMessage}</p>}
                    {detail && (
                        <>
                            <h2 className="h3">{detail.asset.name}</h2>
                            <p className="small">
                                {TECH_LABEL[detail.asset.technicalState]} / 포트{' '}
                                {detail.asset.ports.map((p) => p.key).join(', ') || '없음'}
                            </p>
                            <b>연결</b>
                            <ul className="small">
                                {detail.connections.map((c) => (
                                    <li key={c.id}>
                                        {names.get(c.fromAssetId) ?? '다른 장비'}:{c.fromPort} →{' '}
                                        {names.get(c.toAssetId) ?? '다른 장비'}:{c.toPort}
                                        {c.active ? '' : ' (해제됨)'} / 확인{' '}
                                        {displayTime(c.confirmedAt)}
                                    </li>
                                ))}
                                {!detail.connections.length && <li>확인된 연결 없음</li>}
                            </ul>
                            {chief && ops && (
                                <ObserveAsset
                                    key={`${detail.asset.id}:${detail.asset.revision}`}
                                    commands={commands}
                                    asset={detail.asset}
                                    eventRevision={ops.eventRevision}
                                    onChanged={() => setReload((n) => n + 1)}
                                />
                            )}
                            <b>필요 조건</b>
                            <ul className="small">
                                {detail.requirements.map((q) => (
                                    <li key={q.id}>
                                        {displayTime(q.readyBy)}까지 {q.quantity}개 /{' '}
                                        {q.scheduleMode === 'FIXED'
                                            ? '시간 고정'
                                            : '시간 변경 허용'}
                                    </li>
                                ))}
                                {!detail.requirements.length && <li>연결된 필요 조건 없음</li>}
                            </ul>
                        </>
                    )}
                </section>
            )}
            {chief && ops && (
                <>
                    <RegisterAsset
                        commands={commands}
                        refs={ops.refs}
                        eventRevision={ops.eventRevision}
                        onChanged={() => setReload((n) => n + 1)}
                    />
                    {data && (
                        <RelationsPanel
                            commands={commands}
                            assets={data.assets}
                            refs={ops.refs}
                            eventRevision={ops.eventRevision}
                            onChanged={() => setReload((n) => n + 1)}
                        />
                    )}
                </>
            )}
        </EntryFrame>
    );
}
