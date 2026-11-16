import { useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { useData, useEvent, useMe, useViewer } from '../../domain/hooks';
import { EVENT_STATE_LABEL, roleLabel, shortRole } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Member } from '../../domain/types';
import { Chip } from '../../shared/ui/atoms';
import { Dialog } from '../../shared/ui/Dialog';
import { NameCircle } from '../../shared/ui/NameCircle';
import { AccountProfileForm } from '../../features/identity/screens';
import { LoginWordmark } from '../../screens/entry/LoginWordmark';
import styles from './WebShell.module.css';

/** 네비 바에서 여는 드롭다운. 바깥 투명 막을 누르면 닫힌다. */
export function Dropdown({
    children,
    onClose,
    label,
    className = '',
}: {
    children: ReactNode;
    onClose: () => void;
    label: string;
    className?: string;
}) {
    return (
        <>
            <button
                type="button"
                className={styles.scrimClear}
                aria-label="닫기"
                onClick={onClose}
            />
            <div className={`${styles.dropdown} ${className}`} role="dialog" aria-label={label}>
                {children}
            </div>
        </>
    );
}

/** 행사 이름 옆 ▼: 모든 행사 목록을 펼쳐 다른 행사로 옮겨 간다. */
export function EventMenu() {
    const ev = useEvent();
    const v = useViewer();
    const { events, live } = useDemoState();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const [open, setOpen] = useState(false);
    // 로그인 화면과 같은 흑요석 로고를 작게 둔다. 마우스를 올리면 그 위치 쪽으로 기울어진다
    const tilt = useRef<HTMLDivElement>(null);
    const onTilt = (e: MouseEvent<HTMLElement>) => {
        const r = e.currentTarget.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5;
        const py = (e.clientY - r.top) / r.height - 0.5;
        tilt.current?.style.setProperty('--tilt-x', `${Math.round(-py * 22)}deg`);
        tilt.current?.style.setProperty('--tilt-y', `${Math.round(px * 28)}deg`);
    };
    const onTiltEnd = () => {
        tilt.current?.style.setProperty('--tilt-x', '0deg');
        tilt.current?.style.setProperty('--tilt-y', '0deg');
    };
    return (
        <div className={styles.menuWrap}>
            {/* 행사 목록 토글 왼쪽의 SceneCrew 로고(장식) */}
            <span className={styles.brand} onMouseMove={onTilt} onMouseLeave={onTiltEnd}>
                <LoginWordmark ref={tilt} nav />
            </span>
            <button
                type="button"
                className={styles.ws}
                aria-expanded={open}
                aria-label={`${ev.name} / 행사 목록 ${open ? '닫기' : '열기'}`}
                onClick={() => setOpen((x) => !x)}
            >
                <span className={styles.wsName}>{ev.name}</span>
                <span className={styles.caret} aria-hidden="true">
                    ▼
                </span>
            </button>
            {open && (
                <Dropdown label="행사 목록" className={styles.left} onClose={() => setOpen(false)}>
                    <div className="row" style={{ padding: '10px 14px' }}>
                        <b className="grow">모든 행사 {events.length}</b>
                        {/* A01 종료 요약: 상시 메뉴가 아닌 총괄용 보조 진입 */}
                        {v.isChief && (
                            <button
                                type="button"
                                className="btn sm"
                                onClick={() => {
                                    setOpen(false);
                                    navigate('/e/archive');
                                }}
                            >
                                종료 요약
                            </button>
                        )}
                    </div>
                    {/* 실제 계정은 지금 연 공연만 들고 있다. 다른 공연은 서버 공연 목록에서 고른다(#362). */}
                    {live && (
                        <div className="row" style={{ padding: '0 14px 10px' }}>
                            <button
                                type="button"
                                className="btn sm"
                                onClick={() => {
                                    setOpen(false);
                                    navigate('/account/events');
                                }}
                            >
                                내 공연 목록 / 새 공연 만들기
                            </button>
                        </div>
                    )}
                    <ul className={styles.eventList}>
                        {events.map((e) => {
                            // 데모 데이터는 e1 한 벌이라 그 행사만 열 수 있다(EventsScreen과 같은 기준).
                            // 실서버 모드는 지금 연 공연 하나뿐이다.
                            const enterable = live
                                ? e.id === ev.id
                                : e.id === 'e1' && (e.state === 'LIVE' || e.state === 'ENDED');
                            const current = e.id === ev.id;
                            return (
                                <li key={e.id}>
                                    <button
                                        type="button"
                                        className={styles.eventItem}
                                        aria-current={current || undefined}
                                        disabled={!enterable}
                                        onClick={() => {
                                            setOpen(false);
                                            dispatch({ type: 'enterEvent', eventId: e.id });
                                            navigate('/e/home');
                                        }}
                                    >
                                        <span className="row between nw">
                                            <b className="small">{e.name}</b>
                                            <Chip kind={e.state === 'LIVE' ? 'ok' : ''}>
                                                {current ? '보는 중' : EVENT_STATE_LABEL[e.state]}
                                            </Chip>
                                        </span>
                                        <span className="xs muted">
                                            {e.date} / {e.time} / {e.place}
                                        </span>
                                        {!enterable && !live && (
                                            <span className="xs faint">
                                                초안 범위 밖 / 데모 행사만 열 수 있습니다
                                            </span>
                                        )}
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                </Dropdown>
            )}
        </div>
    );
}

/**
 * 오른쪽 위 사용자 탭: 누르면 내 정보를 펼치고 바로 고칠 수 있다.
 * 이름은 총괄만 바로 바뀌고, 팀원은 총괄에게 확인을 요청한 뒤 승인되면 바뀐다.
 */
export function ProfileMenu() {
    const d = useData();
    const { member } = useMe();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const [open, setOpen] = useState(false);
    const live = !!useDemoState().live;
    if (!member) return null;
    const isChief = member.role === 'CHIEF';
    const requests = isChief ? d.members.filter((m) => m.nameRequest) : [];
    const close = () => setOpen(false);
    return (
        <div className={styles.profWrap}>
            <button
                type="button"
                className={styles.prof}
                aria-haspopup="dialog"
                aria-label={`${member.name} ${roleLabel(d, member)} / 내 정보 열기`}
                onClick={() => setOpen(true)}
            >
                <NameCircle name={member.name} role={shortRole(member)} />
                {requests.length > 0 && <em>{requests.length}</em>}
            </button>
            {open && (
                <Dialog title="내 정보" onClose={close}>
                    <div className="stack">
                        {/* 위 가운데: 네비 바와 같은 동그라미. 그 밑에서 이름·역할·가용 시간을 바로 고친다 */}
                        <div className={styles.profHead}>
                            <NameCircle name={member.name} role={shortRole(member)} size="lg" />
                        </div>
                        <ProfileEdit key={member.id} member={member} live={live} />
                        {requests.length > 0 && (
                            <div className="stack s6">
                                <b className="small">이름 변경 요청 {requests.length}</b>
                                {requests.map((m) => (
                                    <div key={m.id} className="box row nw">
                                        <span className="grow small">
                                            {m.name} → <b>{m.nameRequest}</b>
                                            <span className="xs muted" style={{ display: 'block' }}>
                                                {roleLabel(d, m)}
                                            </span>
                                        </span>
                                        <button
                                            type="button"
                                            className="btn sm ghost"
                                            onClick={() =>
                                                dispatch({
                                                    type: 'nameDecision',
                                                    memberId: m.id,
                                                    approve: false,
                                                })
                                            }
                                        >
                                            반려
                                        </button>
                                        <button
                                            type="button"
                                            className="btn sm primary"
                                            onClick={() =>
                                                dispatch({
                                                    type: 'nameDecision',
                                                    memberId: m.id,
                                                    approve: true,
                                                })
                                            }
                                        >
                                            승인
                                        </button>
                                    </div>
                                ))}
                            </div>
                        )}
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                            <button
                                type="button"
                                className="btn sm ghost"
                                onClick={() => {
                                    close();
                                    dispatch({ type: 'logout' });
                                    navigate('/login');
                                }}
                            >
                                로그아웃
                            </button>
                        </div>
                    </div>
                </Dialog>
            )}
        </div>
    );
}

/**
 * 내 정보 팝업의 이름·역할·가용 시간. 데모는 바로 고친다(이름은 총괄만 바로 바뀌고 팀원은 확인 요청).
 * 실서버는 가용 시간 글이 없고(서버는 투입 가능 여부만 저장) 이름은 계정 화면에서 바꾼다.
 */
function ProfileEdit({ member: m, live }: { member: Member; live: boolean }) {
    const d = useData();
    const ev = useEvent();
    const dispatch = useDispatch();
    const isChief = m.role === 'CHIEF';
    const [name, setName] = useState(m.nameRequest ?? m.name);
    const [avail, setAvail] = useState(m.avail);
    const [saved, setSaved] = useState(false);
    const renamed = name.trim() !== '' && name.trim() !== m.name;
    const dirty = name.trim() !== (m.nameRequest ?? m.name) || avail !== m.avail;

    // 실서버: 공연 안 역할·가용은 보기만 하고, 계정 이름·동의·소셜 연결·탈퇴는 이 팝업에서 바로 고친다.
    // 계정 이름을 바꿔도 이미 참여한 공연의 이름은 바뀌지 않는다(BE02).
    if (live)
        return (
            <div className="stack">
                {/* 값이 시작하는 자리를 아래 닉네임·이메일·동의 칸(이름 칸 128px)과 맞춘다 */}
                <dl className={`kv ${styles.profileKv}`}>
                    <dt>공연 이름</dt>
                    <dd>{ev.name}</dd>
                    <dt>역할</dt>
                    <dd>{roleLabel(d, m)}</dd>
                    <dt>가용</dt>
                    <dd>{m.available ? '투입 가능' : '지금 투입 불가'}</dd>
                </dl>
                <AccountProfileForm withDeactivate />
            </div>
        );

    return (
        <form
            className="stack"
            onSubmit={(e) => {
                e.preventDefault();
                // 가능 업무·대기 장소는 이 팝업에서 다루지 않으므로 지금 값을 그대로 보낸다
                dispatch({
                    type: 'updateProfile',
                    profile: { name, avail, skills: m.skills ?? '', wait: m.wait ?? '' },
                });
                setSaved(true);
            }}
        >
            <div className="field">
                <label htmlFor="pfName">이름</label>
                <input
                    id="pfName"
                    className="inp"
                    value={name}
                    required
                    onChange={(e) => {
                        setName(e.target.value);
                        setSaved(false);
                    }}
                />
                {!isChief && (
                    <span className="hint">
                        {renamed
                            ? '저장하면 총괄에게 확인을 요청합니다. 승인 전까지는 지금 이름이 보입니다.'
                            : '이름 변경은 총괄 확인 후 반영됩니다.'}
                    </span>
                )}
            </div>
            <div className="field">
                <span className="lbl">역할</span>
                <span>{roleLabel(d, m)}</span>
            </div>
            <div className="field">
                <label htmlFor="pfAvail">가용 시간</label>
                <input
                    id="pfAvail"
                    className="inp"
                    value={avail}
                    required
                    onChange={(e) => {
                        setAvail(e.target.value);
                        setSaved(false);
                    }}
                />
            </div>
            {m.nameRequest && (
                <p className="note warn">
                    이름 변경 '{m.nameRequest}' / 총괄 확인을 기다리는 중입니다
                </p>
            )}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
                {saved && !dirty && (
                    <output className="xs muted" role="status">
                        저장했습니다
                    </output>
                )}
                <button type="submit" className="btn sm primary" disabled={!dirty}>
                    {!isChief && renamed ? '저장 / 이름 확인 요청' : '저장'}
                </button>
            </div>
        </form>
    );
}
