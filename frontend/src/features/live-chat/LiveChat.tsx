import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure } from '../../shared/api/failure';
import { useUrlState } from '../../shared/navigation/url-state';
import styles from '../chat/chat.module.css';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { displayTime } from '../plan-workflow/time';
import { ChatConnection, type ChatSnapshot } from './connection';
import { channelKey, parseChannelPage, type Channel } from './contract';

// 실제 서버의 전체·그룹 채팅(#307). 채팅으로 계획이 바뀌지 않는다. 대응이 필요하면 보고·대응에서 한다.

export function LiveChatRoute() {
    return <EventAccess>{(client) => <LiveChat client={client} />}</EventAccess>;
}

const STATUS_TEXT: Record<ChatSnapshot['status'], string> = {
    connecting: '채팅에 연결하고 있습니다.',
    ready: '',
    reconnecting: '연결이 끊겨 다시 연결하고 있습니다. 그동안 보낸 메시지는 확인 뒤 표시합니다.',
    stopped: '채팅 연결을 닫았습니다.',
};

function LiveChat({ client }: { client: Workflow }) {
    const [channels, setChannels] = useState<Channel[] | null>(null);
    const [message, setMessage] = useState('채널을 불러오고 있습니다.');
    // 고른 채널은 주소(?channel=)에 두어 새로고침해도 그 채널로 연다
    const [chosen, setSelected] = useUrlState<string>('channel', '');
    const [snap, setSnap] = useState<ChatSnapshot | null>(null);
    const [draft, setDraft] = useState('');

    useEffect(() => {
        let active = true;
        client.session.api
            .get(client.scope, `/events/${client.scope.eventId}/channels`, parseChannelPage)
            .then(
                (page) => {
                    if (!active) return;
                    setChannels(page.items);
                    setMessage(page.items.length ? '' : '들어갈 수 있는 채널이 없습니다.');
                },
                (e: unknown) => {
                    if (!active) return;
                    const f = classifyFailure(e);
                    setMessage(
                        f.kind === 'cancelled'
                            ? ''
                            : f.kind === 'access-denied'
                              ? '채팅을 볼 수 있는 권한이 없습니다. 서버에 채팅 기능이 아직 열리지 않았을 수도 있습니다.'
                              : '채널을 확인하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.',
                    );
                },
            );
        return () => {
            active = false;
        };
    }, [client]);

    const key = channels?.map((c) => channelKey(c.workGroupId)).join(',') ?? '';
    // 주소의 채널이 들어갈 수 있는 채널이면 그 채널, 아니면(처음·권한 없음) 첫 채널을 보인다
    const keys = channels?.map((c) => channelKey(c.workGroupId)) ?? [];
    const selected = keys.includes(chosen) ? chosen : (keys[0] ?? 'ALL');
    const connectionRef = useRef<ChatConnection | null>(null);
    useEffect(() => {
        if (!key) return;
        // 채널 집합이 같으면 연결을 다시 만들지 않는다
        const workGroupIds = key.split(',').map((k) => (k === 'ALL' ? null : k));
        const connection = new ChatConnection(client.session, client.scope, workGroupIds);
        connectionRef.current = connection;
        const off = connection.subscribe(setSnap);
        connection.start();
        return () => {
            off();
            connection.stop();
            if (connectionRef.current === connection) connectionRef.current = null;
        };
    }, [client, key]);

    const channel = channels?.find((c) => channelKey(c.workGroupId) === selected) ?? null;
    const shown = snap?.messages.filter((m) => channelKey(m.workGroupId) === selected) ?? [];
    const pending = snap?.pending.filter((p) => channelKey(p.workGroupId) === selected) ?? [];
    const canSend = !!snap && snap.status === 'ready' && snap.subscribed.has(selected);
    const me = client.scope.memberId;

    const send = () => {
        if (!connectionRef.current || !channel) return;
        if (connectionRef.current.send(channel.workGroupId, draft)) setDraft('');
    };

    return (
        <EntryFrame title="채팅">
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            {message && <p role="status">{message}</p>}
            {channels && channels.length > 0 && (
                <>
                    <div className="row" role="tablist" aria-label="채널">
                        {channels.map((c) => (
                            <button
                                key={channelKey(c.workGroupId)}
                                type="button"
                                role="tab"
                                className="btn"
                                aria-selected={channelKey(c.workGroupId) === selected}
                                onClick={() => setSelected(channelKey(c.workGroupId))}
                            >
                                {c.name}
                            </button>
                        ))}
                    </div>
                    {snap && (snap.stopReason || STATUS_TEXT[snap.status]) && (
                        <p role="status">{snap.stopReason ?? STATUS_TEXT[snap.status]}</p>
                    )}
                    {snap?.recoveryProblem && (
                        <p role="alert">
                            이전 메시지를 모두 받지 못했습니다.{' '}
                            <button
                                className="btn"
                                type="button"
                                onClick={() => void connectionRef.current?.recover()}
                            >
                                이전 메시지 다시 받기
                            </button>
                        </p>
                    )}
                    <div className={styles.thread}>
                        <p className={styles.hint}>
                            대화로 계획이 바뀌지는 않습니다. 대응이 필요하면 보고/대응에서 작성해
                            주세요. 첨부는 보낼 수 없습니다.
                        </p>
                        <ul className={styles.msgs} aria-label={`${channel?.name ?? ''} 메시지`}>
                            {shown.map((m) => (
                                <li
                                    key={m.id}
                                    className={`${styles.msg} ${m.senderMemberId === me ? styles.mine : ''}`}
                                >
                                    <span className="xs muted">
                                        {m.senderMemberId === me ? '나' : m.senderDisplayName}
                                    </span>
                                    <div className={styles.bubble}>{m.text}</div>
                                    <span className="xs faint mono">
                                        {displayTime(m.createdAt)}
                                    </span>
                                </li>
                            ))}
                            {pending.map((p) => (
                                <li
                                    key={p.clientMessageId}
                                    className={`${styles.msg} ${styles.mine}`}
                                >
                                    <div className={styles.bubble}>{p.text}</div>
                                    <span className="xs" role="status">
                                        {p.state === 'sending'
                                            ? '보내는 중'
                                            : p.state === 'unknown'
                                              ? '저장 여부 확인 필요'
                                              : `보내지 못함${p.code ? ` (${p.code})` : ''}`}
                                    </span>
                                    {p.state !== 'sending' && (
                                        <span className="row">
                                            <button
                                                className="btn"
                                                type="button"
                                                disabled={!canSend}
                                                onClick={() =>
                                                    connectionRef.current?.send(
                                                        p.workGroupId,
                                                        p.text,
                                                        p.clientMessageId,
                                                    )
                                                }
                                            >
                                                같은 메시지 다시 보내기
                                            </button>
                                            <button
                                                className="btn"
                                                type="button"
                                                onClick={() =>
                                                    connectionRef.current?.discard(
                                                        p.clientMessageId,
                                                    )
                                                }
                                            >
                                                지우기
                                            </button>
                                        </span>
                                    )}
                                </li>
                            ))}
                            {!shown.length && !pending.length && (
                                <li className="small">아직 메시지가 없습니다.</li>
                            )}
                        </ul>
                        <form
                            className={styles.compose}
                            onSubmit={(e) => {
                                e.preventDefault();
                                send();
                            }}
                        >
                            <input
                                className="input"
                                aria-label="보낼 메시지"
                                value={draft}
                                maxLength={1000}
                                onChange={(e) => setDraft(e.target.value)}
                                placeholder={canSend ? '메시지 입력' : '연결되면 보낼 수 있습니다'}
                            />
                            <button
                                className="btn primary"
                                type="submit"
                                disabled={!canSend || !draft.trim()}
                            >
                                보내기
                            </button>
                        </form>
                    </div>
                </>
            )}
        </EntryFrame>
    );
}
