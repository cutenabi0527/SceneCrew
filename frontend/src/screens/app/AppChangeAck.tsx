import { useNavigate, useParams } from 'react-router';
import { useChangeReview, useData, useEvent, useNotices, useSession } from '../../domain/hooks';
import {
    TASK_STATUS_LABEL,
    byId,
    nameOf,
    needsAck,
    taskTargetHash,
    type DiffRow,
} from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { ChangeOp, EventData, Task } from '../../domain/types';
import { ChangeCards } from '../../features/change/ChangeDiff';
import { TaskCard } from '../../features/task/TaskCard';
import { useShell } from '../../layouts/shell-actions';
import { Icon } from '../../shared/ui/Icon';
import { Empty, Note } from '../../shared/ui/atoms';
import styles from './app.module.css';
import ack from './AppChangeAck.module.css';

/**
 * M08 변경 확인. APP은 현장 팀원용 화면(바뀐 것만 강조 + 바뀐 뒤 내 업무 + 아래 고정 '확인했어요')을,
 * 실서버 WEB(WebChangeAck)은 지금까지의 화면을 그대로 쓴다.
 */
export function AppChangeAck() {
    return useShell().variant === 'app' ? <AppAckView /> : <ChangeAckClassic />;
}

/** 확인 대상 화면에 들어갈 수 없을 때: 이유와 다음에 갈 곳 */
function AckBlocked({ title }: { title: string }) {
    const navigate = useNavigate();
    const pending = useNotices().filter(needsAck).length;
    return (
        <div className={ack.blocked}>
            <span className={ack.lock}>
                <Icon name="lock" />
            </span>
            <span className={ack.blockedTitle}>{title}</span>
            <span className={ack.blockedText}>
                나에게 온 변경은 알림함에 있습니다.{' '}
                {pending
                    ? `지금 확인할 변경이 ${pending}건 있습니다.`
                    : '지금 확인할 변경은 없습니다.'}
            </span>
            <div className={ack.blockedActions}>
                <button
                    type="button"
                    className={ack.primary}
                    onClick={() => navigate('/e/notices')}
                >
                    내 알림 보기
                </button>
                <button
                    type="button"
                    className={ack.secondary}
                    onClick={() => navigate('/e/today')}
                >
                    내 업무로
                </button>
            </div>
        </div>
    );
}

type Field = { label: string; before?: string; after: string };

/** 변경 operation 한 건을 팀원이 읽는 항목으로: 바뀐 항목은 이전 → 이후, 안 바뀐 항목은 '그대로' */
function opFields(d: EventData, o: ChangeOp, task: Task | undefined): Field[] {
    const pair = (label: string, before: string, after: string): Field =>
        before === after ? { label, after } : { label, before, after };
    if (o.kind === 'cancel') return [{ label: '상태', before: '배정', after: '취소' }];
    if (o.kind === 'add')
        return [
            { label: '시간', before: '없음', after: `${o.after.start}–${o.after.end}` },
            { label: '구역', before: '없음', after: o.after.zone ?? '없음' },
        ];
    const out = [
        pair('시작', o.before.start, o.after.start),
        pair('끝나는 시간', o.before.end, o.after.end),
    ];
    if (o.kind === 'program') return out;
    if (o.after.assignee !== undefined && o.before.assignee !== o.after.assignee)
        out.push(pair('담당', nameOf(d, o.before.assignee), nameOf(d, o.after.assignee)));
    const zoneBefore = o.before.zone ?? task?.zone ?? '없음';
    out.push(pair('구역', zoneBefore, o.after.zone ?? zoneBefore));
    if (o.after.steps && o.before.steps && o.after.steps.join('\n') !== o.before.steps.join('\n'))
        out.push({ label: '절차', before: '이전 절차', after: o.after.steps.join(' / ') });
    return out;
}

/** 바뀐 내용 카드 한 장 */
function ChangeFieldsCard({ title, fields }: { title: string; fields: Field[] }) {
    return (
        <div className={ack.card}>
            <span className={ack.cardTitle}>{title}</span>
            <dl className={ack.fields}>
                {fields.map((f) => (
                    <div key={f.label} className={ack.field}>
                        <dt>{f.label}</dt>
                        {f.before !== undefined ? (
                            <dd className={ack.changed}>
                                <span className={ack.before}>{f.before}</span>
                                <svg
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    role="img"
                                    aria-label="에서"
                                >
                                    <path d="M5 12h14M13 6l6 6-6 6" />
                                </svg>
                                <strong className={ack.after}>{f.after}</strong>
                            </dd>
                        ) : (
                            <dd>
                                {f.after} <span className={ack.same}>그대로</span>
                            </dd>
                        )}
                    </div>
                ))}
            </dl>
        </div>
    );
}

/** 바뀐 뒤 내 업무 한 장. 누르면 업무 상세로 */
function MyTaskCard({ task }: { task: Task }) {
    const shell = useShell();
    return (
        <button
            type="button"
            className={ack.task}
            data-status={task.status}
            onClick={() => shell.openTask(task.id)}
        >
            <span className={ack.taskText}>
                <span className={ack.taskMeta}>
                    {task.start}–{task.end} · {task.zone}
                </span>
                <span className={ack.taskTitle}>{task.title}</span>
            </span>
            <span className={ack.status} data-status={task.status}>
                {TASK_STATUS_LABEL[task.status]}
            </span>
        </button>
    );
}

/** 아래 고정 확인 단추. 확인했으면 내 업무로 가는 보조 단추 */
function AckBar({ done, label, onAck }: { done: boolean; label: string; onAck: () => void }) {
    const navigate = useNavigate();
    return (
        <div className={ack.bar}>
            {done ? (
                <button
                    type="button"
                    className={ack.barSecondary}
                    onClick={() => navigate('/e/today')}
                >
                    확인 완료 / 내 업무로
                </button>
            ) : (
                <>
                    <button type="button" className={ack.barPrimary} onClick={onAck}>
                        {label}
                    </button>
                    <span className={ack.barHint}>누르면 총괄에게 확인 완료로 보입니다</span>
                </>
            )}
        </div>
    );
}

/**
 * APP M08(푸시 진입 화면): "무엇이 바뀌었나 → 바뀐 뒤 내 업무 → 확인했어요" 한 화면으로 끝낸다.
 * 대상 지문·회차 같은 시스템 값은 화면에 보이지 않지만 확인(ackNotice)은 같은 값으로 기록되고 과거 확인도 남는다.
 */
function AppAckView() {
    const { noticeId } = useParams();
    const d = useData();
    const { me } = useSession();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const live = !!useDemoState().live;
    const n = d.notices.find((x) => x.id === noticeId);
    const review = useChangeReview(n?.change);
    if (!n) return <AckBlocked title="알림을 찾을 수 없습니다" />;
    if (n.to !== me) return <AckBlocked title="다른 사람에게 온 확인 요청입니다" />;
    if (live && n.ackId) return <LiveAppAck noticeId={n.id} />;
    const targetIds = n.targetIds ?? [];
    const hash = targetIds.length ? taskTargetHash(d, targetIds) : null;
    const lastAck = n.acks?.at(-1);
    // 알림 이후(확인 전) 또는 확인 이후 대상이 바뀌었는가
    const movedSinceNotice = !!hash && !!n.targetHash && !n.ack && hash !== n.targetHash;
    const reAck = !!hash && n.ack && !!lastAck && lastAck.hash !== hash;
    const mineOps = review
        ? review.change.ops
              .map((o, k) => ({ o, r: review.rows[k] as DiffRow }))
              .filter(({ r }) => r.kind === 'program' || r.people.includes(me ?? ''))
        : [];
    // 바뀐 뒤 내 업무: 이 알림의 확인 대상. 없으면 아직 확인하지 않은 내 업무
    const targets = targetIds
        .map((id) => d.tasks.find((t) => t.id === id))
        .filter((t): t is Task => !!t && t.assignee === me);
    const after = targets.length ? targets : d.tasks.filter((t) => t.assignee === me && !t.ack);
    const by = review ? byId(d, review.change.by) : undefined;
    const deploy = n.kind === 'deploy';

    return (
        <>
            <section className={ack.banner} aria-label="알림">
                <span className={ack.bannerTitle}>
                    {deploy ? '예비 인원으로 투입되었습니다' : '내 업무가 바뀌었습니다'}
                </span>
                <span className={ack.bannerSub}>
                    {by ? `${by.role === 'CHIEF' ? '총괄 ' : ''}${by.name} · ` : ''}
                    {review?.change.appliedAt ?? n.at}
                </span>
                {review?.change.source && (
                    <span className={ack.bannerSub}>이유: {review.change.source}</span>
                )}
            </section>

            <section className={ack.section} aria-labelledby="ack-chg-h">
                <h2 id="ack-chg-h">바뀐 내용</h2>
                {mineOps.length ? (
                    mineOps.map(({ o, r }) => (
                        <ChangeFieldsCard
                            key={r.key}
                            title={r.target}
                            fields={opFields(
                                d,
                                o,
                                'taskId' in o ? d.tasks.find((t) => t.id === o.taskId) : undefined,
                            )}
                        />
                    ))
                ) : (
                    <div className={ack.card}>
                        <span>{n.text}</span>
                    </div>
                )}
            </section>

            {after.length > 0 && (
                <section className={ack.section} aria-labelledby="ack-now-h">
                    <h2 id="ack-now-h">바뀐 뒤 내 업무</h2>
                    {after.map((t) => (
                        <MyTaskCard key={t.id} task={t} />
                    ))}
                </section>
            )}
            {movedSinceNotice && (
                <Note kind="warn" icon="warn">
                    알림을 받은 뒤 내 업무가 다시 바뀌었습니다. 지금 보이는 내용으로 확인합니다.
                </Note>
            )}
            {reAck && (
                <Note kind="warn" icon="warn">
                    확인한 뒤 내 업무가 또 바뀌었습니다. 바뀐 내용을 보고 다시 확인해주세요. 이전
                    확인 기록은 그대로 남습니다.
                </Note>
            )}
            <AckBar
                done={n.ack && !reAck}
                label={
                    reAck ? '바뀐 내용 다시 확인' : deploy ? '투입 내용 확인했어요' : '확인했어요'
                }
                onAck={() => {
                    dispatch({ type: 'ackNotice', id: n.id });
                    navigate('/e/today', { replace: true });
                }}
            />
        </>
    );
}

/**
 * 실서버 APP M08(#373). 팀원은 변경안을 읽지 못하므로 확인 대상(076)의 업무·바뀐 종류로 그리고, 서버 지문(targetsHash)으로
 * 077을 보낸다(지문은 화면에 보이지 않는다). 확인은 비동기로 끝나므로 화면에 남아 결과(다시 읽은 상태)를 보여 준다.
 */
function LiveAppAck({ noticeId }: { noticeId: string }) {
    const d = useData();
    const { me } = useSession();
    const dispatch = useDispatch();
    const n = d.notices.find((x) => x.id === noticeId)!;
    const targets = n.ackTargets ?? [];
    const mine = d.tasks.filter((t) => t.assignee === me && targets.some((x) => x.taskId === t.id));
    return (
        <>
            <section className={ack.banner} aria-label="알림">
                <span className={ack.bannerTitle}>내 업무가 바뀌었습니다</span>
                <span className={ack.bannerSub}>
                    {n.text} · {n.at}
                </span>
            </section>
            <section className={ack.section} aria-labelledby="ack-chg-h">
                <h2 id="ack-chg-h">바뀐 내용</h2>
                {targets.length ? (
                    targets.map((t) => (
                        <div key={t.taskId} className={ack.card}>
                            <span className={ack.cardTitle}>{t.title}</span>
                            <span className={ack.after}>
                                {t.removed ? '내 업무에서 빠짐' : `${t.changes.join(', ')} 변경`}
                            </span>
                        </div>
                    ))
                ) : (
                    <p className={ack.blockedText}>지금 확인할 업무가 없습니다.</p>
                )}
            </section>
            {mine.length > 0 && (
                <section className={ack.section} aria-labelledby="ack-now-h">
                    <h2 id="ack-now-h">바뀐 뒤 내 업무</h2>
                    {mine.map((t) => (
                        <MyTaskCard key={t.id} task={t} />
                    ))}
                </section>
            )}
            <AckBar
                done={n.ack}
                label="확인했어요"
                onAck={() => dispatch({ type: 'ackNotice', id: n.id })}
            />
        </>
    );
}

/**
 * M08 변경 확인(WEB 실서버 · 이전 APP 화면). 승인된 본인 변경의 전후·이유·대상·대상 지문·회차를 보고 명시 확인한다.
 * 알림 읽음·푸시 수신·업무 시작은 확인이 아니다. 확인한 뒤 대상(본인 업무)이 바뀌면 지문이 달라져 다시 확인받고,
 * 과거 확인 기록은 지우지 않는다.
 * 푸시를 누르면 바로 여기로 와서 "무엇이 바뀌었나 → 지금 내 업무 → 확인" 한 화면으로 끝낸다.
 * 데모 WEB에는 이 화면이 없고 알림함에서 바로 확인 처리한다. 실서버(#373)는 WEB도 이 화면에서 명시 확인한다.
 */
function ChangeAckClassic() {
    const { noticeId } = useParams();
    const d = useData();
    const { me } = useSession();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const ev = useEvent();
    const live = !!useDemoState().live;
    const n = d.notices.find((x) => x.id === noticeId);
    const review = useChangeReview(n?.change);
    if (!n) return <Empty>알림을 찾을 수 없습니다</Empty>;
    if (n.to !== me) return <Empty icon="lock">본인에게 온 변경 확인만 볼 수 있습니다</Empty>;
    if (live && n.ackId) return <LiveChangeAck noticeId={n.id} />;
    const targetIds = n.targetIds ?? [];
    const hash = targetIds.length ? taskTargetHash(d, targetIds) : null;
    const lastAck = n.acks?.at(-1);
    // 알림 이후(확인 전) 또는 확인 이후 대상이 바뀌었는가
    const movedSinceNotice = !!hash && !!n.targetHash && !n.ack && hash !== n.targetHash;
    const reAck = !!hash && n.ack && !!lastAck && lastAck.hash !== hash;
    const myRows = review?.rows.filter((r) => r.kind === 'program' || r.people.includes(me ?? ''));
    const mine = d.tasks.filter((t) => t.assignee === me && !t.ack);

    return (
        <>
            <Note kind="accent" icon={n.kind === 'deploy' ? 'bolt' : 'bell'}>
                <b>
                    {n.kind === 'deploy'
                        ? '예비 인원으로 투입되었습니다'
                        : '내 업무가 바뀌었습니다'}
                </b>
                <div className="small">{n.text}</div>
            </Note>
            {review && (
                <dl className="kv">
                    <dt>확인 대상</dt>
                    <dd>
                        {review.change.title} <span className="code">{review.change.id}</span>
                    </dd>
                    <dt>회차</dt>
                    <dd>
                        적용 {review.change.appliedAt ?? '-'} / 현재 상태 번호 {ev.revision}
                    </dd>
                    {hash && (
                        <>
                            <dt>대상 지문</dt>
                            <dd>
                                <span className="code">{hash}</span>{' '}
                                <span className="xs muted">업무 {targetIds.join(', ')}</span>
                            </dd>
                        </>
                    )}
                    {!!n.acks?.length && (
                        <>
                            <dt>확인 기록</dt>
                            <dd className="xs">
                                {n.acks.map((a) => `${a.at} 확인(${a.hash})`).join(' / ')}
                            </dd>
                        </>
                    )}
                    {review.change.source && (
                        <>
                            <dt>이유</dt>
                            <dd>{review.change.source}</dd>
                        </>
                    )}
                </dl>
            )}
            {myRows && myRows.length > 0 && (
                <div className="stack s6">
                    <div className="sec-t">바뀐 내용</div>
                    <ChangeCards rows={myRows} />
                </div>
            )}
            {mine.length > 0 && (
                <div className="stack s6">
                    <div className="sec-t">지금 내 업무</div>
                    {mine.map((t) => (
                        <TaskCard key={t.id} task={t} compact showWho={false} />
                    ))}
                </div>
            )}
            {movedSinceNotice && (
                <Note kind="warn" icon="warn">
                    알림을 받은 뒤 확인 대상이 다시 바뀌었습니다. 지금 보이는 대상으로 확인합니다.
                </Note>
            )}
            {reAck && (
                <Note kind="warn" icon="warn">
                    확인한 뒤 대상이 바뀌었습니다({lastAck!.hash} → {hash}). 바뀐 내용을 보고 다시
                    확인해주세요. 이전 확인 기록은 그대로 남습니다.
                </Note>
            )}
            <div className={styles.bottomAction}>
                {n.ack && !reAck ? (
                    <button
                        type="button"
                        className="btn block"
                        onClick={() => navigate('/e/today')}
                    >
                        확인 완료 / 내 업무로
                    </button>
                ) : (
                    <button
                        type="button"
                        className="btn primary block"
                        onClick={() => {
                            dispatch({ type: 'ackNotice', id: n.id });
                            navigate('/e/today', { replace: true });
                        }}
                    >
                        {reAck
                            ? '바뀐 대상 다시 확인'
                            : n.kind === 'deploy'
                              ? '투입 내용 확인'
                              : '변경사항 확인'}
                    </button>
                )}
            </div>
        </>
    );
}

/**
 * 실서버 M08(#373). 팀원은 변경안을 읽지 못하므로 확인 대상(076)의 업무·바뀐 종류로 그리고, 서버 지문(targetsHash)으로
 * 077을 보낸다. 확인은 비동기로 끝나므로 화면에 남아 결과(다시 읽은 상태)를 보여 준다.
 */
function LiveChangeAck({ noticeId }: { noticeId: string }) {
    const d = useData();
    const { me } = useSession();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const n = d.notices.find((x) => x.id === noticeId)!;
    const targets = n.ackTargets ?? [];
    const mine = d.tasks.filter((t) => t.assignee === me && targets.some((x) => x.taskId === t.id));
    return (
        <>
            <Note kind="accent" icon="bell">
                <b>내 업무가 바뀌었습니다</b>
                <div className="small">{n.text}</div>
            </Note>
            <div className="stack s6">
                <div className="sec-t">바뀐 업무</div>
                {targets.length ? (
                    <ul className="stack s6">
                        {targets.map((t) => (
                            <li key={t.taskId}>
                                <b>{t.title}</b>{' '}
                                <span className="small">
                                    {t.removed
                                        ? '내 업무에서 빠짐'
                                        : `${t.changes.join(', ')} 변경`}
                                </span>
                            </li>
                        ))}
                    </ul>
                ) : (
                    <p className="small muted">지금 확인할 업무가 없습니다.</p>
                )}
            </div>
            <dl className="kv">
                <dt>대상 지문</dt>
                <dd>
                    <span className="code">{n.targetHash}</span>
                </dd>
                {!!n.acks?.length && (
                    <>
                        <dt>확인 기록</dt>
                        <dd className="xs">{n.acks.map((a) => `${a.at} 확인`).join(' / ')}</dd>
                    </>
                )}
            </dl>
            {mine.length > 0 && (
                <div className="stack s6">
                    <div className="sec-t">지금 내 업무</div>
                    {mine.map((t) => (
                        <TaskCard key={t.id} task={t} compact showWho={false} />
                    ))}
                </div>
            )}
            <Note icon="info">
                알림을 읽거나 푸시를 받은 것은 확인이 아닙니다. 바뀐 내용을 보고 아래에서
                확인해주세요.
            </Note>
            <div className={styles.bottomAction}>
                {n.ack ? (
                    <button
                        type="button"
                        className="btn block"
                        onClick={() => navigate('/e/today')}
                    >
                        확인 완료 / 내 업무로
                    </button>
                ) : (
                    <button
                        type="button"
                        className="btn primary block"
                        onClick={() => dispatch({ type: 'ackNotice', id: n.id })}
                    >
                        변경사항 확인
                    </button>
                )}
            </div>
        </>
    );
}
