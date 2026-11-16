/**
 * WEB 소통 창을 브라우저 새 창으로 연다. 별도 창이라 운영 화면과 Alt+Tab으로 오간다.
 * 채널 목록은 창 하나, 대화방은 방마다 창 하나(같은 방을 다시 열면 그 창을 앞으로 가져온다).
 * 팝업이 막혀 창을 열지 못하면 null을 돌려준다. 그때는 호출한 쪽이 화면 안 채팅 창으로 대신 연다.
 */
export function chatWindowPath(roomId?: string | null) {
    return roomId ? `/chat-window/${encodeURIComponent(roomId)}` : '/chat-window';
}

export function openChatWindow(roomId?: string | null): Window | null {
    const name = roomId ? `scenecrew-chat-${roomId}` : 'scenecrew-chat';
    // 대화방 창은 목록 창 옆에 조금 비켜 띄운다
    const left = window.screenX + window.outerWidth - (roomId ? 860 : 440);
    const top = window.screenY + (roomId ? 120 : 80);
    const w = window.open(
        chatWindowPath(roomId),
        name,
        `popup=yes,width=420,height=640,left=${Math.max(0, left)},top=${Math.max(0, top)}`,
    );
    w?.focus();
    return w;
}
