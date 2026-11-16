import { useState, type ReactNode } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import { useData, useEvent, useMe } from '../../domain/hooks';
import { tasksOf } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Member } from '../../domain/types';
import { AssignBadge, AssignText } from '../../features/member/MemberParts';
import { Icon } from '../../shared/ui/Icon';
import { M3Switch } from './M3Switch';
import styles from './me.module.css';

/** 가용 시간 "17:00–21:30"을 시작·끝으로 나눈다. 모양이 다르면 null(한 칸으로 고친다) */
function splitAvail(avail: string): [string, string] | null {
    const m = /^\s*(\d{1,2}:\d{2})\s*[–~-]\s*(\d{1,2}:\d{2})\s*$/.exec(avail);
    return m ? [m[1].padStart(5, '0'), m[2].padStart(5, '0')] : null;
}

/**
 * 내 정보(APP 전체 화면, 머리줄 아바타에서 연다). 프로필 한 줄 · 참여 상태 · 근무(누르면 수정) · 그룹 안내 · 로그아웃.
 * 실서버(#373)는 가용 시간·가능 업무·대기 장소 API가 없어 가용 여부만 보이고 계정·동의 관리로 안내한다.
 */
export function AppMe() {
    const { member: m, group } = useMe();
    const ev = useEvent();
    const d = useData();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const live = !!useDemoState().live;
    if (!m) return null;
    const role = m.role === 'CHIEF' ? '총괄' : m.isReserve ? '예비 팀원' : '팀원';
    const hasTasks = tasksOf(d, m.id).length > 0;
    // 예비 팀원은 공연 중에 투입 가능 여부를 직접 바꾼다(웹 홈 내 참여 상태 카드와 같은 동작)
    const toggle = m.isReserve && ev.state === 'LIVE';
    return (
        <div className={styles.page}>
            <section aria-label="프로필" className={styles.profile}>
                <span className={styles.avatar} aria-hidden="true">
                    {m.name.slice(0, 1)}
                </span>
                <span className={styles.who}>
                    <b className={styles.name}>{m.name}</b>
                    <span className={styles.role}>
                        {m.role === 'CHIEF' ? role : `${group?.name ?? '그룹 없음'} · ${role}`}
                    </span>
                </span>
            </section>

            {m.nameRequest && (
                <p className={styles.notice}>
                    이름 변경 '{m.nameRequest}' · 총괄 확인을 기다리는 중입니다
                </p>
            )}

            {m.role !== 'CHIEF' && ((m.assign && m.assign !== 'DONE') || m.isReserve) && (
                <section aria-label="참여 상태" className={styles.status}>
                    {m.assign && m.assign !== 'DONE' && (
                        <>
                            <AssignBadge state={m.assign} />
                            <p>
                                <AssignText state={m.assign} />
                            </p>
                        </>
                    )}
                    {m.isReserve && (
                        <p>
                            {hasTasks
                                ? '예비 인원으로 투입되어 업무를 맡고 있습니다.'
                                : '배정된 업무가 없습니다. 총괄이 투입을 확정하면 알림과 함께 내 업무에 나타납니다.'}
                        </p>
                    )}
                </section>
            )}

            <section aria-labelledby="me-work" className={styles.list}>
                <h2 id="me-work" className={styles.listHead}>
                    근무
                </h2>
                {toggle && (
                    <div className={styles.row}>
                        <span id="me-available" className={styles.rowLabel}>
                            지금 투입 가능
                        </span>
                        <M3Switch
                            checked={m.available}
                            labelledBy="me-available"
                            onChange={(available) => dispatch({ type: 'setAvailable', available })}
                        />
                    </div>
                )}
                {live ? (
                    !toggle && (
                        <div className={styles.row}>
                            <span className={styles.rowLabel}>가용</span>
                            <span className={styles.rowValue}>
                                {m.available ? '투입 가능' : '지금 투입 불가'}
                            </span>
                        </div>
                    )
                ) : (
                    <>
                        <WorkRow label="가용 시간" value={m.avail} />
                        <WorkRow label="가능 업무" value={m.skills} />
                        <WorkRow label="대기 장소" value={m.wait} />
                    </>
                )}
                {/* 실서버: 이름·동의는 계정 화면에서 바꾼다(BE02) */}
                {live && (
                    <Link to="/account/profile" className={styles.row}>
                        <span className={styles.rowLabel}>계정·동의 관리</span>
                        <Icon name="chev" className={styles.chev} />
                    </Link>
                )}
            </section>

            {group?.guide && m.role !== 'CHIEF' && (
                <section aria-labelledby="me-guide" className={styles.guide}>
                    <h2 id="me-guide">{group.name} 안내</h2>
                    <p>{group.guide}</p>
                </section>
            )}

            <button
                type="button"
                className={styles.logout}
                onClick={() => {
                    dispatch({ type: 'logout' });
                    navigate('/login');
                }}
            >
                로그아웃
            </button>
        </div>
    );
}

/** 근무 한 줄: 누르면 내 정보 수정. 빈 값은 '추가하기' */
function WorkRow({ label, value }: { label: string; value?: string }) {
    const empty = !value?.trim();
    return (
        <Link to="/e/me/edit" className={styles.row}>
            <span className={styles.rowLabel}>{label}</span>
            <span className={empty ? styles.rowAdd : styles.rowValue}>
                {empty ? '추가하기' : value}
                <Icon name="chev" className={styles.chev} />
            </span>
        </Link>
    );
}

/** 내 정보 수정(APP 전체 화면). 저장하면 내 정보로 돌아간다. 실서버는 이 값들의 API가 없어 내 정보로 돌린다. */
export function AppMeEdit() {
    const { member } = useMe();
    const live = !!useDemoState().live;
    if (live) return <Navigate to="/e/me" replace />;
    if (!member) return null;
    return <MeForm key={member.id} member={member} />;
}

/** WEB 내 정보 수정(ProfileForm)과 같은 저장: 팀원의 이름 변경은 총괄 확인 요청이 된다 */
function MeForm({ member: m }: { member: Member }) {
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const isChief = m.role === 'CHIEF';
    const [name, setName] = useState(m.nameRequest ?? m.name);
    const parts = splitAvail(m.avail);
    const [start, setStart] = useState(parts?.[0] ?? '');
    const [end, setEnd] = useState(parts?.[1] ?? '');
    // 시작·끝으로 나눌 수 없는 값은 한 칸 그대로 고친다
    const [availText, setAvailText] = useState(m.avail);
    const [skills, setSkills] = useState(m.skills ?? '');
    const [wait, setWait] = useState(m.wait ?? '');
    const renamed = name.trim() !== '' && name.trim() !== m.name;
    return (
        <form
            className={styles.form}
            onSubmit={(e) => {
                e.preventDefault();
                const avail = parts ? `${start}–${end}` : availText;
                dispatch({ type: 'updateProfile', profile: { name, avail, skills, wait } });
                navigate('/e/me', { replace: true });
            }}
        >
            <Field
                id="me-name"
                label="이름"
                hint={
                    isChief
                        ? undefined
                        : renamed
                          ? '저장하면 총괄에게 확인을 요청합니다. 승인 전까지는 지금 이름이 보입니다'
                          : '이름을 바꾸면 총괄이 확인한 뒤 반영됩니다'
                }
            >
                <input
                    id="me-name"
                    className={styles.input}
                    value={name}
                    required
                    autoComplete="name"
                    onChange={(e) => setName(e.target.value)}
                />
            </Field>
            {parts ? (
                <fieldset className={styles.fieldset}>
                    <legend className={styles.label}>가용 시간</legend>
                    <div className={styles.range}>
                        <input
                            aria-label="시작"
                            type="time"
                            className={styles.input}
                            value={start}
                            required
                            onChange={(e) => setStart(e.target.value)}
                        />
                        <span aria-hidden="true">–</span>
                        <input
                            aria-label="끝"
                            type="time"
                            className={styles.input}
                            value={end}
                            required
                            onChange={(e) => setEnd(e.target.value)}
                        />
                    </div>
                </fieldset>
            ) : (
                <Field id="me-avail" label="가용 시간">
                    <input
                        id="me-avail"
                        className={styles.input}
                        value={availText}
                        required
                        placeholder="예: 17:00–21:30"
                        onChange={(e) => setAvailText(e.target.value)}
                    />
                </Field>
            )}
            <Field id="me-skills" label="가능 업무" hint="총괄이 사람을 찾을 때 봅니다">
                <input
                    id="me-skills"
                    className={styles.input}
                    value={skills}
                    placeholder="예: 무선 마이크, 수신기"
                    onChange={(e) => setSkills(e.target.value)}
                />
            </Field>
            <Field id="me-wait" label="대기 장소" hint="업무가 없을 때 있을 곳">
                <input
                    id="me-wait"
                    className={styles.input}
                    value={wait}
                    placeholder="예: 음향 부스 옆"
                    onChange={(e) => setWait(e.target.value)}
                />
            </Field>
            <div className={styles.bottom}>
                <button type="submit" className={styles.primary}>
                    {!isChief && renamed ? '저장 · 이름 확인 요청' : '저장'}
                </button>
            </div>
        </form>
    );
}

function Field({
    id,
    label,
    hint,
    children,
}: {
    id: string;
    label: string;
    hint?: string;
    children: ReactNode;
}) {
    return (
        <div className={styles.field}>
            <label htmlFor={id} className={styles.label}>
                {label}
            </label>
            {children}
            {hint && <span className={styles.hint}>{hint}</span>}
        </div>
    );
}
