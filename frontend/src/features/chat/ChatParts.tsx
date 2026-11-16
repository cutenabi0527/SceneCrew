import { useEffect, useRef, useState } from 'react';
import { useData, useEvent, useRoomMessages, useRooms, useSession } from '../../domain/hooks';
import { nameOf } from '../../domain/selectors';
import { newId, useDispatch } from '../../domain/store-context';
import { Icon } from '../../shared/ui/Icon';
import { Empty } from '../../shared/ui/atoms';
import styles from './chat.module.css';

const KIND = { EVENT: '전체 채널', GROUP: '직무 그룹 채널' } as const;
const MAX = 1000;

/** 채널 목록(전체 + 직무 그룹, 고정): WEB 떠 있는 채팅 창 첫 화면, APP 채팅 탭 */
export function RoomList({ onOpen }: { onOpen: (roomId: string) => void }) {
    const rooms = useRooms();
    const d = useData();
    return (
        <div className={`lgroup ${styles.rooms}`}>
            {rooms.map(({ room, last }) => (
                <button
                    key={room.id}
                    type="button"
                    className="lrow"
                    onClick={() => onOpen(room.id)}
                >
                    <span className="li">
                        <Icon name="chat" />
                    </span>
                    <span className="grow">
                        <b>{room.name}</b> <span className="xs faint">{KIND[room.kind]}</span>
                        <br />
                        <span className="xs muted ellipsis" style={{ display: 'block' }}>
                            {last
                                ? `${last.name ?? nameOf(d, last.by)}: ${last.text}`
                                : '메시지가 없습니다'}
                        </span>
                    </span>
                </button>
            ))}
        </div>
    );
}

/** 실서버(#373): 서버 저장 전 내 메시지의 상태 */
const SEND_STATE = {
    sending: '보내는 중',
    unknown: '전송 확인 중',
    failed: '보내지 못함',
} as const;

/** 대화 내용 + 입력. 높이는 감싸는 레이아웃이 정한다. */
export function ChatThread({ roomId }: { roomId: string }) {
    const d = useData();
    const { me } = useSession();
    const msgs = useRoomMessages(roomId);
    const room = d.rooms.find((r) => r.id === roomId);
    const ev = useEvent();
    const dispatch = useDispatch();
    const [draft, setDraft] = useState('');
    const end = useRef<HTMLDivElement>(null);
    const live = ev.state === 'LIVE';

    useEffect(() => {
        end.current?.scrollIntoView({ block: 'end' });
    }, [msgs.length]);

    if (!room) return <Empty>채팅방을 찾을 수 없습니다</Empty>;
    return (
        <div className={styles.thread}>
            <p className={styles.hint}>
                대화로 계획이 바뀌지는 않습니다. 대응이 필요하면 기존 상황 보고 화면에서 직접 작성해
                주세요.
            </p>
            <div className={styles.msgs}>
                {msgs.map((m) => (
                    <div key={m.id} className={`${styles.msg} ${m.by === me ? styles.mine : ''}`}>
                        {m.by !== me && (
                            <span className="xs muted">{m.name ?? nameOf(d, m.by)}</span>
                        )}
                        <div className={styles.bubble}>{m.text}</div>
                        <span className="xs faint mono">
                            {m.state ? SEND_STATE[m.state] : m.at}
                        </span>
                    </div>
                ))}
                <div ref={end} />
            </div>
            <form
                className={styles.compose}
                onSubmit={(e) => {
                    e.preventDefault();
                    if (!draft.trim()) return;
                    dispatch({ type: 'send', room: roomId, text: draft.trim(), id: newId('c') });
                    setDraft('');
                }}
            >
                <input
                    className="inp"
                    aria-label="메시지"
                    placeholder={
                        live ? `메시지 (최대 ${MAX}자)` : '종료된 행사는 읽기만 할 수 있습니다'
                    }
                    maxLength={MAX}
                    disabled={!live}
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                />
                <button type="submit" className="btn primary" aria-label="보내기" disabled={!live}>
                    <Icon name="send" />
                </button>
            </form>
        </div>
    );
}
