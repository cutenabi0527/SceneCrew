import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useData } from '../../domain/hooks';
import { ChatThread, RoomList } from '../../features/chat/ChatParts';
import { chatWindowPath, openChatWindow } from '../../features/chat/chat-window';
import styles from './web.module.css';

/**
 * W07 소통 새 창(메뉴 없음): /chat-window는 채널 목록, /chat-window/:roomId는 그 방의 대화.
 * 운영 화면과 다른 브라우저 창이라 Alt+Tab으로 오가며, 카카오톡처럼 방을 누르면 방마다 새 창으로 연다.
 */
export function WebChatWindow() {
    const { roomId } = useParams();
    const navigate = useNavigate();
    const d = useData();
    const room = roomId ? d.rooms.find((r) => r.id === roomId) : undefined;
    const title = roomId ? (room?.name ?? '채팅') : '채팅';
    useEffect(() => {
        document.title = `${title} - 소통`;
    }, [title]);
    return (
        <main className={styles.chatWindow} data-ui="web">
            {/* 창 제목·닫기는 브라우저 창 머리줄에 있어 화면 안 머리줄은 두지 않는다(제목은 보조기기용으로만) */}
            <h1 className="sr-only">{title}</h1>
            <div className={styles.chatWindowBody}>
                {roomId ? (
                    <ChatThread roomId={roomId} />
                ) : (
                    <div style={{ padding: 12 }}>
                        <RoomList
                            onOpen={(id) => {
                                // 팝업이 막히면 이 창 안에서 그 방으로 옮긴다
                                if (!openChatWindow(id)) navigate(chatWindowPath(id));
                            }}
                        />
                    </div>
                )}
            </div>
        </main>
    );
}
