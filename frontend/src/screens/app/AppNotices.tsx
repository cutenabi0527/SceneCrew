import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useData, useNotices, useSession } from '../../domain/hooks';
import { needsAck } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Notice, NoticeKind, Task } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { Icon } from '../../shared/ui/Icon';
import styles from './AppNotices.module.css';

/** APP 알림(M04): 배정·변경·본인 보고 알림만 묶어서 보인다 */
type Filter = 'all' | 'assign' | 'change' | 'report';
const APP_KINDS: Record<Filter, NoticeKind[]> = {
    all: ['assign', 'task', 'change', 'deploy', 'report'],
    assign: ['assign', 'task'],
    change: ['change', 'deploy'],
    report: ['report'],
};
const FILTERS: [Filter, string][] = [
    ['all', '전체'],
    ['assign', '배정'],
    ['change', '변경'],
    ['report', '보고'],
];
const KIND: Record<NoticeKind, [string, string]> = {
    report: ['보고', 'bad'],
    assign: ['업무 배정', 'blue'],
    task: ['업무 안내', 'blue'],
    change: ['변경', 'accent'],
    deploy: ['예비 투입', 'accent'],
    profile: ['정보 변경', 'blue'],
};

/**
 * 배정 알림 글("… 배정되었습니다 / 17:30 업무, 18:20 업무")을 제목과 업무 줄로 나눈다.
 * 줄마다 내 업무(시작 시각·이름이 같은 것)를 찾아 눌러서 바로 가게 한다. 하나라도 못 찾으면 나누지 않는다.
 */
function splitAssign(n: Notice, tasks: Task[], me: string | null) {
    if (n.kind !== 'assign') return null;
    const [title, ...rest] = n.text.split(' / ');
    if (!rest.length) return null;
    const lines = rest
        .join(' / ')
        .split(', ')
        .map((s) => {
            const m = /^(\d{1,2}:\d{2}) (.+)$/.exec(s.trim());
            const t =
                m && tasks.find((x) => x.assignee === me && x.start === m[1] && x.title === m[2]);
            return t ? { time: m![1], title: m![2], task: t.id } : null;
        });
    if (lines.some((l) => !l)) return null;
    return { title, lines: lines as { time: string; title: string; task: string }[] };
}

/**
 * 알림함(APP). 맨 위에 확인할 변경 상태 띠, 종류 탭에 개수, 배정 알림은 업무 줄로 나눠 바로 이동한다.
 * 읽음·필터·이동 동작은 공용 알림함(NoticesScreen)과 같다: 변경·투입 알림은 푸시와 같은 변경 확인 화면으로 간다.
 */
export function AppNotices() {
    const dispatch = useDispatch();
    const shell = useShell();
    const navigate = useNavigate();
    const d = useData();
    const { me } = useSession();
    const [f, setF] = useState<Filter>('all');
    const live = !!useDemoState().live;
    const mine = useNotices();
    const open = (n: Notice) => {
        dispatch({ type: 'readNotice', id: n.id });
        // 실서버는 변경 확인 화면에서 명시 확인한다. 알림을 여는 것만으로 확인하지 않는다(IA05, #373).
        if (live && n.ackId) return navigate(`/e/ack/${n.id}`);
        if (needsAck(n)) return navigate(`/e/ack/${n.id}`);
        if (n.issue) shell.openIssue(n.issue);
        else if (n.change) shell.openChange(n.change);
        else if (n.task) shell.openTask(n.task);
    };
    const openLine = (n: Notice, task: string) => {
        dispatch({ type: 'readNotice', id: n.id });
        shell.openTask(task);
    };

    // 푸시를 눌러 들어왔다(?open=<알림 id>, #307). 공용 알림함과 같은 처리: 서버에서 다시 읽은 목록에서 그 알림을 찾아 연다.
    // 뒤로 가기로 돌아와 다시 열리지 않게 이 주소를 바꿔치기한다. 찾지 못하면 알림함에 머문다.
    const [params] = useSearchParams();
    const target = live ? params.get('open') : null;
    const notices = d.notices;
    useEffect(() => {
        if (!target) return;
        const n = notices.find((x) => x.id === target);
        if (!n) return void navigate('/e/notices', { replace: true });
        dispatch({ type: 'readNotice', id: n.id });
        const path = n.ackId
            ? `/e/ack/${n.id}`
            : n.issue
              ? `/e/response/${n.issue}`
              : n.change
                ? `/e/changes/${n.change}`
                : n.task
                  ? `/e/tasks/${n.task}`
                  : '/e/notices';
        navigate(path, { replace: true });
    }, [target, notices, dispatch, navigate]);

    const pending = mine.filter(needsAck);
    const list = mine.filter((n) => APP_KINDS[f].includes(n.kind));
    const count = (k: Filter) => mine.filter((n) => APP_KINDS[k].includes(n.kind)).length;
    return (
        <>
            <div className={styles.top}>
                <button
                    type="button"
                    className={styles.readAll}
                    onClick={() => dispatch({ type: 'readAllNotices' })}
                >
                    모두 읽음
                </button>
            </div>
            {pending.length ? (
                <button
                    type="button"
                    className={`${styles.band} ${styles.bandWarn}`}
                    onClick={() => open(pending[0])}
                >
                    <Icon name="bell" />
                    <span className={styles.bandText}>
                        확인할 변경이 {pending.length}건 있습니다
                    </span>
                    <Icon name="chev" className={styles.right} />
                </button>
            ) : (
                <section className={`${styles.band} ${styles.bandOk}`} aria-label="확인할 변경">
                    <Icon name="check" />
                    <span className={styles.bandText}>확인할 변경이 없습니다</span>
                </section>
            )}
            <div role="tablist" aria-label="알림 종류" className={styles.tabs}>
                {FILTERS.map(([k, label]) => (
                    <button
                        key={k}
                        type="button"
                        role="tab"
                        aria-selected={f === k}
                        className={styles.tab}
                        onClick={() => setF(k)}
                    >
                        {label} {count(k)}
                    </button>
                ))}
            </div>
            {list.length ? (
                <div className={styles.list}>
                    {list.map((n) => {
                        const split = splitAssign(n, d.tasks, me);
                        const head = (
                            <>
                                <span className={styles.meta}>
                                    <span className={styles.chip} data-kind={KIND[n.kind][1]}>
                                        {KIND[n.kind][0]}
                                    </span>
                                    {needsAck(n) && (
                                        <span className={styles.chip} data-kind="warn">
                                            변경 확인 필요
                                        </span>
                                    )}
                                    <span className={styles.at}>{n.at}</span>
                                </span>
                                <span className={styles.title}>{split?.title ?? n.text}</span>
                            </>
                        );
                        return (
                            <article
                                key={n.id}
                                className={`${styles.card} ${n.read ? '' : styles.unread}`}
                            >
                                {split ? (
                                    <>
                                        <div className={styles.head}>{head}</div>
                                        <div className={styles.lines}>
                                            {split.lines.map((l) => (
                                                <button
                                                    key={l.task}
                                                    type="button"
                                                    className={styles.line}
                                                    onClick={() => openLine(n, l.task)}
                                                >
                                                    <span>
                                                        <span className={styles.lineTime}>
                                                            {l.time}
                                                        </span>{' '}
                                                        {l.title}
                                                    </span>
                                                    <Icon name="chev" className={styles.right} />
                                                </button>
                                            ))}
                                        </div>
                                    </>
                                ) : (
                                    <button
                                        type="button"
                                        className={styles.head}
                                        onClick={() => open(n)}
                                    >
                                        {head}
                                    </button>
                                )}
                            </article>
                        );
                    })}
                </div>
            ) : (
                <div className={styles.empty}>
                    <span>{mine.length ? '이 종류의 알림이 없습니다' : '알림이 없습니다'}</span>
                    <button
                        type="button"
                        className={styles.secondary}
                        onClick={() => navigate('/e/today')}
                    >
                        내 업무로
                    </button>
                </div>
            )}
        </>
    );
}
