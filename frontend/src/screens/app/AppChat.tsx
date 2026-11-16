import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useData, useEvent, useRoomMessages, useRooms, useSession } from '../../domain/hooks';
import { nameOf } from '../../domain/selectors';
import { newId, useDispatch } from '../../domain/store-context';
import { useShell } from '../../layouts/shell-actions';
import { Icon } from '../../shared/ui/Icon';
import { Empty } from '../../shared/ui/atoms';
import styles from './AppChat.module.css';

const KIND = { EVENT: '전체 채널', GROUP: '직무 그룹 채널' } as const;
const MAX = 1000;
/** 실서버(#373): 서버 저장 전 내 메시지의 상태 */
const SEND_STATE = {
    sending: '보내는 중',
    unknown: '전송 확인 중',
    failed: '보내지 못함',
} as const;

/**
 * 소통 탭(APP): 메신저처럼 채널 줄 목록(전체 → 직무 그룹). 업무 화면과 달리 카드 모양을 쓰지 않는다.
 * 방 목록·대화의 데이터와 보내기 동작은 WEB 채팅 부품(features/chat/ChatParts)과 같다.
 */
export function AppChat() {
    const navigate = useNavigate();
    const rooms = useRooms();
    const d = useData();
    if (!rooms.length) return <Empty icon="chat">참여한 채널이 없습니다</Empty>;
    return (
        <ul className={styles.rooms} aria-label="채널">
            {rooms.map(({ room, last }) => (
                <li key={room.id}>
                    <button
                        type="button"
                        className={styles.room}
                        onClick={() => navigate(`/e/chat/${room.id}`)}
                    >
                        <span className={styles.roomIcon}>
                            <Icon name="chat" />
                        </span>
                        <span className={styles.roomText}>
                            <span className={styles.roomHead}>
                                <b>{room.name}</b> <span>{KIND[room.kind]}</span>
                            </span>
                            <span className={styles.last}>
                                {last
                                    ? `${last.name ?? nameOf(d, last.by)}: ${last.text}`
                                    : '메시지가 없습니다'}
                            </span>
                        </span>
                    </button>
                </li>
            ))}
        </ul>
    );
}

/** 채팅방(APP 전체 화면). 머리줄 방 이름은 쉘이 그린다. */
export function AppRoom() {
    const { roomId = '' } = useParams();
    const d = useData();
    const { me } = useSession();
    const msgs = useRoomMessages(roomId);
    const room = d.rooms.find((r) => r.id === roomId);
    const ev = useEvent();
    const shell = useShell();
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
                대화로 계획이 바뀌지는 않습니다. 대응이 필요하면{' '}
                <button type="button" className={styles.link} onClick={() => shell.openReport()}>
                    상황 보고 화면
                </button>
                에서 직접 작성해 주세요.
            </p>
            <div className={styles.msgs}>
                {!msgs.length && <p className={styles.none}>아직 메시지가 없습니다.</p>}
                {msgs.map((m) => {
                    const mine = m.by === me;
                    return (
                        <div key={m.id} className={`${styles.msg} ${mine ? styles.mine : ''}`}>
                            {!mine && (
                                <span className={styles.who}>{m.name ?? nameOf(d, m.by)}</span>
                            )}
                            <div className={styles.bubble}>{m.text}</div>
                            <span className={styles.at}>
                                {m.state ? SEND_STATE[m.state] : m.at}
                            </span>
                        </div>
                    );
                })}
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
                    className={styles.input}
                    aria-label="메시지"
                    placeholder={
                        live ? `메시지 (최대 ${MAX}자)` : '종료된 행사는 읽기만 할 수 있습니다'
                    }
                    maxLength={MAX}
                    disabled={!live}
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                />
                <button type="submit" className={styles.send} aria-label="보내기" disabled={!live}>
                    <svg
                        width="20"
                        height="20"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                    >
                        <path d="M21 3 10 14" />
                        <path d="M21 3 14 21l-4-7-7-4z" />
                    </svg>
                </button>
            </form>
        </div>
    );
}
