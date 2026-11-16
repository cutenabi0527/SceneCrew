import { useState, useSyncExternalStore } from 'react';
import { useEvent } from '../domain/hooks';
import { Note } from '../shared/ui/atoms';

function subscribe(listener: () => void) {
    window.addEventListener('online', listener);
    window.addEventListener('offline', listener);
    return () => {
        window.removeEventListener('online', listener);
        window.removeEventListener('offline', listener);
    };
}
const online = () => navigator.onLine;

/**
 * WEB·APP 공통 상태 띠: 종료(읽기 전용)·연결 끊김·재접속을 화면 위에 알린다.
 * 연결이 끊겨도 입력은 지우지 않고, 다시 연결되면 현재 상태를 다시 확인하도록 안내한다(늦은 응답으로 덮지 않음).
 */
export function ShellStatus() {
    const ev = useEvent();
    const isOnline = useSyncExternalStore(subscribe, online, () => true);
    const [wasOffline, setWasOffline] = useState(!isOnline);
    const [reconnected, setReconnected] = useState(false);
    if (!isOnline && !wasOffline) {
        setWasOffline(true);
        setReconnected(false);
    }
    if (isOnline && wasOffline) {
        setWasOffline(false);
        setReconnected(true);
    }
    if (ev.state !== 'ENDED' && isOnline && !reconnected) return null;
    return (
        <div className="stack s6" role="status" aria-live="polite">
            {ev.state === 'ENDED' && (
                <Note kind="warn" icon="lock">
                    종료된 행사입니다. 계획/업무/보고/알림/채팅은 읽기만 할 수 있고, 새
                    보고/관측/수행과 변경 적용은 막혀 있습니다.
                </Note>
            )}
            {!isOnline && (
                <Note kind="warn" icon="warn">
                    연결이 끊겼습니다. 입력한 내용은 그대로 두며, 다시 연결되면 현재 상태를 다시
                    확인합니다. 보낸 요청의 결과는 자동으로 다시 보내지 않습니다.
                </Note>
            )}
            {isOnline && reconnected && (
                <Note kind="accent" icon="bell">
                    다시 연결되었습니다. 화면의 업무/알림/관측이 지금 상태인지 확인해주세요.{' '}
                    <button type="button" className="linkbtn" onClick={() => setReconnected(false)}>
                        닫기
                    </button>
                </Note>
            )}
        </div>
    );
}
