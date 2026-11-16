import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useData, useEvent, useMe } from '../../domain/hooks';
import { bundlesOf, groupName, unassignedBundles } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { EventInfo } from '../../domain/types';
import { AppEntryPage } from './AppEntryPage';
import { M3Switch } from './M3Switch';
import { APP_EVENT_STATE, placeText as place, useEventGate } from './event-gate';
import page from './entry-page.module.css';
import styles from './entry-app.module.css';

/**
 * 공연 목록(APP, 로그인 뒤). 지금 열 수 있는 운영 중 공연은 맨 위 큰 카드 + '내 업무 열기',
 * 나머지는 작은 목록. 계정과 로그아웃은 아래 고정 줄. 공연을 여는 규칙은 웹 공용 EventList와 같다.
 */
export function AppEvents() {
    const d = useData();
    const { events, live } = useDemoState();
    const { member, invitee } = useMe();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const gate = useEventGate();
    const enter = (e: EventInfo) => {
        dispatch({ type: 'enterEvent', eventId: e.id });
        navigate(gate(e).invited ? '/join' : '/e/home');
    };
    const main = events.filter((e) => e.state === 'LIVE' && gate(e).enabled);
    const others = events.filter((e) => !main.includes(e));
    const demoName = events.find((e) => e.id === 'e1')?.name;
    const name = member?.name ?? invitee?.name ?? '';
    const role = !member
        ? '참여 전'
        : member.role === 'CHIEF'
          ? '총괄'
          : `${groupName(d, member.groupId)} ${member.isReserve ? '예비 팀원' : '팀원'}`;
    return (
        <AppEntryPage
            foot={
                <div className={styles.account}>
                    <span>
                        {name} · {role}
                    </span>
                    <button
                        type="button"
                        className={page.textBtn}
                        onClick={() => {
                            dispatch({ type: 'logout' });
                            navigate('/login');
                        }}
                    >
                        로그아웃
                    </button>
                </div>
            }
        >
            <h1 className={styles.h1}>내 공연</h1>
            {main.length > 0 && (
                <section aria-labelledby="ev-now" className={styles.group}>
                    <h2 id="ev-now" className={styles.groupHead}>
                        지금 운영 중
                    </h2>
                    {main.map((e) => {
                        const { invited } = gate(e);
                        return (
                            <button
                                key={e.id}
                                type="button"
                                className={styles.hero}
                                onClick={() => enter(e)}
                            >
                                <span className={styles.heroHead}>
                                    <span className={styles.heroName}>{e.name}</span>
                                    <span className={invited ? styles.chipBlue : styles.chipOk}>
                                        {invited ? '초대받음' : APP_EVENT_STATE[e.state]}
                                    </span>
                                </span>
                                <span className={styles.heroMeta}>
                                    {e.date} {e.time} · {place(e)}
                                </span>
                                <span className={styles.heroBtn}>
                                    {invited ? '그룹 골라 참여하기' : '내 업무 열기'}
                                </span>
                            </button>
                        );
                    })}
                </section>
            )}
            {others.length > 0 && (
                <section aria-labelledby="ev-others" className={styles.group}>
                    <h2 id="ev-others" className={styles.groupHead}>
                        {main.length > 0 ? '다른 공연' : '공연'}
                    </h2>
                    <div className={styles.rows}>
                        {others.map((e) => (
                            <button
                                key={e.id}
                                type="button"
                                className={styles.evRow}
                                disabled={!gate(e).enabled}
                                onClick={() => enter(e)}
                            >
                                <span className={styles.pickText}>
                                    <span className={styles.evRowName}>{e.name}</span>
                                    <span className={styles.pickMeta}>
                                        {e.date} · {place(e)}
                                    </span>
                                </span>
                                <span className={styles.pickState}>{APP_EVENT_STATE[e.state]}</span>
                            </button>
                        ))}
                    </div>
                </section>
            )}
            {events.length === 0 && <p className={page.notice}>참여한 공연이 없습니다.</p>}
            {!live && demoName && events.length > 1 && (
                <p className={styles.demoNote}>데모에서는 {demoName}만 열 수 있습니다</p>
            )}
        </AppEntryPage>
    );
}

/** 받침이 있으면(ㄹ 제외) '으로', 없으면 '로' */
function ro(word: string) {
    const code = word.charCodeAt(word.length - 1) - 0xac00;
    if (code < 0 || code > 11171) return '로';
    const jong = code % 28;
    return jong === 0 || jong === 8 ? '로' : '으로';
}

/**
 * 초대 참여(APP, C03 · P03). 그룹마다 이름·시간·맡을 수 있는 묶음 칩만 보이고, 고른 그룹 안내는 아래 상자 하나.
 * 맡을 묶음이 있는 그룹을 위로 올린다. 참여 dispatch와 그룹 링크(?group=) 처리는 웹 공용 JoinScreen과 같다.
 */
export function AppJoin() {
    const d = useData();
    const ev = useEvent();
    const { invitee } = useMe();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const [reserve, setReserve] = useState(false);
    const [available, setAvailable] = useState(true);
    const groups = d.groups
        .filter((g) => bundlesOf(d, g.id).length > 0)
        .map((g) => ({ g, open: unassignedBundles(d, g.id).length }))
        .sort((a, b) => Number(b.open > 0) - Number(a.open > 0));
    const [params] = useSearchParams();
    const linked = params.get('group');
    const [groupId, setGroupId] = useState(() =>
        linked && groups.some(({ g }) => g.id === linked) ? linked : '',
    );
    const picked = d.groups.find((g) => g.id === groupId);
    return (
        <AppEntryPage
            foot={
                <>
                    <button
                        type="submit"
                        form="join-form"
                        className={page.primary}
                        disabled={!picked}
                    >
                        {picked ? `${picked.name}${ro(picked.name)} 참여` : '그룹을 고르세요'}
                    </button>
                    <p className={styles.footSmall}>
                        참여하면 이 그룹에서 담당이 없는 업무를 찾아 배정합니다
                    </p>
                    <button
                        type="button"
                        className={page.textBtn}
                        onClick={() => {
                            dispatch({ type: 'leaveEvent' });
                            navigate('/events');
                        }}
                    >
                        나중에 참여
                    </button>
                </>
            }
        >
            <form
                id="join-form"
                className={styles.join}
                onSubmit={(e) => {
                    e.preventDefault();
                    if (!groupId) return;
                    dispatch({ type: 'join', groupId, reserve, available });
                    navigate('/e/home', { replace: true });
                }}
            >
                <div className={styles.joinHead}>
                    <span className={styles.pickMeta}>
                        {ev.name} · {ev.date} {ev.time}
                    </span>
                    <h1 className={styles.h1}>{invitee?.name ?? ''} 님, 함께할 그룹을 고르세요</h1>
                </div>
                <fieldset className={styles.radios}>
                    <legend className="sr-only">직무 그룹</legend>
                    {groups.map(({ g, open }) => {
                        const all = bundlesOf(d, g.id).flatMap((b) => b.tasks);
                        const first = all.reduce((a, t) => (t.start < a ? t.start : a), '99:99');
                        const last = all.reduce((a, t) => (t.end > a ? t.end : a), '00:00');
                        return (
                            <label key={g.id} className={styles.radio}>
                                <input
                                    type="radio"
                                    name="group"
                                    value={g.id}
                                    checked={groupId === g.id}
                                    onChange={() => setGroupId(g.id)}
                                />
                                <span className={styles.dot} aria-hidden="true" />
                                <span className={styles.radioText}>
                                    <span className={styles.radioHead}>
                                        <b>{g.name}</b>
                                        <span className={styles.pickMeta}>
                                            {first}–{last}
                                        </span>
                                    </span>
                                    {open > 0 ? (
                                        <span className={styles.chipOk}>
                                            맡을 수 있는 업무 묶음 {open}
                                        </span>
                                    ) : (
                                        <span className={styles.none}>지금 맡을 묶음 없음</span>
                                    )}
                                </span>
                            </label>
                        );
                    })}
                </fieldset>
                {picked?.guide && (
                    <p className={styles.guide}>
                        <b>{picked.name}</b> · {picked.guide}
                    </p>
                )}
                <div className={styles.switches}>
                    <div className={styles.switchRow}>
                        <span id="join-available" className={styles.switchLabel}>
                            지금 활동할 수 있어요
                        </span>
                        <M3Switch
                            checked={available}
                            onChange={setAvailable}
                            labelledBy="join-available"
                        />
                    </div>
                    <div className={styles.switchRow}>
                        <span className={styles.switchText}>
                            <span id="join-reserve" className={styles.switchLabel}>
                                예비 인원으로 참여
                            </span>
                            <span id="join-reserve-sub" className={styles.switchSub}>
                                바로 업무를 받지 않고, 총괄이 투입을 정하면 맡습니다
                            </span>
                        </span>
                        <M3Switch
                            checked={reserve}
                            onChange={setReserve}
                            labelledBy="join-reserve"
                            describedBy="join-reserve-sub"
                        />
                    </div>
                </div>
            </form>
        </AppEntryPage>
    );
}
