import {
    useEffect,
    useLayoutEffect,
    useRef,
    useState,
    type DragEvent,
    type PointerEvent,
    type ReactNode,
} from 'react';
import { useData, useEvent, useSession, useViewer } from '../../domain/hooks';
import {
    groupName,
    isLate,
    issueGroupOf,
    membersOf,
    openTasksOf,
    shortRole,
    tasksOf,
    unassignedBundles,
} from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { EVENT_DATE } from '../../domain/seed';
import type { EventData, Member, Task, WorkGroup } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { TaskCard } from '../task/TaskCard';
import { InviteDialog } from '../members/InviteDialog';
import { Dialog } from '../../shared/ui/Dialog';
import { Icon } from '../../shared/ui/Icon';
import { NameCircle } from '../../shared/ui/NameCircle';
import { Chip } from '../../shared/ui/atoms';
import { AssignBadge, BundleAssign, ReserveActions } from './MemberParts';
import styles from './member.module.css';

/** 구성도에서 팀원 카드를 끌어 옮길 때 싣는 값 */
const MIME = 'application/x-scenecrew-member';

/** 그룹의 미해결 문제(종결 전 이슈, V9-D50). 이슈의 그룹으로 고른다(구역 이름과 비교하지 않는다) */
const unresolvedOf = (d: EventData, groupId: string) =>
    d.issues.filter((i) => i.status !== 'CLOSED' && issueGroupOf(d, i) === groupId);

/**
 * 구성도(총괄 WEB W02 운영 현황): 총괄 → 직무 그룹 → 그룹 안 팀원 가로 계층도가 화면을 채운다.
 * 와이어프레임 조직도의 배치를 살리되 평면 직무 그룹이라 책임자·상하 권한은 없다.
 * 그룹은 평면 분류이고, 같은 그룹 팀원은 모두 같은 단계에 나란히 놓인다.
 * 따로 상세 패널을 두지 않고, 카드나 ▼를 누르면 그 자리에서 상세가 펼쳐진다.
 */
export function MemberTree({ phase }: { phase?: ReactNode }) {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const dispatch = useDispatch();
    const { me } = useSession();
    const [expandedIds, setExpandedIds] = useState<Set<string>>(() => new Set());
    // 구성도 배율(1 = 100%). 마지막 값을 이 브라우저에 기억한다
    const [zoomSet, setZoom] = useState(readZoom);
    useEffect(() => {
        try {
            localStorage.setItem(ZOOM_KEY, String(zoomSet));
        } catch {
            // 저장하지 못해도 지금 화면 배율은 바뀐다
        }
    }, [zoomSet]);
    // 전체가 화면에 들어오는 배율. 최소 배율은 이 값 이하라서, 가장 작게 하면 인원이 아무리 많아도 모두 보인다
    const canvasRef = useRef<HTMLElement>(null);
    const treeRef = useRef<HTMLUListElement>(null);
    const [fit, setFit] = useState(ZOOM_FLOOR);
    const fitRef = useRef(ZOOM_FLOOR);
    const applyFit = (next: number | null) => {
        if (next === null || next === fitRef.current) return;
        fitRef.current = next;
        setFit(next);
    };
    // 화면 크기가 바뀌면 다시 잰다
    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ro = new ResizeObserver(() => applyFit(measureFit(canvas, treeRef.current)));
        ro.observe(canvas);
        return () => ro.disconnect();
    }, []);
    // 인원·그룹이 늘거나 상세를 펼쳐 구성도 크기가 바뀌면 그린 직후 다시 잰다(같은 값이면 다시 그리지 않는다)
    useLayoutEffect(() => applyFit(measureFit(canvasRef.current, treeRef.current)));
    const minZoom = Math.min(fit, ZOOM_FLOOR);
    const zoom = clampZoom(zoomSet, minZoom);
    // Ctrl+마우스 휠: 브라우저 전체 확대 대신 구성도 배율만 바꾼다(React onWheel은 기본 동작을 막지 못해 직접 붙인다)
    useEffect(() => {
        const el = canvasRef.current;
        if (!el) return;
        const onWheel = (e: WheelEvent) => {
            if (!e.ctrlKey) return;
            e.preventDefault();
            const min = Math.min(fitRef.current, ZOOM_FLOOR);
            setZoom((z) =>
                clampZoom(clampZoom(z, min) + (e.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP), min),
            );
        };
        el.addEventListener('wheel', onWheel, { passive: false });
        return () => el.removeEventListener('wheel', onWheel);
    }, []);
    // 배경을 끌어 화면 옮기기(지도처럼). 카드·단추·펼친 상세·팝업 위에서 누르면 원래 동작을 한다
    const pan = useRef<{ id: number; x: number; y: number } | null>(null);
    const [panning, setPanning] = useState(false);
    const panStart = (e: PointerEvent<HTMLElement>) => {
        if (e.button !== 0) return;
        const t = e.target as HTMLElement;
        const onBackground =
            t === e.currentTarget ||
            (!!treeRef.current?.contains(t) &&
                !t.closest(
                    `button, a, input, select, textarea, [draggable="true"], .${styles.drop}`,
                ));
        if (!onBackground) return;
        pan.current = { id: e.pointerId, x: e.clientX, y: e.clientY };
        e.currentTarget.setPointerCapture(e.pointerId);
        setPanning(true);
    };
    const panMove = (e: PointerEvent<HTMLElement>) => {
        const p = pan.current;
        if (!p || p.id !== e.pointerId) return;
        e.currentTarget.scrollBy(p.x - e.clientX, p.y - e.clientY);
        pan.current = { ...p, x: e.clientX, y: e.clientY };
    };
    const panEnd = (e: PointerEvent<HTMLElement>) => {
        if (pan.current?.id !== e.pointerId) return;
        pan.current = null;
        setPanning(false);
    };
    // 총괄: 팀원을 다른 그룹으로 끌어 옮기고, 그룹에 인원을 초대한다(끝난 공연은 고치지 않는다)
    const movable = v.isChief && ev.state !== 'ENDED';
    const [over, setOver] = useState<string | null>(null);
    const [move, setMove] = useState<Move | null>(null);
    const [invite, setInvite] = useState<{ group: WorkGroup; ask: boolean } | null>(null);
    // 그룹 카드의 +로 만든 초대 자리 수(그룹별). 화면 자리일 뿐이라 이 브라우저 탭에만 기억한다
    const { eventId } = useSession();
    const slotKey = `scenecrew-invite-slots-${eventId ?? ''}`;
    const [slots, setSlots] = useState<Record<string, Slots>>(() => readSlots(slotKey));
    const workersIn = (groupId: string) =>
        membersOf(d, groupId).filter((m) => m.role !== 'CHIEF').length;
    /**
     * 아직 비어 있는 초대 자리 수. 자리를 만든 뒤 그 그룹에 새로 들어온 인원만큼 위에서부터 채워져
     * 줄어든다(인원은 참여한 순서대로 목록에 놓인다)
     */
    const openSlots = (groupId: string) => {
        const s = slots[groupId];
        return s ? Math.max(0, s.n - Math.max(0, workersIn(groupId) - s.base)) : 0;
    };
    /** 그룹의 빈 초대 자리 수를 by만큼 바꾼다(+n 추가, -1 삭제). 지금 인원 수를 새 기준으로 남긴다 */
    const changeSlots = (groupId: string, by: number) => {
        const n = Math.max(0, openSlots(groupId) + by);
        const next = { ...slots };
        if (n) next[groupId] = { n, base: workersIn(groupId) };
        else delete next[groupId];
        setSlots(next);
        try {
            sessionStorage.setItem(slotKey, JSON.stringify(next));
        } catch {
            // 저장하지 못해도 이 화면에서는 자리가 보인다
        }
    };
    const toggle = (id: string) =>
        setExpandedIds((prev) => {
            const next = new Set(prev);
            if (!next.delete(id)) next.add(id);
            return next;
        });
    const chief = d.members.find((m) => m.role === 'CHIEF');
    // 미해결 문제(종결 전 이슈, V9-D50)가 있는 그룹을 강조한다.
    const unresolvedIn = (g: WorkGroup) => unresolvedOf(d, g.id).length;

    /** 노드 카드 + ▼. 카드를 눌러도 ▼와 똑같이 상세가 펼쳐진다. extra는 카드 안 오른쪽에 겹쳐 두는 단추다 */
    const toggleRow = (
        key: string,
        label: string,
        className: string,
        body: ReactNode,
        extra?: ReactNode,
    ) => {
        const expanded = expandedIds.has(key);
        const panelId = `drop-${key}`;
        const card = (
            <button
                type="button"
                className={`${styles.node} ${className} ${extra ? styles.withExtra : ''}`}
                aria-expanded={expanded}
                aria-controls={panelId}
                onClick={() => toggle(key)}
            >
                {body}
            </button>
        );
        return (
            <div className={styles.nodeRow}>
                {extra ? (
                    <div className={styles.nodeWrap}>
                        {card}
                        {extra}
                    </div>
                ) : (
                    card
                )}
                <button
                    type="button"
                    className={styles.caret}
                    aria-expanded={expanded}
                    aria-controls={panelId}
                    aria-label={`${label} 상세 ${expanded ? '접기' : '펼치기'}`}
                    onClick={() => toggle(key)}
                >
                    <span aria-hidden="true">▼</span>
                </button>
            </div>
        );
    };

    /** 다른 그룹 카드·인원 줄에 팀원을 놓았을 때: 이동할지 먼저 묻는다 */
    const dropOn = (e: DragEvent, g: WorkGroup) => {
        e.preventDefault();
        setOver(null);
        const member = d.members.find((m) => m.id === e.dataTransfer.getData(MIME));
        if (!member || member.groupId === g.id) return;
        const open = openTasksOf(d, member.id);
        setMove({
            member,
            to: g,
            blocked: open.some((t) => t.status === 'doing'),
            waiting: open.filter((t) => t.status === 'todo').length,
        });
    };
    const dropZone = (g: WorkGroup) =>
        movable
            ? {
                  onDragOver: (e: DragEvent) => {
                      if (!e.dataTransfer.types.includes(MIME)) return;
                      e.preventDefault();
                      e.dataTransfer.dropEffect = 'move';
                      if (over !== g.id) setOver(g.id);
                  },
                  onDragLeave: (e: DragEvent) => {
                      // 그룹 안의 카드 위로 지나갈 때는 강조를 유지한다
                      if (e.currentTarget.contains(e.relatedTarget as Node | null)) return;
                      setOver((k) => (k === g.id ? null : k));
                  },
                  onDrop: (e: DragEvent) => dropOn(e, g),
              }
            : {};

    const memberNode = (m: Member) => {
        const late = tasksOf(d, m.id).some(isLate);
        return (
            <li
                key={m.id}
                draggable={movable && m.role === 'WORKER'}
                onDragStart={(e) => {
                    e.dataTransfer.setData(MIME, m.id);
                    e.dataTransfer.effectAllowed = 'move';
                }}
                onDragEnd={() => setOver(null)}
            >
                {toggleRow(
                    m.id,
                    m.name,
                    `${styles.memberNode} ${late ? styles.hot : ''} ${m.id === me ? styles.me : ''} ${m.isReserve ? styles.reserve : ''} ${m.available ? '' : styles.offNode}`,
                    <>
                        <NameCircle name={m.name} role={shortRole(m)} size="sm" />
                        {/* 이름만 보이고, 예비 여부·업무 상태는 이름 옆에 붙인다 */}
                        <span className={`grow ${styles.nameLine}`}>
                            <b className="small">
                                {m.name}
                                {m.id === me && ' (나)'}
                            </b>
                            <MemberStatus member={m} />
                        </span>
                    </>,
                )}
                {expandedIds.has(m.id) && <MemberDrop id={`drop-${m.id}`} member={m} />}
            </li>
        );
    };

    return (
        <section
            ref={canvasRef}
            className={styles.canvas}
            aria-label="구조도"
            data-panning={panning || undefined}
            onPointerDown={panStart}
            onPointerMove={panMove}
            onPointerUp={panEnd}
            onPointerCancel={panEnd}
        >
            {phase && <div className={styles.phase}>{phase}</div>}
            {/* 지도처럼 확대/축소: 왼쪽 세로 + / 배율 막대 / − */}
            <MapZoom
                value={zoom}
                min={minZoom}
                fit={Math.min(fit, ZOOM_MAX)}
                onChange={(z) => setZoom(clampZoom(z, minZoom))}
            />
            <ul ref={treeRef} className={styles.tree} style={{ zoom }}>
                <li>
                    {toggleRow(
                        'root',
                        '행사 전체',
                        `${styles.root} ${chief?.id === me ? styles.me : ''}`,
                        <>
                            {chief && <NameCircle name={chief.name} role="총괄" size="sm" />}
                            <span className="grow">
                                <b className="small">총괄 / {chief?.name}</b>
                                <span className="xs muted" style={{ display: 'block' }}>
                                    그룹 {d.groups.length} / 인원 {d.members.length}
                                </span>
                            </span>
                        </>,
                    )}
                    {expandedIds.has('root') && <RootDrop id="drop-root" chiefId={chief?.id} />}
                    <ul>
                        {d.groups.map((g) => {
                            const people = membersOf(d, g.id).filter((m) => m.role !== 'CHIEF');
                            const open = unassignedBundles(d, g.id).length;
                            const unresolved = unresolvedIn(g);
                            return (
                                <li
                                    key={g.id}
                                    className={styles.groupBranch}
                                    data-over={over === g.id || undefined}
                                    {...dropZone(g)}
                                >
                                    {toggleRow(
                                        g.id,
                                        `${g.name} 그룹`,
                                        `${styles.groupNode} ${unresolved ? styles.unresolvedNode : ''}`,
                                        <>
                                            <span className="li">
                                                <Icon name="people" />
                                            </span>
                                            <span className="grow">
                                                <b className="small">{g.name}</b>
                                                <span
                                                    className="xs muted"
                                                    style={{ display: 'block' }}
                                                >
                                                    팀원 {people.length} / 가용{' '}
                                                    {people.filter((m) => m.available).length}
                                                    {open > 0 && ` / 미배정 묶음 ${open}`}
                                                </span>
                                                {unresolved > 0 && (
                                                    <span className={styles.nodeChips}>
                                                        <Chip kind="bad">
                                                            <Icon name="warn" /> 미해결 {unresolved}
                                                        </Chip>
                                                    </span>
                                                )}
                                            </span>
                                        </>,
                                        // 그룹 카드 안 오른쪽: 인원 추가(먼저 새로 추가할지 묻는다)
                                        movable && (
                                            <button
                                                type="button"
                                                className={styles.addMember}
                                                aria-label={`${g.name}에 인원 추가`}
                                                title="인원 추가"
                                                onClick={() => setInvite({ group: g, ask: true })}
                                            >
                                                <span aria-hidden="true">+</span>
                                            </button>
                                        ),
                                    )}
                                    {expandedIds.has(g.id) && (
                                        <GroupDrop id={`drop-${g.id}`} group={g} />
                                    )}
                                    <ul>
                                        {people.map(memberNode)}
                                        {/* +로 만든 초대 자리: 그룹 인원 맨 밑에 남고, 누르면 언제든 초대 링크를 다시 준다 */}
                                        {Array.from(
                                            { length: movable ? openSlots(g.id) : 0 },
                                            (_, k) => (
                                                <li key={`slot-${k}`}>
                                                    <div className={styles.nodeRow}>
                                                        <div className={styles.nodeWrap}>
                                                            <button
                                                                type="button"
                                                                className={`${styles.node} ${styles.inviteSlot}`}
                                                                onClick={() =>
                                                                    setInvite({
                                                                        group: g,
                                                                        ask: false,
                                                                    })
                                                                }
                                                            >
                                                                <span
                                                                    className={styles.inviteCircle}
                                                                    aria-hidden="true"
                                                                >
                                                                    +
                                                                </span>
                                                                <span className="small">
                                                                    인원 초대
                                                                </span>
                                                            </button>
                                                            {/* 초대 자리 삭제(카드 안 오른쪽): 화면 자리만 지운다(이미 보낸 초대 링크는 그대로) */}
                                                            <button
                                                                type="button"
                                                                className={styles.slotDelete}
                                                                aria-label={`${g.name} 인원 초대 자리 삭제`}
                                                                title="초대 자리 삭제"
                                                                onClick={() =>
                                                                    changeSlots(g.id, -1)
                                                                }
                                                            >
                                                                X
                                                            </button>
                                                        </div>
                                                    </div>
                                                </li>
                                            ),
                                        )}
                                    </ul>
                                </li>
                            );
                        })}
                    </ul>
                </li>
            </ul>
            {move && (
                <MoveDialog
                    move={move}
                    onClose={() => setMove(null)}
                    onConfirm={() => {
                        dispatch({
                            type: 'memberManage',
                            memberId: move.member.id,
                            patch: { groupId: move.to.id },
                        });
                        setMove(null);
                    }}
                />
            )}
            {invite && (
                <InviteDialog
                    groupId={invite.group.id}
                    groupName={invite.group.name}
                    ask={invite.ask}
                    onAdd={(count) => changeSlots(invite.group.id, count)}
                    onClose={() => setInvite(null)}
                />
            )}
        </section>
    );
}

const ZOOM_KEY = 'scenecrew-tree-zoom';
/** 기본 최소 배율. 전체가 이보다 작아야 화면에 들어가면 최소 배율을 그만큼 더 낮춘다 */
const ZOOM_FLOOR = 0.3;
/** 아무리 커져도 이보다 작게는 줄이지 않는다(글자를 알아볼 수 없는 값) */
const ZOOM_ABS_MIN = 0.05;
const ZOOM_MAX = 1.5;
/** 전체 맞춤에서 비워 둘 가로(왼쪽 막대·양옆 여백)·세로(위아래 여백) 픽셀 */
const FIT_PAD_X = 110;
const FIT_PAD_Y = 48;
const ZOOM_STEP = 0.1;
/** 배율을 범위(min~최대) 안으로 맞추고 소수 오차를 없앤다 */
const clampZoom = (z: number, min = ZOOM_ABS_MIN) =>
    Math.round(Math.min(ZOOM_MAX, Math.max(min, z)) * 100) / 100;

/**
 * 구성도 전체(총괄 아래 모든 그룹·인원)가 화면 한 칸에 들어오는 배율. 잴 수 없으면 null.
 * 100% 크기는 지금 보이는 크기 ÷ 지금 배율이다. 왼쪽 확대/축소 막대 자리와 위아래 여백을 뺀 칸에 맞춘다.
 */
function measureFit(canvas: HTMLElement | null, tree: HTMLElement | null): number | null {
    const body = tree?.firstElementChild;
    if (!canvas || !tree || !body) return null;
    const z = Number(getComputedStyle(tree).zoom) || 1;
    const r = body.getBoundingClientRect();
    const w = r.width / z;
    const h = r.height / z;
    if (!w || !h) return null;
    const room = Math.min(
        (canvas.clientWidth - FIT_PAD_X) / w,
        (canvas.clientHeight - FIT_PAD_Y) / h,
    );
    // 배율에 따라 글자·테두리 반올림으로 크기가 조금씩 달라져 5% 여유를 둔다
    return Math.max(ZOOM_ABS_MIN, Math.floor(room * 0.95 * 100) / 100);
}

/** 마지막으로 쓴 배율. 없거나 읽을 수 없으면 100% */
function readZoom() {
    try {
        const z = Number(localStorage.getItem(ZOOM_KEY));
        return z ? clampZoom(z) : 1;
    } catch {
        return 1;
    }
}

/** 구성도 확대/축소: 위 + · 가운데 끌어 조절하는 배율 막대 · 아래 −, 그 밑에 지금 배율 */
function MapZoom({
    value,
    min,
    fit,
    onChange,
}: {
    value: number;
    /** 지금 최소 배율(이때 전체가 화면에 들어온다) */
    min: number;
    /** 전체가 화면에 딱 들어오는 배율 */
    fit: number;
    onChange: (z: number) => void;
}) {
    const pct = Math.round(value * 100);
    return (
        <div className={styles.zoomBar} role="group" aria-label="구성도 확대/축소">
            <button
                type="button"
                className={styles.zoomBtn}
                aria-label="확대"
                disabled={value >= ZOOM_MAX}
                onClick={() => onChange(value + ZOOM_STEP)}
            >
                +
            </button>
            <input
                type="range"
                className={styles.zoomRange}
                aria-label="배율"
                aria-valuetext={`${pct}%`}
                min={min}
                max={ZOOM_MAX}
                step={0.01}
                value={value}
                onChange={(e) => onChange(Number(e.target.value))}
            />
            <button
                type="button"
                className={styles.zoomBtn}
                aria-label="축소"
                disabled={value <= min}
                onClick={() => onChange(value - ZOOM_STEP)}
            >
                −
            </button>
            <button
                type="button"
                className={styles.zoomPct}
                title="100%로 되돌리기"
                aria-label={`지금 배율 ${pct}%, 누르면 100%로`}
                onClick={() => onChange(1)}
            >
                {pct}%
            </button>
            <button
                type="button"
                className={styles.zoomFit}
                title="전체가 한 화면에 보이게"
                aria-label="전체 보기"
                onClick={() => onChange(fit)}
            >
                전체
            </button>
        </div>
    );
}

/** 저장해 둔 초대 자리 수. 없거나 읽을 수 없으면 빈 값 */
/** 그룹의 초대 자리: 만든 자리 수(n)와 그때의 그룹 인원 수(base) */
type Slots = { n: number; base: number };

function readSlots(key: string): Record<string, Slots> {
    try {
        const raw: unknown = JSON.parse(sessionStorage.getItem(key) ?? '{}');
        if (!raw || typeof raw !== 'object') return {};
        return Object.fromEntries(
            Object.entries(raw).filter((e): e is [string, Slots] => {
                const v = e[1] as Partial<Slots> | null;
                return (
                    !!v &&
                    Number.isInteger(v.n) &&
                    (v.n ?? 0) > 0 &&
                    Number.isInteger(v.base) &&
                    (v.base ?? -1) >= 0
                );
            }),
        );
    } catch {
        return {};
    }
}

type Move = {
    member: Member;
    to: WorkGroup;
    /** 진행 중 업무가 있어 옮길 수 없다 */
    blocked: boolean;
    /** 옮기면 담당이 해제되는 대기 업무 수 */
    waiting: number;
};

/** 소속 이동 확인: A그룹 → B그룹으로 옮길지 예/아니요로 묻는다 */
function MoveDialog({
    move,
    onClose,
    onConfirm,
}: {
    move: Move;
    onClose: () => void;
    onConfirm: () => void;
}) {
    const d = useData();
    const from = groupName(d, move.member.groupId);
    return (
        <Dialog title={move.blocked ? '소속을 옮길 수 없습니다' : '소속 이동'} onClose={onClose}>
            <div className="stack">
                {move.blocked ? (
                    <p>
                        <b>{move.member.name}</b>님은 진행 중인 업무가 있습니다. 업무를 마친 뒤 옮길
                        수 있습니다.
                    </p>
                ) : (
                    <>
                        <p>
                            <b>{move.member.name}</b>님을 <b>{from}</b> → <b>{move.to.name}</b>
                            (으)로 이동하시겠습니까?
                        </p>
                        {move.waiting > 0 && (
                            <p className="small muted">
                                옮기면 대기 중인 업무 {move.waiting}건의 담당이 해제됩니다.
                            </p>
                        )}
                    </>
                )}
                <div className="row" style={{ justifyContent: 'flex-end' }}>
                    {move.blocked ? (
                        <button type="button" className="btn primary" onClick={onClose}>
                            확인
                        </button>
                    ) : (
                        <>
                            <button type="button" className="btn ghost" onClick={onClose}>
                                아니요
                            </button>
                            <button type="button" className="btn primary" onClick={onConfirm}>
                                예
                            </button>
                        </>
                    )}
                </div>
            </div>
        </Dialog>
    );
}

/** 이름 옆 상태: 예비 · 업무 지연/진행/완료 · 내가 올린 열린 보고 · 최초 배정 상태 */
function MemberStatus({ member: m }: { member: Member }) {
    const d = useData();
    const tasks = tasksOf(d, m.id).filter((t) => t.status !== 'canceled');
    const late = tasks.some(isLate);
    const doing = !late && tasks.some((t) => t.status === 'doing');
    const done = tasks.length > 0 && tasks.every((t) => t.status === 'done');
    const reports = d.issues.filter((i) => i.reporter === m.id && i.status !== 'CLOSED').length;
    return (
        <>
            {m.isReserve && <Chip kind="violet">예비</Chip>}
            {late && <Chip kind="bad">지연</Chip>}
            {doing && <Chip kind="accent">진행</Chip>}
            {done && <Chip kind="ok">완료</Chip>}
            {reports > 0 && <Chip kind="warn">보고 {reports}</Chip>}
            {m.assign && m.assign !== 'DONE' && <AssignBadge state={m.assign} />}
        </>
    );
}

/** 펼친 노드의 업무 목록. 날짜별로 묶고 날짜 안에서는 시작 시각 순이다. */
function TasksByDate({ memberId }: { memberId: string }) {
    const d = useData();
    const ev = useEvent();
    const byDate = new Map<string, Task[]>();
    for (const t of tasksOf(d, memberId)) {
        const key = t.date ?? EVENT_DATE;
        byDate.set(key, [...(byDate.get(key) ?? []), t]);
    }
    const dates = [...byDate.keys()].sort();
    return (
        <div className={styles.taskDrop}>
            {dates.length ? (
                dates.map((date) => (
                    <section key={date}>
                        <h4 className="xs muted">
                            {date === EVENT_DATE ? `${ev.date} / 행사 당일` : date}
                        </h4>
                        <div className={styles.taskCards}>
                            {byDate
                                .get(date)!
                                .sort((a, b) => a.start.localeCompare(b.start))
                                .map((t) => (
                                    <TaskCard key={t.id} task={t} compact lines showWho={false} />
                                ))}
                        </div>
                    </section>
                ))
            ) : (
                <p className="xs muted">배정된 업무가 없습니다</p>
            )}
        </div>
    );
}

/** 총괄 노드 펼침: 행사 전체 집계 + 총괄 본인 업무 */
function RootDrop({ id, chiefId }: { id: string; chiefId?: string }) {
    const d = useData();
    const tasks = d.tasks.filter((t) => t.status !== 'canceled');
    const openIssues = d.issues.filter((i) => i.status !== 'CLOSED');
    return (
        <div id={id} className={styles.drop}>
            <div className={`${styles.dropCard} stack s6`}>
                <b className="small">행사 전체</b>
                <dl className="kv">
                    <dt>업무</dt>
                    <dd>
                        {tasks.length}건 / 진행 {tasks.filter((t) => t.status === 'doing').length} /
                        지연 {tasks.filter(isLate).length} / 미배정{' '}
                        {tasks.filter((t) => !t.assignee).length}
                    </dd>
                    <dt>이슈</dt>
                    <dd>열림 {openIssues.length}</dd>
                </dl>
            </div>
            {chiefId && <TasksByDate memberId={chiefId} />}
        </div>
    );
}

/** 그룹 노드 펼침: 그룹 안내 · 열린 이슈 · 미배정 업무 묶음 수동 배정 */
function GroupDrop({ id, group: g }: { id: string; group: WorkGroup }) {
    const d = useData();
    const ev = useEvent();
    const shell = useShell();
    const issues = unresolvedOf(d, g.id);
    const open = unassignedBundles(d, g.id);
    return (
        <div id={id} className={styles.drop}>
            <div className={`${styles.dropCard} stack s6`}>
                <b className="small">주의사항</b>
                <p className="small">{g.guide}</p>
                <b className="small">열린 이슈 {issues.length}</b>
                {issues.length ? (
                    issues.map((i) => (
                        <button
                            key={i.id}
                            type="button"
                            className="linkbtn"
                            style={{ display: 'block' }}
                            onClick={() => shell.openIssue(i.id)}
                        >
                            {i.title} ›
                        </button>
                    ))
                ) : (
                    <p className="small muted">열린 이슈가 없습니다</p>
                )}
                <b className="small">
                    미배정 업무 {open.reduce((n, b) => n + b.tasks.length, 0)}건
                </b>
                {open.length ? (
                    open.map((b) => (
                        <BundleAssign key={b.key} bundleKey={b.key} live={ev.state === 'LIVE'} />
                    ))
                ) : (
                    <p className="small muted">모든 묶음에 담당이 있습니다</p>
                )}
            </div>
        </div>
    );
}

/** 팀원 노드 펼침: "항목: 값" 줄로 가용·예비 상태와 총괄 조치(AI 재요청·예비 투입) + 날짜별 업무 */
function MemberDrop({ id, member: m }: { id: string; member: Member }) {
    const d = useData();
    const ev = useEvent();
    const dispatch = useDispatch();
    const live = !!useDemoState().live;
    const retry = m.assign && m.assign !== 'DONE' && m.assign !== 'RUNNING';
    const pending = d.notices.filter(
        (n) => n.to === m.id && !n.ack && (n.kind === 'change' || n.kind === 'deploy'),
    );
    return (
        <div id={id} className={styles.drop}>
            <div className={`${styles.dropCard} stack s6`}>
                <dl className={styles.facts}>
                    <div>
                        <dt>가용</dt>
                        <dd>{m.avail}</dd>
                    </div>
                    {m.isReserve && (
                        <>
                            <div>
                                <dt>진행할 업무</dt>
                                <dd>{openTasksOf(d, m.id).length}건</dd>
                            </div>
                            <div>
                                <dt>공식 미확인</dt>
                                <dd>{pending.length}건</dd>
                            </div>
                        </>
                    )}
                    {m.skills && (
                        <div>
                            <dt>가능 업무</dt>
                            <dd>{m.skills}</dd>
                        </div>
                    )}
                </dl>
                {/* AI 배정 재요청 API는 없다(#373). 실서버에서는 숨긴다 */}
                {retry && ev.state === 'LIVE' && !live && (
                    <button
                        type="button"
                        className="btn sm"
                        onClick={() => dispatch({ type: 'assignRetry', memberId: m.id })}
                    >
                        AI 재요청
                    </button>
                )}
                <ReserveActions key={m.id} m={m} />
            </div>
            <TasksByDate memberId={m.id} />
        </div>
    );
}
