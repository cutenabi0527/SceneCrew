import { useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router';
import { useBadges, useData, useEvent, useViewer } from '../../domain/hooks';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { ChatThread, RoomList } from '../../features/chat/ChatParts';
import { openChatWindow } from '../../features/chat/chat-window';
import { ReportForm, type ReportFormProps } from '../../features/issue/ReportForm';
import { NoticeList } from '../../features/notice/NoticeList';
import { Icon, type IconName } from '../../shared/ui/Icon';
import { PersonaSelect } from '../PersonaSelect';
import { ShellStatus } from '../ShellStatus';
import { ShellActionsContext, type ShellActions } from '../shell-actions';
import { AlertDock } from './AlertDock';
import { Dropdown, EventMenu, ProfileMenu } from './NavMenus';
import { useDrag, type Pos } from './useDrag';
import styles from './WebShell.module.css';

/**
 * 총괄 메뉴(v9 W01~W07, 사용자 지정 순서). 업무 배정은 계획 큐시트에서 하고,
 * 종료 요약(A01)은 행사 메뉴의 보조 진입이다. 소통(W07)은 상단 메뉴에 두지 않고 소통 버튼으로 브라우저 새 창을 연다.
 */
const NAV_CHIEF: [to: string, label: string, icon: IconName][] = [
    ['/e/plan', '계획', 'plan'],
    // 운영 = 구성도(W02). 인원 이동·초대도 구성도에서 한다(운영진 탭 없음)
    ['/e/home', '운영', 'org'],
    ['/e/equipment', '장비', 'gear'],
    ['/e/response', '보고/대응', 'warn'],
    ['/e/safety', '현황', 'shield'],
];
/** 다른 주소도 같은 메뉴로 강조한다(계획 = 업무 상세 주소 포함) */
const ALIAS: Record<string, string[]> = { '/e/plan': ['/e/tasks'] };
/** 팀원 메뉴(작은 WEB 포함): 내 업무·공통 계획·본인 보고. 현장 기능은 APP이 우선이며 소통은 소통 버튼으로 새 창을 연다 */
const NAV_WORKER: [to: string, label: string, icon: IconName][] = [
    ['/e/home', '내 업무', 'task'],
    ['/e/plan', '계획', 'plan'],
    ['/e/response', '내 보고', 'warn'],
];
/** 장비 조회 허용 그룹의 팀원 메뉴: 팀원 메뉴 + W03 조회(V9-D13) */
const NAV_WORKER_EQUIPMENT: [to: string, label: string, icon: IconName][] = [
    ...NAV_WORKER,
    ['/e/equipment', '장비 현황', 'gear'],
];

/** 실서버 공연에서만 보이는 L01 내 연결(#396). 노트북 카메라를 연결해 사진을 보낸다 */
const NAV_LINKS: [to: string, label: string, icon: IconName] = ['/e/links', '내 연결', 'target'];

function navFor(chief: boolean, equipment: boolean, live: boolean) {
    const base = chief ? NAV_CHIEF : equipment ? NAV_WORKER_EQUIPMENT : NAV_WORKER;
    return live ? [...base, NAV_LINKS] : base;
}

type ReportReq = Omit<ReportFormProps, 'onDone' | 'onCancel'>;

/** 공연 공개 전(실제 공연 DRAFT)에도 열 수 있는 주소: 등록(공개 자료 입력)과 행사 목록 이동 */
const OPEN_BEFORE_PUBLISH = ['/e/plan/draft', '/e/events'];

/**
 * WEB 레이아웃(관리자 중심, 넓은 화면):
 * 상단 파란 네비 바 + 흰 시트 본문 + 끌어 옮기는 소통 버튼(새 창으로 채팅) + 모달 보고.
 * 화면 이동은 같은 주소 체계를 쓰지만 보고·채팅은 페이지 이동 없이 현재 화면 위에 연다.
 */
export function WebShell() {
    const navigate = useNavigate();
    const { pathname } = useLocation();
    const d = useData();
    const badges = useBadges();
    const v = useViewer();
    // 실제 공연을 공개하기 전에는 등록 화면만 쓴다. 메뉴·알림·소통은 공개를 확정한 뒤 열고,
    // 다른 주소로 들어오면(새로고침·직접 입력 포함) 등록 화면으로 보낸다. 행사 목록 토글과 프로필은 그대로 쓴다.
    const { live } = useDemoState();
    const ev = useEvent();
    const locked = !!live && ev.state === 'DRAFT';
    const offLimits = locked && !OPEN_BEFORE_PUBLISH.some((p) => pathname.startsWith(p));
    const dispatch = useDispatch();
    const [report, setReport] = useState<ReportReq | null>(null);
    const [chat, setChat] = useState<{ open: boolean; room: string | null }>({
        open: false,
        room: null,
    });
    const [bell, setBell] = useState(false);
    // 소통 버튼·화면 안 채팅 창은 끌어서 옮길 수 있다(위치는 이 브라우저에 기억)
    // 소통 버튼은 알림 상자처럼 오른쪽 가장자리에 붙어 있고 위아래로만 옮긴다
    const fab = useDrag('scenecrew-chat-fab', undefined, 'y');
    const popRef = useRef<HTMLElement>(null);
    const pop = useDrag('scenecrew-chat-pop', popRef);
    // 소통은 브라우저 새 창으로 연다(Alt+Tab으로 오감). 팝업이 막혔을 때만 화면 안 채팅 창으로 연다
    const openChat = (room?: string | null) => {
        if (!openChatWindow(room)) setChat({ open: true, room: room ?? null });
    };
    // 총괄 운영(W02 구성도)은 네비 바 아래를 빈틈없이 채운다
    const fit = v.isChief && pathname === '/e/home';
    // 보고·대응(W04)은 화면 높이에 고정하고 목록·보고서만 안에서 스크롤한다
    const pinned = pathname.startsWith('/e/response');
    // 소통(W07) 화면에서는 같은 채팅을 겹쳐 띄우지 않는다
    const onChat = pathname.startsWith('/e/chat');

    const nav = navFor(v.isChief, v.canViewEquipment, !!live);
    const actions = useMemo<ShellActions>(
        () => ({
            variant: 'web',
            openReport: (o) => setReport({ ...o }),
            openChat: (room) => {
                if (!openChatWindow(room)) setChat({ open: true, room: room ?? null });
            },
            openIssue: (id) => navigate(`/e/response/${id}`),
            // 업무 상세는 공연 계획 큐시트(총괄 전용 배정 화면)에서 연다. 총괄이 아니면 열지 않는다
            openTask: (id) => v.isChief && navigate(`/e/tasks/${id}`),
            canOpenTask: v.isChief,
            openChange: (id) => navigate(`/e/changes/${id}`),
        }),
        [navigate, v.isChief],
    );

    return (
        <ShellActionsContext.Provider value={actions}>
            <div
                className={`${styles.shell} ${fit ? styles.fit : ''} ${pinned ? styles.pinned : ''}`}
                data-ui="web"
                data-platform="web"
            >
                <header className={styles.nav}>
                    <EventMenu />
                    <nav className={styles.menu} aria-label="주 메뉴">
                        {nav.map(([to, label, icon]) =>
                            locked ? (
                                <span
                                    key={to}
                                    className={styles.item}
                                    aria-disabled="true"
                                    title="공연을 공개하면 열립니다"
                                >
                                    <Icon name={icon} />
                                    <span>{label}</span>
                                </span>
                            ) : (
                                // 별칭 주소(업무 상세)도 같은 메뉴로 강조하려고 aria-current를 직접 정한다
                                <Link
                                    key={to}
                                    to={to}
                                    className={styles.item}
                                    aria-current={
                                        pathname.startsWith(to) ||
                                        ALIAS[to]?.some((a) => pathname.startsWith(a))
                                            ? 'page'
                                            : undefined
                                    }
                                >
                                    <Icon name={icon} />
                                    <span>{label}</span>
                                    {to === '/e/response' && badges.response > 0 && (
                                        <em>{badges.response}</em>
                                    )}
                                </Link>
                            ),
                        )}
                    </nav>
                    <div className={styles.tools}>
                        <div className={styles.bellWrap}>
                            <button
                                type="button"
                                className={styles.icon}
                                aria-label={`알림 ${badges.notices}건`}
                                aria-expanded={bell}
                                disabled={locked}
                                title={locked ? '공연을 공개하면 열립니다' : undefined}
                                onClick={() => setBell((x) => !x)}
                            >
                                <Icon name="bell" />
                                {badges.notices > 0 && <em>{badges.notices}</em>}
                            </button>
                            {bell && (
                                <Dropdown label="알림" onClose={() => setBell(false)}>
                                    <div className="row" style={{ padding: '10px 14px' }}>
                                        <b className="grow">알림</b>
                                        <button
                                            type="button"
                                            className="btn sm ghost"
                                            onClick={() => dispatch({ type: 'readAllNotices' })}
                                        >
                                            모두 읽음
                                        </button>
                                        <button
                                            type="button"
                                            className="btn sm"
                                            onClick={() => {
                                                setBell(false);
                                                navigate('/e/notices');
                                            }}
                                        >
                                            전체 보기
                                        </button>
                                    </div>
                                    <NoticeList
                                        limit={6}
                                        onOpen={(n) => {
                                            setBell(false);
                                            dispatch({ type: 'readNotice', id: n.id });
                                            if (n.ackId) navigate(`/e/ack/${n.id}`);
                                            else if (n.issue) actions.openIssue(n.issue);
                                            else if (n.change) actions.openChange(n.change);
                                            else if (n.task) actions.openTask(n.task);
                                        }}
                                    />
                                </Dropdown>
                            )}
                        </div>
                        <PersonaSelect className={styles.persona} />
                    </div>
                    <ProfileMenu />
                </header>
                <div className={styles.sheet}>
                    <main
                        id="main"
                        className={`${styles.main} ${fit ? styles.full : ''} ${pinned ? styles.pinnedMain : ''}`}
                        tabIndex={-1}
                    >
                        <ShellStatus />
                        {offLimits ? <Navigate to="/e/plan/draft" replace /> : <Outlet />}
                    </main>
                </div>

                {/* 문제 상황·다음 공연 알림: 모든 화면에서 오른쪽 가장자리 같은 자리 */}
                <AlertDock />
                {/* 소통 버튼은 공연 공개를 확정한 뒤부터 보인다 */}
                {!onChat && !locked && (
                    <button
                        type="button"
                        className={styles.fab}
                        style={fab.pos ? { top: fab.pos.y, bottom: 'auto' } : undefined}
                        aria-label="채팅 열기 (끌어서 옮길 수 있음)"
                        title="채팅 열기 / 끌어서 옮기기"
                        {...fab.handlers}
                        onClick={() => {
                            // 끌어서 옮긴 직후의 클릭은 창을 열지 않는다
                            if (fab.moved()) return;
                            if (chat.open) setChat({ open: false, room: null });
                            else openChat();
                        }}
                    >
                        <Icon name="chat" />
                    </button>
                )}
                {chat.open && !onChat && (
                    <section
                        ref={popRef}
                        className={styles.chatPop}
                        style={at(pop.pos)}
                        aria-label="채팅"
                    >
                        {/* 머리줄을 잡고 끌면 채팅 창을 옮긴다 */}
                        <header className={`row nw ${styles.dragHandle}`} {...pop.handlers}>
                            {chat.room && (
                                <button
                                    type="button"
                                    className="btn sm ghost"
                                    aria-label="채팅방 목록"
                                    onClick={() => setChat({ open: true, room: null })}
                                >
                                    <Icon name="back" />
                                </button>
                            )}
                            <b className="grow">
                                {chat.room ? d.rooms.find((r) => r.id === chat.room)?.name : '채팅'}
                            </b>
                            <button
                                type="button"
                                className="btn sm ghost"
                                aria-label="채팅 닫기"
                                onClick={() => setChat({ open: false, room: null })}
                            >
                                <Icon name="close" />
                            </button>
                        </header>
                        <div className={styles.chatBody}>
                            {chat.room ? (
                                <ChatThread roomId={chat.room} />
                            ) : (
                                <div style={{ padding: 12 }}>
                                    <RoomList onOpen={(room) => openChat(room)} />
                                </div>
                            )}
                        </div>
                    </section>
                )}
                {report && (
                    <Modal onClose={() => setReport(null)} label="상황 보고">
                        <ReportForm
                            {...report}
                            voiceFirst
                            onDone={() => setReport(null)}
                            onCancel={() => setReport(null)}
                        />
                    </Modal>
                )}
            </div>
        </ShellActionsContext.Provider>
    );
}

/** 끌어 옮긴 위치가 있으면 CSS 기본 위치(오른쪽 아래) 대신 그 왼쪽 위 좌표에 둔다 */
function at(pos: Pos | null): CSSProperties | undefined {
    return pos ? { left: pos.x, top: pos.y, right: 'auto', bottom: 'auto' } : undefined;
}

function Modal({
    children,
    onClose,
    label,
}: {
    children: ReactNode;
    onClose: () => void;
    label: string;
}) {
    return (
        <div
            className={styles.scrim}
            onClick={(e) => e.target === e.currentTarget && onClose()}
            onKeyDown={(e) => e.key === 'Escape' && onClose()}
        >
            <div className={styles.modal} role="dialog" aria-modal="true" aria-label={label}>
                {children}
            </div>
        </div>
    );
}
