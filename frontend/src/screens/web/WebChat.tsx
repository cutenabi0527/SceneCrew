import { useNavigate, useParams } from 'react-router';
import { useRooms } from '../../domain/hooks';
import { ChatThread, RoomList } from '../../features/chat/ChatParts';
import { Empty } from '../../shared/ui/atoms';
import styles from './web.module.css';

/**
 * W07 소통(총괄 WEB): 전체·현재 직무 그룹 고정 채널. 왼쪽 채널 목록, 오른쪽 대화.
 * APP 소통 탭과 같은 RoomList·ChatThread를 넓게 배치한다. 채팅에서 공식 보고를 만들지 않는다.
 */
export function WebChat() {
    const { roomId } = useParams();
    const navigate = useNavigate();
    const rooms = useRooms();
    const current = roomId ?? rooms[0]?.room.id;
    return (
        <div className={styles.chatPage}>
            <section className={styles.chatRooms} aria-label="채널 목록">
                <h1 className="h2">소통</h1>
                <RoomList onOpen={(id) => navigate(`/e/chat/${id}`, { replace: true })} />
            </section>
            <section className={styles.chatMain} aria-label="대화">
                {current ? <ChatThread roomId={current} /> : <Empty>참여한 채널이 없습니다</Empty>}
            </section>
        </div>
    );
}
