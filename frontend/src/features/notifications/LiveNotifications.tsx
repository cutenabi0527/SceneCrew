import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure } from '../../shared/api/failure';
import { useIdentity } from '../identity/context';
import { displayTime } from '../plan-workflow/time';
import { resultText } from '../response/ui';
import { NotificationDesk } from './client';
import { TARGET_LABEL, targetPath, type Notification } from './contract';

// 내 알림(074·075). 알림을 눌러도 자동으로 읽음 처리하지 않고, 사용자가 "읽음 표시"를 누를 때만 보낸다.
// 대상 화면으로 가는 링크는 그 화면이 현재 참여 자격을 다시 확인한다(지난 회차의 알림일 수 있다).

/** 화면이 보이는 동안 알림을 다시 읽는 주기 */
export const REFRESH_MS = 30_000;

export function LiveNotifications() {
    const session = useIdentity();
    const desk = useMemo(() => new NotificationDesk(session), [session]);
    const [items, setItems] = useState<Notification[] | null>(null);
    const [next, setNext] = useState<string | null>(null);
    const [message, setMessage] = useState('알림을 불러오고 있습니다.');
    const [notice, setNotice] = useState('');
    const [busy, setBusy] = useState<string | null>(null);
    const [reload, setReload] = useState(0);

    useEffect(() => {
        let active = true;
        desk.list().then(
            (page) => {
                if (!active) return;
                setItems(page.items);
                setNext(page.nextCursor);
                setMessage('');
            },
            (e: unknown) => {
                if (!active) return;
                const f = classifyFailure(e);
                if (f.kind === 'cancelled') return;
                if (f.kind === 'unauthenticated') setItems(null);
                setMessage(
                    f.kind === 'unauthenticated'
                        ? '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.'
                        : '알림을 확인하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.',
                );
            },
        );
        return () => {
            active = false;
        };
    }, [desk, reload]);

    useEffect(() => {
        const timer = window.setInterval(() => {
            if (document.visibilityState === 'visible') setReload((n) => n + 1);
        }, REFRESH_MS);
        return () => window.clearInterval(timer);
    }, []);

    const more = async () => {
        if (!next || busy) return;
        setBusy('more');
        try {
            const page = await desk.list(next);
            setItems((prev) => {
                const seen = new Set(prev?.map((n) => n.id));
                return [...(prev ?? []), ...page.items.filter((n) => !seen.has(n.id))];
            });
            setNext(page.nextCursor === next ? null : page.nextCursor);
        } catch {
            setNotice('알림을 더 불러오지 못했습니다.');
        } finally {
            setBusy(null);
        }
    };

    const markRead = async (n: Notification) => {
        if (busy) return;
        setBusy(n.id);
        setNotice('');
        try {
            const result = await desk.read(n.id);
            if (result.kind === 'done')
                setItems((prev) => prev?.map((x) => (x.id === n.id ? result.data : x)) ?? null);
            else {
                setReload((v) => v + 1);
                setNotice(resultText(result, '읽음 표시'));
            }
        } finally {
            setBusy(null);
        }
    };

    const unread = items?.filter((n) => n.readAt === null).length ?? 0;
    return (
        <EntryFrame title="알림">
            <Link to="/account/events">내 공연 목록</Link>
            <button className="btn" type="button" onClick={() => setReload((n) => n + 1)}>
                알림 새로고침
            </button>
            {message && <p role="status">{message}</p>}
            {notice && <p role="status">{notice}</p>}
            {items && (
                <>
                    <p className="small">읽지 않은 알림 {unread}건</p>
                    <ul aria-label="알림 목록" className="stack">
                        {items.map((n) => (
                            <li key={n.id} className="stack">
                                <b className="small">
                                    {n.readAt === null ? '새 알림 / ' : ''}
                                    {n.title}
                                </b>
                                {n.body && <p className="small">{n.body}</p>}
                                <p className="xs">
                                    {displayTime(n.createdAt)} / {TARGET_LABEL[n.target.kind]}
                                    {n.readAt ? ` / ${displayTime(n.readAt)} 읽음` : ''}
                                </p>
                                <div className="row">
                                    <Link to={targetPath(n)}>
                                        {TARGET_LABEL[n.target.kind]} 열기
                                    </Link>
                                    {n.readAt === null && (
                                        <button
                                            className="btn"
                                            type="button"
                                            disabled={!!busy}
                                            onClick={() => void markRead(n)}
                                        >
                                            읽음 표시
                                        </button>
                                    )}
                                </div>
                            </li>
                        ))}
                        {!items.length && <li className="small">알림이 없습니다.</li>}
                    </ul>
                    {next && (
                        <button
                            className="btn"
                            type="button"
                            disabled={!!busy}
                            onClick={() => void more()}
                        >
                            알림 더 보기
                        </button>
                    )}
                </>
            )}
        </EntryFrame>
    );
}
