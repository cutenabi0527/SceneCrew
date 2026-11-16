import { useNavigate } from 'react-router';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { APP_EVENT_STATE, placeText, useEventGate } from './event-gate';
import { Icon } from '../../shared/ui/Icon';
import styles from './entry-app.module.css';

/**
 * 공연 바꾸기 시트의 목록(APP). 웹 공용 EventList와 같은 전환 동작을 카드 모양만 바꿔 그린다:
 * 지금 공연은 굵은 테두리 + 체크, 못 여는 공연은 회색 비활성, 데모 안내는 맨 아래 한 줄.
 */
export function AppEventList({ onPick }: { onPick?: () => void }) {
    const { events, live } = useDemoState();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const gate = useEventGate();
    const demoName = events.find((e) => e.id === 'e1')?.name;
    return (
        <div className={styles.sheetList}>
            {events.map((e) => {
                const { invited, enabled, current } = gate(e);
                return (
                    <button
                        key={e.id}
                        type="button"
                        className={styles.pick}
                        aria-current={current || undefined}
                        disabled={!enabled}
                        onClick={() => {
                            onPick?.();
                            dispatch({ type: 'enterEvent', eventId: e.id });
                            navigate(invited ? '/join' : '/e/home');
                        }}
                    >
                        <span className={styles.pickText}>
                            <span className={styles.pickName}>{e.name}</span>
                            <span className={styles.pickMeta}>
                                {e.date} {e.time} · {placeText(e)}
                            </span>
                        </span>
                        {invited ? (
                            <span className={styles.pickInvited}>초대받음</span>
                        ) : (
                            <span
                                className={
                                    e.state === 'LIVE' && enabled
                                        ? styles.pickLive
                                        : styles.pickState
                                }
                            >
                                {current && <Icon name="check" className={styles.pickCheck} />}
                                {APP_EVENT_STATE[e.state]}
                            </span>
                        )}
                    </button>
                );
            })}
            {!live && demoName && events.length > 1 && (
                <p className={styles.demoNote}>데모에서는 {demoName}만 열 수 있습니다</p>
            )}
        </div>
    );
}
