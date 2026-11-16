import { useRef, useState } from 'react';
import { useData, useEvent, useMe, useViewer } from '../../domain/hooks';
import {
    canTakeBundle,
    membersOf,
    openTasksOf,
    tasksOf,
    unassignedBundles,
} from '../../domain/selectors';
import { newId, useDemoState, useDispatch } from '../../domain/store-context';
import type { AssignState, Member } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { Icon, type IconName } from '../../shared/ui/Icon';
import { Chip, type ChipKind } from '../../shared/ui/atoms';
import styles from './member.module.css';

/** 최초 배정 상태 6가지. 색만으로 구분하지 않고 글자+아이콘을 함께 쓴다(IA03). */
const ASSIGN: Record<AssignState, [label: string, icon: IconName, kind: ChipKind]> = {
    WAITING: ['배정 대기', 'calendar', 'blue'],
    RUNNING: ['배정 작업 중', 'bolt', 'blue'],
    DONE: ['배정 완료', 'check', 'ok'],
    NO_CANDIDATE: ['맡을 묶음 없음', 'people', 'warn'],
    FAILED: ['배정 지연 / 재요청 필요', 'warn', 'warn'],
    UNKNOWN: ['배정 처리 확인 중', 'info', ''],
};

export function AssignBadge({ state }: { state: AssignState }) {
    const [label, icon, kind] = ASSIGN[state];
    return (
        <Chip kind={kind}>
            <Icon name={icon} /> {label}
        </Chip>
    );
}

const ASSIGN_TEXT: Record<AssignState, string> = {
    WAITING: '참여가 완료됐습니다. 업무 배정을 준비하고 있습니다.',
    RUNNING: '참여가 완료됐습니다. AI가 같은 그룹의 업무 묶음을 찾고 있습니다.',
    DONE: '업무가 배정되었습니다. 내 업무에서 시간과 절차를 확인하세요.',
    NO_CANDIDATE:
        '참여는 완료됐습니다. 지금 이 그룹에 맡을 수 있는 업무 묶음이 없어 대기 중입니다. 총괄이 배정하면 알림으로 알려 드립니다.',
    FAILED: '참여는 완료됐습니다. 배정이 늦어지고 있어 총괄이 다시 요청합니다. 다시 참여할 필요는 없습니다.',
    UNKNOWN: '참여는 완료됐습니다. 배정 결과를 확인하고 있습니다. 다시 참여할 필요는 없습니다.',
};

/** 최초 배정 상태 안내 한 줄의 글(APP 내 정보가 같은 문구를 쓴다) */
export function AssignText({ state }: { state: AssignState }) {
    return <>{ASSIGN_TEXT[state]}</>;
}

/**
 * 내 참여 상태 카드(팀원): 그룹 · 예비/가용 · 최초 배정 상태 · 소속 안내.
 * WEB 홈과 APP 현황·내 업무가 같은 카드를 쓴다.
 */
/** bare: 카드 테두리 없이 화면에 바로 놓는다(WEB 팀원 홈) */
export function MyStatusCard({ bare = false }: { bare?: boolean }) {
    const { member: m, group } = useMe();
    const ev = useEvent();
    const dispatch = useDispatch();
    const d = useData();
    const liveMode = !!useDemoState().live;
    if (!m || m.role === 'CHIEF') return null;
    const hasTasks = tasksOf(d, m.id).length > 0;
    return (
        <section className={bare ? 'stack s6' : 'card stack s6'}>
            {/* 소속 칩 옆에 그룹 안내 문구를 한 줄로 둔다 */}
            <div className={styles.meHead}>
                <b className={styles.mePill}>
                    {group?.name ?? '그룹 없음'} / {m.isReserve ? '예비 팀원' : '팀원'}
                </b>
                {group?.guide && <p className="small grow">{group.guide}</p>}
                {m.assign && <AssignBadge state={m.assign} />}
                {m.isReserve && <ReserveChip m={m} />}
            </div>
            {m.assign && m.assign !== 'DONE' && <p className="small">{ASSIGN_TEXT[m.assign]}</p>}
            {m.isReserve && (
                <>
                    <p className="small">
                        {hasTasks
                            ? '예비 인원으로 투입되어 업무를 맡고 있습니다.'
                            : '배정된 업무가 없습니다. 총괄이 투입을 확정하면 알림과 함께 내 업무에 나타납니다.'}
                    </p>
                    {/* 실서버에는 이 값들의 API가 없다(#373, 기획에 없음) */}
                    {!liveMode && (
                        <dl className="kv">
                            <dt>가능 업무</dt>
                            <dd>{m.skills ?? '-'}</dd>
                            <dt>대기 장소</dt>
                            <dd>{m.wait ?? '-'}</dd>
                            <dt>가용 시간</dt>
                            <dd className="mono">{m.avail}</dd>
                        </dl>
                    )}
                    {ev.state === 'LIVE' && (
                        <button
                            type="button"
                            className="btn sm"
                            aria-pressed={m.available}
                            onClick={() =>
                                dispatch({ type: 'setAvailable', available: !m.available })
                            }
                        >
                            {m.available ? '잠시 투입 불가로 바꾸기' : '지금 투입 가능으로 바꾸기'}
                        </button>
                    )}
                </>
            )}
        </section>
    );
}

export function ReserveChip({ m }: { m: Member }) {
    return m.available ? (
        <Chip kind="violet">
            <Icon name="people" /> 예비 / <span className={styles.ready}>투입 가능</span>
        </Chip>
    ) : (
        <Chip>
            <Icon name="lock" /> 예비 / 지금 불가
        </Chip>
    );
}

/**
 * 그룹별 요약(팀원에게도 허용되는 집계): 인원·업무 수만, 개인 상세 없음(P02).
 */
export function GroupSummary({ flat = false }: { flat?: boolean }) {
    const d = useData();
    // flat: 카드·아이콘 없이 이름과 집계만 줄로 나열(WEB 팀원 홈 오른쪽 칸)
    if (flat) {
        return (
            <ul className={styles.groupFlat}>
                {d.groups.map((g) => {
                    const people = membersOf(d, g.id);
                    const tasks = d.tasks.filter(
                        (t) => t.groupId === g.id && t.status !== 'canceled',
                    );
                    return (
                        <li key={g.id}>
                            <b>{g.name}</b>
                            <span>
                                인원 {people.length}명 / 업무 {tasks.length}건
                            </span>
                        </li>
                    );
                })}
            </ul>
        );
    }
    return (
        <div className="lgroup">
            {d.groups.map((g) => {
                const people = membersOf(d, g.id);
                const tasks = d.tasks.filter((t) => t.groupId === g.id && t.status !== 'canceled');
                return (
                    <div key={g.id} className="lrow">
                        <span className="li">
                            <Icon name="people" />
                        </span>
                        <span className="grow">
                            <b>{g.name}</b>
                            <br />
                            <span className="xs muted">
                                인원 {people.length}명 / 업무 {tasks.length}건
                            </span>
                        </span>
                    </div>
                );
            })}
        </div>
    );
}

/** 미배정 묶음 하나: 시간 요약 + 같은 검증을 쓰는 수동 대체 배정 */
export function BundleAssign({ bundleKey, live }: { bundleKey: string; live: boolean }) {
    const d = useData();
    const dispatch = useDispatch();
    const b = unassignedBundles(d).find((x) => x.key === bundleKey);
    const [who, setWho] = useState('');
    const [open, setOpen] = useState(false);
    const dialogRef = useRef<HTMLDialogElement>(null);
    if (!b) return null;
    // 같은 그룹 팀원을 모두 보여 주고, 조건에 맞지 않는 사람을 고르면 안내 팝업을 띄운다
    const people = membersOf(d, b.groupId).filter((m) => m.role === 'WORKER');
    const [first, ...rest] = b.tasks;
    const listId = `bundle-${b.key}`;
    return (
        <div className="box stack s6">
            <button
                type="button"
                className={styles.bundleToggle}
                aria-expanded={open}
                aria-controls={listId}
                onClick={() => setOpen((x) => !x)}
            >
                <span className="grow">
                    {first.start}–{first.end} {first.title}
                    {rest.length > 0 && ` 외 ${rest.length}건`}
                </span>
                <span className={styles.bundleCaret} aria-hidden="true">
                    ▼
                </span>
            </button>
            {open && (
                <ul id={listId} className={styles.bundleTasks}>
                    {b.tasks.map((t) => (
                        <li key={t.id}>
                            <span className="mono">
                                {t.start}–{t.end}
                            </span>{' '}
                            {t.title}
                        </li>
                    ))}
                </ul>
            )}
            {live && (
                <div className="row nw">
                    <select
                        className="inp"
                        aria-label="수동 대체 배정할 팀원"
                        value={who}
                        onChange={(e) => {
                            const m = people.find((x) => x.id === e.target.value);
                            if (m && !canTakeBundle(d, m, b)) {
                                setWho('');
                                dialogRef.current?.showModal();
                                return;
                            }
                            setWho(e.target.value);
                        }}
                    >
                        <option value="">배정할 팀원 선택</option>
                        {people.map((m) => (
                            <option key={m.id} value={m.id}>
                                {m.name}
                            </option>
                        ))}
                    </select>
                    <button
                        type="button"
                        className="btn sm"
                        disabled={!who}
                        onClick={() => {
                            dispatch({ type: 'assignManual', bundle: b.key, memberId: who });
                            setWho('');
                        }}
                    >
                        수동 배정
                    </button>
                </div>
            )}
            <dialog
                ref={dialogRef}
                className={styles.notice}
                aria-labelledby={`${listId}-title`}
                aria-describedby={`${listId}-text`}
            >
                <h2 id={`${listId}-title`} className="h3">
                    배정할 수 없는 팀원입니다
                </h2>
                <p id={`${listId}-text`}>
                    같은 그룹 / 가용 / 비예비 / 시간 충돌 없음 조건을 통과한 팀원만 고를 수
                    있습니다.
                </p>
                <div className="row" style={{ justifyContent: 'flex-end' }}>
                    <button
                        type="button"
                        className="btn sm primary"
                        onClick={() => dialogRef.current?.close()}
                    >
                        확인
                    </button>
                </div>
            </dialog>
        </div>
    );
}

/**
 * 구조도 팀원 펼침 안의 예비 투입. 투입은 변경안을 만들어 총괄 승인한 뒤 반영한다(V7-D21).
 * 변경안을 만들기 전에 투입 규칙 안내 팝업을 한 번 띄운다.
 */
export function ReserveActions({ m }: { m: Member }) {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const dispatch = useDispatch();
    const shell = useShell();
    const [issueId, setIssueId] = useState('');
    const dialogRef = useRef<HTMLDialogElement>(null);
    if (!m.isReserve || !v.isChief) return null;
    const issues = d.issues.filter((i) => i.status === 'RECEIVED' || i.status === 'RESPONDING');
    const canPropose = ev.state === 'LIVE' && m.available && openTasksOf(d, m.id).length === 0;
    if (!canPropose) return null;
    const propose = () => {
        dialogRef.current?.close();
        const id = newId('C');
        dispatch({ type: 'aiChange', issueId, id, reserveId: m.id });
        shell.openChange(id);
    };
    return (
        <div className="row nw">
            <select
                className="inp"
                aria-label={`${m.name} 투입할 이슈`}
                value={issueId}
                onChange={(e) => setIssueId(e.target.value)}
            >
                <option value="">투입할 이슈 선택</option>
                {issues.map((i) => (
                    <option key={i.id} value={i.id}>
                        {i.id} {i.title}
                    </option>
                ))}
            </select>
            <button
                type="button"
                className="btn sm primary"
                disabled={!issues.some((i) => i.id === issueId)}
                onClick={() => dialogRef.current?.showModal()}
            >
                투입 변경안 만들기
            </button>
            <dialog
                ref={dialogRef}
                className={styles.notice}
                aria-labelledby={`deploy-${m.id}-title`}
                aria-describedby={`deploy-${m.id}-text`}
            >
                <h2 id={`deploy-${m.id}-title`} className="h3">
                    예비 투입 안내
                </h2>
                <p id={`deploy-${m.id}-text`}>
                    예비 투입은 변경 비교 후 총괄이 확정합니다. 가용 상태를 바꿔도 기존 업무는
                    취소되지 않습니다.
                </p>
                <div className="row" style={{ justifyContent: 'flex-end' }}>
                    <button
                        type="button"
                        className="btn sm ghost"
                        onClick={() => dialogRef.current?.close()}
                    >
                        취소
                    </button>
                    <button type="button" className="btn sm primary" onClick={propose}>
                        확인하고 변경안 만들기
                    </button>
                </div>
            </dialog>
        </div>
    );
}
