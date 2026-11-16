import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useData, useEvent, usePhase, useViewer } from '../../domain/hooks';
import {
    EQUIP_TECH_LABEL,
    NOW_MIN,
    ago,
    fmtMin,
    nameOf,
    reservationConflicts,
    reservationNow,
    toMin,
} from '../../domain/selectors';
import { NOW } from '../../domain/seed';
import { newId, useDemoState, useDispatch } from '../../domain/store-context';
import type { EquipTech, RecoveryOption } from '../../domain/types';
import { ChangeReview } from '../../features/change/ChangeReview';
import { EquipmentImport } from '../../features/equipment/EquipmentImport';
import { RecoveryCompare } from '../../features/equipment/RecoveryCompare';
import {
    AutoChip,
    AutoDetail,
    SignalPath,
    TechChip,
    VerdictChip,
} from '../../features/equipment/EquipmentParts';
import { Dialog } from '../../shared/ui/Dialog';
import { Chip, Empty, Note, Tabs } from '../../shared/ui/atoms';
import { Icon } from '../../shared/ui/Icon';
import { axisSlots, useTimeZoom } from '../../shared/ui/useTimeZoom';
import { ZoomButtons } from '../../shared/ui/ZoomButtons';
import styles from './ops.module.css';

/** 시간표 왼쪽 고정 열(장비 이름) 폭 */
const NAME_W = 190;

type DetailTab = 'info' | 'path';

/**
 * W03 장비 운영(총괄 WEB): 장비 행 × 시간 열의 준비/사용/회수/예약·충돌과 지금 시각선.
 * 장비를 고르면 기술 상태·사용/예약·출처/시각·신호 경로를, 관련 보고를 고르면 복구 대안(D03)을 연다.
 * 대안 선택은 미리 보기이며 변경안(X01) 승인 전에는 예약·담당·물리 상태를 바꾸지 않는다.
 * 값은 v9 목표 계약(EXT01~09)의 예시 데이터다.
 */
export function WebEquipment() {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const { now } = usePhase();
    const navigate = useNavigate();
    const dispatch = useDispatch();
    const live = !!useDemoState().live;
    const [params, setParams] = useSearchParams();
    const issueId = params.get('issue');
    const [kind, setKind] = useState('all');
    const [tech, setTech] = useState<EquipTech | 'all'>('all');
    // 고른 장비(?asset=)와 상세 탭(?detail=)은 주소에 두어 새로고침해도 그대로 연다
    const sel = params.get('asset') ?? d.equipment[0]?.id;
    const tab: DetailTab = params.get('detail') === 'path' ? 'path' : 'info';
    const pick = (id: string, extra: Record<string, string> = {}, push = false) =>
        setParams(
            (old) => {
                const p = new URLSearchParams(old);
                p.set('asset', id);
                p.delete('detail');
                for (const [k, v] of Object.entries(extra)) p.set(k, v);
                return p;
            },
            { replace: !push },
        );
    const setSel = (id: string) => pick(id);
    const setTab = (t: DetailTab) =>
        setParams(
            (old) => {
                const p = new URLSearchParams(old);
                if (t === 'info') p.delete('detail');
                else p.set('detail', t);
                return p;
            },
            { replace: true },
        );
    const [preview, setPreview] = useState<string | null>(null);
    // 장비 등록·자료 가져오기 팝업
    const [register, setRegister] = useState(false);
    // 복구 대안 팝업 안에서 연 변경안
    const [changeId, setChangeId] = useState<string | null>(null);
    // 대안을 "미리 보기"하면 팝업을 맨 위로 올려 비교 시간표가 바로 보이게 한다
    const compareRef = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (preview) compareRef.current?.parentElement?.scrollTo({ top: 0, behavior: 'smooth' });
    }, [preview]);

    // 알림 목록 팝업(?alerts=1). 목록에서 연 복구 대안은 ?alerts=1&issue=… 로 남아 목록으로 되돌아갈 수 있다
    const fromList = params.get('alerts') === '1';
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.key.toLowerCase() !== 'z') return;
            // 입력칸의 Ctrl+Z는 글자 되돌리기로 둔다
            if ((e.target as HTMLElement | null)?.closest?.('input, textarea, select')) return;
            // 변경안을 쓰는 중에는 글자 칸 밖에서 눌러도 화면을 벗어나 쓰던 글을 잃지 않게 한다
            if (document.querySelector('[data-change-editing]')) return;
            if (changeId) {
                e.preventDefault();
                setChangeId(null);
            } else if (issueId && fromList) {
                e.preventDefault();
                setPreview(null);
                setParams({ alerts: '1' });
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [changeId, issueId, fromList, setParams]);

    // 시간 축: 모든 예약과 지금 시각을 담는 범위(30분 단위). 처음에는 지금 시각을 가운데에 둔다
    const axisMins = [NOW_MIN, ...d.reservations.flatMap((r) => [toMin(r.start), toMin(r.end)])];
    const t0 = Math.floor(Math.min(...axisMins) / 30) * 30;
    const t1 = Math.ceil(Math.max(...axisMins) / 30) * 30;
    const zoom = useTimeZoom({ initial: 14, t0, centerMin: NOW_MIN });
    const { px } = zoom;

    if (!v.canViewEquipment)
        return <Empty icon="lock">장비 현황은 총괄과 장비 담당 그룹만 볼 수 있습니다</Empty>;
    // 장비 담당 그룹의 팀원은 조회만 한다(V9-D13): 등록·복구 대안·변경안을 보이지 않는다
    const readOnly = !v.canManageEquipment;

    const blocking = d.issues.filter(
        (i) =>
            i.status !== 'CLOSED' &&
            (i.situation?.primary === 'EQUIPMENT_ANOMALY' ||
                i.situation?.related.includes('EQUIPMENT_ANOMALY')),
    );
    const issue = readOnly ? null : (d.issues.find((i) => i.id === issueId) ?? null);
    const options = issue ? d.recovery.filter((o) => o.issueId === issue.id) : [];
    const picked = options.find((o) => o.id === preview) ?? null;
    const kinds = [...new Set(d.equipment.map((a) => a.kind))];
    const assets = d.equipment.filter(
        (a) => (kind === 'all' || a.kind === kind) && (tech === 'all' || a.tech === tech),
    );
    const asset = d.equipment.find((a) => a.id === sel);
    const pending = issue
        ? d.changes.find((c) => c.issueId === issue.id && c.status === 'READY')
        : undefined;

    const x = (m: number) => (m - t0) * px;
    const slots = axisSlots(t0, t1, px);
    const pickAlert = (id: string) => {
        setPreview(null);
        const target = d.equipment.find((a) => a.tech === 'FAULT');
        // 알림·이슈·장비를 한 번에 바꾼다(따로 바꾸면 앞의 변경이 덮인다)
        if (target) pick(target.id, { alerts: '1', issue: id }, true);
        else setParams({ alerts: '1', issue: id });
    };

    /** 장비 행 × 시간 열 시간표. 왼쪽 이름 열은 고정하고 오른쪽 시간만 가로로 스크롤한다 */
    const timeline = (list: typeof assets, label: string) => (
        <section
            ref={zoom.bindRoot}
            className={styles.timeline}
            aria-label={label}
            style={
                {
                    '--name-w': `${NAME_W}px`,
                    '--track-w': `${x(t1)}px`,
                    '--slot-w': `${px * 10}px`,
                } as CSSProperties
            }
        >
            <div className={styles.tlHead}>
                <span className={styles.tlCorner} />
                <div ref={zoom.bindOrigin} className={styles.tlScale}>
                    {slots.map((m) => (
                        <span key={m} style={{ left: x(m) }}>
                            {fmtMin(m)}
                        </span>
                    ))}
                </div>
            </div>
            {list.map((a) => {
                const conflicts = new Set(reservationConflicts(d, a.id).map((r) => r.id));
                const using = reservationNow(d, a.id);
                return (
                    <div key={a.id} className={styles.tlRow} aria-selected={a.id === sel}>
                        <button
                            type="button"
                            className={styles.tlName}
                            onClick={() => setSel(a.id)}
                        >
                            <b>{a.name}</b>
                            <span className="row">
                                <TechChip tech={a.tech} />
                                {a.auto && <AutoChip auto={a.auto} />}
                                <span className="xs muted">
                                    {using ? `${using.kind} 중` : '미사용'}
                                </span>
                            </span>
                        </button>
                        <div className={styles.tlTrack}>
                            <i className={styles.nowLine} style={{ left: x(NOW_MIN) }} />
                            {d.reservations
                                .filter((r) => r.assetId === a.id)
                                .map((r) => (
                                    <span
                                        key={r.id}
                                        className={styles.bar}
                                        data-kind={r.kind}
                                        data-conflict={conflicts.has(r.id) || undefined}
                                        style={{
                                            left: x(toMin(r.start)),
                                            width: x(toMin(r.end)) - x(toMin(r.start)),
                                        }}
                                        title={`${r.kind} ${r.start}–${r.end} ${r.label}`}
                                    >
                                        {r.label}
                                    </span>
                                ))}
                        </div>
                    </div>
                );
            })}
            {!list.length && <Empty>조건에 맞는 장비가 없습니다</Empty>}
        </section>
    );

    const makeChange = (o: RecoveryOption) => {
        const id = newId('C');
        dispatch({ type: 'recoveryChange', optionId: o.id, id });
        // 실서버: 만든 변경안은 다시 읽은 뒤 '확정 대기' 안내의 변경안 보기로 연다(#371)
        if (live) return;
        // 화면을 옮기지 않고 같은 팝업에서 변경안(X01)을 검토·확정한다
        setChangeId(id);
    };

    return (
        <div className="stack">
            {/* 화면 제목은 디자인상 숨기지만 스크린 리더가 화면을 구분할 수 있게 남긴다 */}
            <h1 className="sr-only">{readOnly ? '장비 현황' : '장비 운영'}</h1>
            {readOnly && (
                <div className="row">
                    <Chip>조회 전용</Chip>
                    {blocking.length > 0 && (
                        <span className="small">
                            <Icon name="warn" /> 장비 이상 보고 {blocking.length}건 / 대응은 총괄이
                            결정합니다
                        </span>
                    )}
                </div>
            )}

            {readOnly && (
                <Note>
                    장비 담당 그룹은 장비 상태와 사용 시간표를 조회만 합니다. 장비 등록/복구 대안
                    선택/변경 승인은 총괄이 하며, 본인 업무의 점검/교체 기록은 APP 업무 상세에서
                    남깁니다.
                </Note>
            )}

            <div className={styles.split}>
                {/* 왼쪽: 장비 종류·기술 상태·확대/축소 → 장비 시간표(장비 행 × 시간 열) → 범례 */}
                <div className="stack s6">
                    {/* 왼쪽 칸 맨 위: 지금 시각(크게) · 진행 중 공연. 오른쪽 칸(장비 등록·상세)은 위 여백 없이 맨 위부터 */}
                    <section className={styles.context}>
                        <div className={styles.clockLine}>
                            <b className={styles.clock}>{NOW}</b>
                            <span className={styles.nowShow}>
                                {now ? (
                                    <>
                                        <span className={styles.nowTag}>진행 중</span>
                                        <span className={styles.nowBar}>|</span>
                                        <b>{now.name}</b>{' '}
                                        <span className="mono xs">
                                            {now.start}–{now.end}
                                        </span>
                                    </>
                                ) : (
                                    <span className="muted">진행 중인 공연 없음</span>
                                )}
                            </span>
                        </div>
                    </section>
                    <div className={styles.toolRow}>
                        {/* 한 줄: 장비 종류·기술 상태 | 확대/축소. 범례는 시간표 아래 오른쪽 */}
                        <div className={styles.filterLine}>
                            <label>
                                <span className="lbl">장비 종류</span>
                                <select
                                    className="inp"
                                    value={kind}
                                    onChange={(e) => setKind(e.target.value)}
                                >
                                    <option value="all">전체</option>
                                    {kinds.map((k) => (
                                        <option key={k}>{k}</option>
                                    ))}
                                </select>
                            </label>
                            <label>
                                <span className="lbl">기술 상태</span>
                                <select
                                    className="inp"
                                    value={tech}
                                    onChange={(e) => setTech(e.target.value as EquipTech | 'all')}
                                >
                                    <option value="all">전체</option>
                                    {(Object.keys(EQUIP_TECH_LABEL) as EquipTech[]).map((k) => (
                                        <option key={k} value={k}>
                                            {EQUIP_TECH_LABEL[k]}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        </div>
                        <ZoomButtons zoom={zoom} />
                    </div>
                    {timeline(assets, '장비 시간표')}
                    {/* 시간표 아래 오른쪽: 준비·공연·회수·예약 범례 */}
                    <div
                        className={`${styles.legendRow} ${styles.legendBottom}`}
                        aria-label="시간표 범례"
                    >
                        {(['준비', '공연', '회수', '예약'] as const).map((k) => (
                            <span key={k} className={styles.legendItem} data-kind={k}>
                                {k}
                            </span>
                        ))}
                    </div>
                </div>

                {/* 오른쪽: 패널과 같은 너비의 장비 등록 버튼 → 장비 상세 패널 */}
                <div className={styles.sideCol}>
                    {!readOnly && (
                        <button
                            type="button"
                            className={`btn primary ${styles.sideRegister}`}
                            onClick={() => setRegister(true)}
                        >
                            <Icon name="file" /> 장비 등록
                        </button>
                    )}
                    <aside className={styles.side} aria-label="장비 상세">
                        {asset ? (
                            <>
                                <div className="row between">
                                    <b>{asset.name}</b>
                                    <TechChip tech={asset.tech} />
                                </div>
                                <Tabs<DetailTab>
                                    label="장비 상세"
                                    value={tab}
                                    onChange={setTab}
                                    items={[
                                        ['info', '정보/사용 계획'],
                                        ['path', '신호 경로'],
                                    ]}
                                />
                                {tab === 'info' ? (
                                    <>
                                        <dl className="kv">
                                            <dt>종류</dt>
                                            <dd>{asset.kind}</dd>
                                            <dt>위치</dt>
                                            <dd>{asset.place}</dd>
                                            <dt>출처</dt>
                                            <dd>
                                                {asset.source}
                                                {asset.observedAt && ` / ${asset.observedAt}`}
                                            </dd>
                                            {asset.note && (
                                                <>
                                                    <dt>메모</dt>
                                                    <dd>{asset.note}</dd>
                                                </>
                                            )}
                                        </dl>
                                        {asset.auto && <AutoDetail auto={asset.auto} />}
                                        <div className="stack s6">
                                            <span className="lbl">사용/예약</span>
                                            {d.reservations
                                                .filter((r) => r.assetId === asset.id)
                                                .map((r) => {
                                                    const p = ev.programs.find(
                                                        (x) => x.id === r.program,
                                                    );
                                                    return (
                                                        <span key={r.id} className="small">
                                                            <span className="mono">
                                                                {r.start}–{r.end}
                                                            </span>{' '}
                                                            {r.kind} / {r.label}
                                                            {p && ` / ${p.name}`}
                                                        </span>
                                                    );
                                                })}
                                        </div>
                                    </>
                                ) : (
                                    <SignalPath path={asset.path} />
                                )}
                            </>
                        ) : (
                            <Empty>장비를 고르세요</Empty>
                        )}
                    </aside>
                </div>
            </div>

            {/* 문제 상황 알림 전체 목록. 고르면 복구 대안으로 가고, Ctrl+Z로 여기로 돌아온다 */}
            {!readOnly && fromList && !issue && (
                <Dialog title={`문제 상황 알림 ${blocking.length}건`} onClose={() => setParams({})}>
                    <div className={styles.alertList}>
                        {blocking.length ? (
                            blocking.map((i) => (
                                <button
                                    key={i.id}
                                    type="button"
                                    className={styles.alert}
                                    title={i.title}
                                    onClick={() => pickAlert(i.id)}
                                >
                                    <b className={styles.bang} aria-hidden="true">
                                        !
                                    </b>
                                    <b className={styles.alertTitle}>{i.title}</b>
                                    <span className="xs">
                                        {i.zone} / {ago(i.at)} 전
                                    </span>
                                    <span className="xs">복구 대안 보기 ›</span>
                                </button>
                            ))
                        ) : (
                            <Empty>지금 문제 상황 알림이 없습니다</Empty>
                        )}
                    </div>
                </Dialog>
            )}

            {/* D03 복구 대안 */}
            {issue && (
                <Dialog
                    wide
                    title={`${changeId ? '변경안' : '복구 대안'} / ${issue.title}`}
                    sub={`${issue.zone} / 보고 ${issue.at} / 원인은 보고와 관측만으로 확정하지 않습니다`}
                    onClose={() => {
                        setChangeId(null);
                        setParams({});
                    }}
                >
                    {changeId ? (
                        <>
                            <div className="row">
                                <button
                                    type="button"
                                    className="btn sm ghost"
                                    onClick={() => setChangeId(null)}
                                >
                                    ‹ 복구 대안으로
                                </button>
                            </div>
                            <ChangeReview id={changeId} onDone={() => setChangeId(null)} />
                        </>
                    ) : (
                        <>
                            <div className="row between">
                                {fromList ? (
                                    <button
                                        type="button"
                                        className="btn sm ghost"
                                        title="Ctrl+Z"
                                        onClick={() => {
                                            setPreview(null);
                                            setParams({ alerts: '1' });
                                        }}
                                    >
                                        ‹ 알림 목록으로
                                    </button>
                                ) : (
                                    <span />
                                )}
                                <button
                                    type="button"
                                    className="btn sm"
                                    onClick={() => navigate(`/e/response/${issue.id}`)}
                                >
                                    보고/대응으로
                                </button>
                            </div>
                            {/* 맨 위: 고른 대안이 기존 시간표를 어떻게 바꾸는지 */}
                            <div ref={compareRef}>
                                <RecoveryCompare option={picked} />
                            </div>
                            {picked && (
                                <Note kind="warn">
                                    미리 보기 중: <b>{picked.title}</b>. 아직 적용되지 않았으며
                                    변경안 승인 전까지 예약/담당은 그대로입니다.
                                </Note>
                            )}
                            {pending && (
                                <Note kind="warn">
                                    이 보고에 확정 대기 중인 변경안이 있어 새 변경안을 만들 수
                                    없습니다. 먼저 확정하거나 폐기하세요: {pending.title}{' '}
                                    <button
                                        type="button"
                                        className="linkbtn"
                                        onClick={() => setChangeId(pending.id)}
                                    >
                                        변경안 보기
                                    </button>
                                </Note>
                            )}
                            <div className={styles.options}>
                                {options.map((o) => (
                                    <article
                                        key={o.id}
                                        className={styles.option}
                                        aria-current={o.id === preview || undefined}
                                    >
                                        <div className="row between">
                                            <b>{o.title}</b>
                                            <VerdictChip verdict={o.verdict} />
                                        </div>
                                        <dl className="kv">
                                            <dt>준비 완료</dt>
                                            <dd>
                                                {o.readyAt ?? '판단 불가'} / 기한 {o.deadline}
                                            </dd>
                                            <dt>여유</dt>
                                            <dd>{o.margin}</dd>
                                            <dt>작업</dt>
                                            <dd>
                                                {o.work
                                                    .map(
                                                        (w) =>
                                                            `${w.label} ${w.min == null ? '?' : `${w.min}분`}`,
                                                    )
                                                    .join(' → ')}
                                            </dd>
                                            <dt>담당</dt>
                                            <dd>{o.staff ? nameOf(d, o.staff) : '미정'}</dd>
                                        </dl>
                                        {o.effects.length > 0 && (
                                            <ul className="xs">
                                                {o.effects.map((e) => (
                                                    <li key={e}>{e}</li>
                                                ))}
                                            </ul>
                                        )}
                                        <p className="small">
                                            <b>
                                                {o.verdict === 'NO'
                                                    ? '제외 이유'
                                                    : o.verdict === 'UNKNOWN'
                                                      ? '판단 불가 이유'
                                                      : '선택 이유'}
                                                :
                                            </b>{' '}
                                            {o.reason}
                                        </p>
                                        {o.unknowns.length > 0 && (
                                            <p className="xs">
                                                <b>미확정 조건:</b> {o.unknowns.join(', ')}
                                            </p>
                                        )}
                                        {(o.verdict === 'OK' || o.verdict === 'CONDITIONAL') && (
                                            <div className="row">
                                                <button
                                                    type="button"
                                                    className="btn sm"
                                                    aria-pressed={o.id === preview}
                                                    onClick={() =>
                                                        setPreview(o.id === preview ? null : o.id)
                                                    }
                                                >
                                                    {o.id === preview
                                                        ? '미리 보기 끄기'
                                                        : '미리 보기'}
                                                </button>
                                                <button
                                                    type="button"
                                                    className="btn sm primary"
                                                    disabled={!!pending || ev.state !== 'LIVE'}
                                                    title={
                                                        pending
                                                            ? '이 보고에 확정 대기 중인 변경안이 있어 새로 만들 수 없습니다'
                                                            : ev.state !== 'LIVE'
                                                              ? '운영 중인 행사에서만 만들 수 있습니다'
                                                              : undefined
                                                    }
                                                    onClick={() => makeChange(o)}
                                                >
                                                    변경안 만들기
                                                </button>
                                            </div>
                                        )}
                                        {o.verdict === 'UNKNOWN' && (
                                            <span className="xs muted">
                                                입력을 보완하거나 현장 확인 뒤 다시 계산하세요.
                                            </span>
                                        )}
                                    </article>
                                ))}
                                {!options.length && (
                                    <Empty>
                                        계산된 대안이 없습니다. 현장 확인/수동 대응으로 진행하세요.
                                    </Empty>
                                )}
                            </div>
                            {live && (
                                <RecoveryRun
                                    issueId={issue.id}
                                    recalc={options.length > 0}
                                    disabled={ev.state !== 'LIVE'}
                                />
                            )}
                        </>
                    )}
                </Dialog>
            )}
            {register && (
                <Dialog
                    title="장비 등록"
                    sub="이름/종류/위치를 입력하거나 장비 목록 파일을 올립니다. 기술 상태는 미확인으로 시작합니다."
                    onClose={() => setRegister(false)}
                >
                    <EquipmentRegister
                        onAdded={(id) => {
                            setSel(id);
                            setRegister(false);
                        }}
                    />
                </Dialog>
            )}
        </div>
    );
}

/**
 * 실서버의 복구 대안 계산(EXT06). 고른 장비(기본: 이상·점검 필요 장비)로 계산을 요청한다.
 * 계산은 예약·담당을 바꾸지 않고, 끝나면 다시 읽어 대안을 보인다.
 */
function RecoveryRun({
    issueId,
    recalc,
    disabled,
}: {
    issueId: string;
    recalc: boolean;
    disabled: boolean;
}) {
    const d = useData();
    const dispatch = useDispatch();
    const [picked, setPicked] = useState<string[]>(() =>
        d.equipment.filter((a) => a.tech === 'FAULT' || a.tech === 'CHECK').map((a) => a.id),
    );
    return (
        <fieldset className="stack s6" style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className="lbl">복구할 장비</legend>
            {d.equipment.map((a) => (
                <label key={a.id} className="check">
                    <input
                        type="checkbox"
                        checked={picked.includes(a.id)}
                        onChange={(e) =>
                            setPicked((xs) =>
                                e.target.checked ? [...xs, a.id] : xs.filter((x) => x !== a.id),
                            )
                        }
                    />
                    {a.name} <TechChip tech={a.tech} />
                </label>
            ))}
            {!d.equipment.length && <span className="xs muted">등록된 장비가 없습니다.</span>}
            <div className="row">
                <button
                    type="button"
                    className="btn sm primary"
                    disabled={disabled || !picked.length}
                    onClick={() => dispatch({ type: 'recoveryRun', issueId, assetIds: picked })}
                >
                    {recalc ? '복구 대안 다시 계산' : '복구 대안 계산'}
                </button>
            </div>
        </fieldset>
    );
}

/** D01 장비 등록·자료 가져오기(팝업): 이름·종류·위치만 받고 기술 상태는 미확인으로 시작한다 */
function EquipmentRegister({ onAdded }: { onAdded: (id: string) => void }) {
    const dispatch = useDispatch();
    const [name, setName] = useState('');
    const [kind, setKind] = useState('');
    const [place, setPlace] = useState('');
    return (
        <>
            <form
                className="stack"
                onSubmit={(e) => {
                    e.preventDefault();
                    if (!name.trim() || !kind.trim()) return;
                    const id = newId('EQ');
                    dispatch({
                        type: 'addEquipment',
                        id,
                        name: name.trim(),
                        kind: kind.trim(),
                        place: place.trim() || '미확인',
                    });
                    onAdded(id);
                    setName('');
                    setKind('');
                    setPlace('');
                }}
            >
                <label className="field">
                    <span className="lbl">장비 이름 (필수)</span>
                    <input value={name} onChange={(e) => setName(e.target.value)} />
                </label>
                <label className="field">
                    <span className="lbl">종류 (필수)</span>
                    <input
                        value={kind}
                        placeholder="예) 무선 마이크"
                        onChange={(e) => setKind(e.target.value)}
                    />
                </label>
                <label className="field">
                    <span className="lbl">위치</span>
                    <input value={place} onChange={(e) => setPlace(e.target.value)} />
                </label>
                <button
                    type="submit"
                    className="btn primary"
                    disabled={!name.trim() || !kind.trim()}
                >
                    등록
                </button>
            </form>
            <hr className={styles.sep} />
            <EquipmentImport />
        </>
    );
}
