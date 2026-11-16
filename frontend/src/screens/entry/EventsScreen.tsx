import { useNavigate } from 'react-router';
import { useData, useMe } from '../../domain/hooks';
import { roleLabel } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import { EntryFrame } from './EntryFrame';
import { EventList } from './EventList';

/** 참여 행사·초대 목록(공유 화면). 운영 중 행사만 들어갈 수 있는 초안이다. */
export function EventsScreen() {
    const d = useData();
    const { member, invitee } = useMe();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const name = member?.name ?? invitee?.name ?? '';
    return (
        <EntryFrame title={`${name} 님의 행사`}>
            <EventList />
            <div className="row between">
                <span className="xs muted">
                    현재 보는 계정: {member ? roleLabel(d, member) : '참여 전'}
                </span>
                <button
                    type="button"
                    className="btn sm ghost"
                    onClick={() => {
                        dispatch({ type: 'logout' });
                        navigate('/login');
                    }}
                >
                    로그아웃
                </button>
            </div>
        </EntryFrame>
    );
}
