import { Fragment, useMemo, useState } from 'react';
import {
    NavLink,
    Outlet,
    matchPath,
    useLocation,
    useNavigate,
    useSearchParams,
} from 'react-router';
import { useBadges, useData, useEvent, useSession } from '../../domain/hooks';
import { EVENT_STATE_LABEL, byId } from '../../domain/selectors';
import { Icon, type IconName } from '../../shared/ui/Icon';
import { useDemoState } from '../../domain/store-context';
import { useModalBackHandler } from '../../shared/navigation/leave-guard-context';
import { PersonaSelect } from '../PersonaSelect';
import { ShellStatus } from '../ShellStatus';
import { AppEventList } from '../../screens/app/AppEventList';
import { ShellActionsContext, type ShellActions } from '../shell-actions';
import styles from './AppShell.module.css';

/**
 * 하단 탭 4개 + 가운데 상황 보고 원 버튼. 원 버튼은 화면 이동 대신 바로 음성 보고를 연다.
 */
const TABS: [to: string, label: string, icon: IconName, badge?: 'today'][] = [
    ['/e/today', '내 업무', 'task', 'today'],
    ['/e/plan', '계획', 'plan'],
    ['/e/issues', '이슈', 'warn'],
    ['/e/chat', '소통', 'chat'],
];
const ROOTS = TABS.map((t) => t[0]);

/**
 * 하위 화면 제목. 업무·이슈 번호는 제목에 쓰지 않는다(현장 팀원에게 필요 없는 값, 실서버 UUID #373).
 * 이슈 상세는 내가 보낸 보고면 "내 보고", 상황 보고는 이어 쓰기 종류에 따라 이름이 바뀐다.
 */
const TITLES: [pattern: string, title: string][] = [
    ['/e/tasks/:taskId', '업무 상세'],
    ['/e/response/:issueId', '이슈 상세'],
    ['/e/notices', '알림'],
    ['/e/ack/:noticeId', '변경 확인'],
    ['/e/report', '상황 보고'],
    ['/e/me', '내 정보'],
    ['/e/me/edit', '내 정보 수정'],
];
const REPORT_TITLE: Record<string, string> = { FOLLOWUP: '추가 보고', RESOLUTION: '해결 보고' };
/** 아래 고정 버튼이 있거나 입력칸이 맨 아래인 화면은 하단 탭을 뺀다 */
const NO_TABS = [
    '/e/chat/:roomId',
    '/e/report',
    '/e/ack/:noticeId',
    '/e/response/:issueId',
    '/e/me',
    '/e/me/edit',
];

/**
 * APP 레이아웃(현장 팀원 우선, 한 손 조작). 관리자 승인·계획 편집·종료 요약은 넣지 않는다:
 * 상단 앱바(탭 루트는 로고+공연명+알림+내 정보, 하위는 뒤로+화면명) + 세로 스크롤 본문 + 하단 4탭과 가운데 상황 보고 원 버튼.
 * 아래 고정 버튼이 있는 화면(보고·변경 확인·이슈 상세·내 정보)과 채팅방은 하단 탭을 뺀다.
 * 보고·채팅·상세는 모두 페이지로 쌓고 Android Back으로 돌아온다.
 */
export function AppShell() {
    const navigate = useNavigate();
    const location = useLocation();
    const [search] = useSearchParams();
    const ev = useEvent();
    const d = useData();
    const { me } = useSession();
    const badges = useBadges();
    const [demo, setDemo] = useState(false);
    const live = !!useDemoState().live;
    // 왼쪽 위 공연 이름을 누르면 아래에서 올라오는 공연 바꾸기 시트
    const [events, setEvents] = useState(false);
    useModalBackHandler(events, () => setEvents(false));
    const member = byId(d, me);
    const path = location.pathname;
    const isRoot = ROOTS.includes(path);
    const room = matchPath('/e/chat/:roomId', path);
    const issue = matchPath('/e/response/:issueId', path);
    const isReport = path === '/e/report';
    // 채팅방은 입력창이 맨 아래라 본문 여백도 뺀다
    const fullScreen = !!room;
    const noTabs = NO_TABS.some((p) => matchPath(p, path));

    const title = isRoot
        ? ev.name
        : room
          ? (d.rooms.find((r) => r.id === room.params.roomId)?.name ?? '채팅')
          : isReport
            ? (REPORT_TITLE[search.get('kind') ?? ''] ?? '상황 보고')
            : issue && d.issues.find((i) => i.id === issue.params.issueId)?.reporter === me
              ? '내 보고'
              : (TITLES.find(([p]) => matchPath(p, path))?.[1] ?? '');

    const actions = useMemo<ShellActions>(
        () => ({
            variant: 'app',
            openReport: (o) => {
                const q = new URLSearchParams();
                if (o?.taskId) q.set('task', o.taskId);
                if (o?.issueId) q.set('issue', o.issueId);
                if (o?.kind) q.set('kind', o.kind);
                navigate(`/e/report${q.size ? `?${q}` : ''}`);
            },
            openChat: (roomId) => navigate(roomId ? `/e/chat/${roomId}` : '/e/chat'),
            openIssue: (id) => navigate(`/e/response/${id}`),
            openTask: (id) => navigate(`/e/tasks/${id}`),
            canOpenTask: true,
            // APP에는 변경 비교·승인이 없다. 내게 온 변경 확인 알림이 있으면 그 확인 화면(M08)으로 연다.
            openChange: (id) => {
                const n = d.notices.find((x) => x.change === id && x.to === me);
                navigate(n ? `/e/ack/${n.id}` : '/e/notices');
            },
        }),
        [navigate, d.notices, me],
    );

    // 푸시·딥링크로 바로 들어온 하위 화면은 이전 이력이 없으므로 뒤로가기를 내 업무 탭으로 보낸다.
    const back = () =>
        location.key === 'default' ? navigate('/e/today', { replace: true }) : navigate(-1);

    return (
        <ShellActionsContext.Provider value={actions}>
            <div className={styles.app} data-ui="app" data-platform="android">
                <header className={styles.appbar}>
                    {!isRoot && (
                        <button
                            type="button"
                            className={styles.abBtn}
                            aria-label={isReport ? '닫기' : '뒤로'}
                            onClick={back}
                        >
                            <Icon name={isReport ? 'close' : 'back'} />
                        </button>
                    )}
                    {/* 탭 화면에서는 WEB 네비 왼쪽과 같은 로고 + 공연 이름 */}
                    {isRoot && <span className={styles.logo}>씬</span>}
                    {isRoot ? (
                        <button
                            type="button"
                            className={`${styles.title} ${styles.titleBtn}`}
                            aria-expanded={events}
                            aria-label={`${ev.name} / 행사 목록 ${events ? '닫기' : '열기'}`}
                            onClick={() => setEvents((x) => !x)}
                        >
                            <b>
                                {title}{' '}
                                <span className={styles.caret} aria-hidden="true">
                                    {events ? '▴' : '▾'}
                                </span>
                            </b>
                            <span className={styles.state}>
                                {ev.state === 'LIVE' ? '운영 중' : EVENT_STATE_LABEL[ev.state]}
                            </span>
                        </button>
                    ) : (
                        <h1 className={styles.title}>{title}</h1>
                    )}
                    {/* 데모 인물 바꾸기는 데모에서만 쓴다(실서버 #373) */}
                    {!live && isRoot && (
                        <button
                            type="button"
                            className={styles.demo}
                            aria-expanded={demo}
                            onClick={() => setDemo((x) => !x)}
                        >
                            데모
                        </button>
                    )}
                    {/* 알림·내 정보는 탭 화면에서만. 하위 화면 머리줄은 뒤로 + 제목만 둔다 */}
                    {isRoot && (
                        <button
                            type="button"
                            className={styles.abBtn}
                            aria-label={`알림 ${badges.notices}건`}
                            onClick={() => navigate('/e/notices')}
                        >
                            <Icon name="bell" />
                            {badges.notices > 0 && <span className={styles.dot} />}
                        </button>
                    )}
                    {isRoot && (
                        <button
                            type="button"
                            className={styles.abBtn}
                            aria-label="내 정보"
                            onClick={() => navigate('/e/me')}
                        >
                            <span className={styles.av}>{member?.name.slice(0, 1)}</span>
                        </button>
                    )}
                </header>
                {events && isRoot && (
                    <div className={styles.scrim} onClick={() => setEvents(false)}>
                        <section
                            className={styles.sheet}
                            role="dialog"
                            aria-modal="true"
                            aria-labelledby="event-sheet-title"
                            onClick={(e) => e.stopPropagation()}
                            onKeyDown={(e) => e.key === 'Escape' && setEvents(false)}
                        >
                            <span className={styles.grip} aria-hidden="true" />
                            <div className={styles.sheetHead}>
                                <h2 id="event-sheet-title">공연 바꾸기</h2>
                                <button
                                    type="button"
                                    className={styles.abBtn}
                                    aria-label="닫기"
                                    autoFocus
                                    onClick={() => setEvents(false)}
                                >
                                    <Icon name="close" />
                                </button>
                            </div>
                            <AppEventList onPick={() => setEvents(false)} />
                        </section>
                    </div>
                )}
                {demo && isRoot && (
                    <div className={styles.demoSheet}>
                        <PersonaSelect className={styles.persona} />
                        <span className="xs muted">같은 행사를 다른 참여자 권한으로 봅니다</span>
                    </div>
                )}
                <main
                    id="main"
                    className={`${styles.body} ${fullScreen ? styles.flush : ''} ${noTabs ? styles.noTabs : ''}`}
                    tabIndex={-1}
                >
                    <ShellStatus />
                    <Outlet />
                </main>
                {!noTabs && (
                    <nav className={styles.tabs} aria-label="주 메뉴">
                        {TABS.map(([to, label, icon, badge], i) => (
                            <Fragment key={to}>
                                {i === 2 && (
                                    <button
                                        type="button"
                                        className={styles.report}
                                        disabled={ev.state !== 'LIVE'}
                                        aria-label="상황 보고"
                                        onClick={() => actions.openReport()}
                                    >
                                        <Icon name="warn" />
                                        보고
                                    </button>
                                )}
                                <NavLink to={to} className={styles.tab}>
                                    <span className={styles.pill}>
                                        <Icon name={icon} />
                                    </span>
                                    {label}
                                    {badge && badges[badge] > 0 && (
                                        <span className={styles.cnt}>{badges[badge]}</span>
                                    )}
                                </NavLink>
                            </Fragment>
                        ))}
                    </nav>
                )}
            </div>
        </ShellActionsContext.Provider>
    );
}
