import { useNavigate } from 'react-router';
import { useMe, useSession } from '../../domain/hooks';
import { EVENT_STATE_LABEL } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { Chip } from '../../shared/ui/atoms';
import styles from './entry.module.css';

/**
 * 참여 행사·초대 목록(C02). 로그인 뒤 행사 목록 화면과 APP "행사 목록" 탭이 같은 목록을 쓴다.
 * 데모 데이터는 e1 한 벌이라 그 행사만 들어갈 수 있다.
 */
/** onPick: 행사를 고른 뒤 할 일(예: 펼친 목록 닫기) */
export function EventList({ onPick }: { onPick?: () => void } = {}) {
    const { events, live } = useDemoState();
    const { eventId } = useSession();
    const { member, invitee } = useMe();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    return (
        <div className={styles.events}>
            {events.map((e) => {
                // 실서버 모드는 지금 연 공연 하나만 들고 있다(#362). 데모는 e1 한 벌만 열 수 있다.
                const invited = !live && !member && !!invitee && e.id === 'e1';
                const open = e.state === 'LIVE' || e.state === 'ENDED';
                const current = e.id === eventId && !!member;
                return (
                    <button
                        key={e.id}
                        type="button"
                        className={styles.event}
                        aria-current={current || undefined}
                        disabled={
                            live
                                ? e.id !== eventId
                                : !(e.id === 'e1' && open && (member || invited))
                        }
                        onClick={() => {
                            onPick?.();
                            dispatch({ type: 'enterEvent', eventId: e.id });
                            navigate(invited ? '/join' : '/e/home');
                        }}
                    >
                        <div className="row between nw">
                            <b>{e.name}</b>
                            {invited ? (
                                <Chip kind="blue">초대받음</Chip>
                            ) : (
                                <Chip kind={e.state === 'LIVE' ? 'ok' : ''}>
                                    {current ? '보는 중' : EVENT_STATE_LABEL[e.state]}
                                </Chip>
                            )}
                        </div>
                        <span className="xs muted">
                            {e.date} / {e.time} / {e.place}
                        </span>
                        {invited && (
                            <span className="xs">그룹을 골라 참여하면 업무가 배정됩니다 ›</span>
                        )}
                        {!live && e.id !== 'e1' && (
                            <span className="xs faint">
                                초안 범위 밖 / 데모 행사만 열 수 있습니다
                            </span>
                        )}
                    </button>
                );
            })}
        </div>
    );
}
