import { useCallback, useEffect, useMemo, useState } from 'react';
import { useInvalidation } from '../../shared/live/invalidation';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { displayTime } from '../plan-workflow/time';
import { responder as responderOf } from './client';
import { ACK_KIND_LABEL, type Acknowledgment } from './contract';
import { readText, resultText } from './ui';

// 승인으로 바뀐 내 업무의 명시 확인(077). 확인은 "변경을 봤다"는 뜻이며 업무 수행·완료와 별개다.
// 확인 요청은 화면에 보인 대상 묶음의 해시를 그대로 보낸다. 그사이 대상이 바뀌면 서버가 거절하고 다시 조회한다.

export function LiveChangesRoute() {
    return <EventAccess>{(client) => <LiveChanges client={client} />}</EventAccess>;
}

const STATE_LABEL = {
    PENDING: '확인 필요',
    ACKNOWLEDGED: '확인함',
    NO_CURRENT_TARGETS: '현재 바뀐 업무 없음',
} as const;

function LiveChanges({ client }: { client: Workflow }) {
    const responder = useMemo(() => responderOf(client.session, client.scope), [client]);
    const [items, setItems] = useState<Acknowledgment[] | null>(null);
    const [message, setMessage] = useState('바뀐 업무를 불러오고 있습니다.');
    const [busy, setBusy] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        try {
            const page = await responder.myChanges();
            setItems(page.items);
            setMessage(
                page.items.some((a) => a.state === 'PENDING') ? '' : '확인할 변경이 없습니다.',
            );
        } catch (e) {
            setMessage(readText(e));
        }
    }, [responder]);
    // 서버가 내 변경 확인 대상이 바뀌었다고 알리면 다시 조회한다
    useInvalidation(client.session.api, client.scope, ['MY_CHANGES'], () => void refresh());
    useEffect(() => {
        let active = true;
        responder.myChanges().then(
            (page) => {
                if (!active) return;
                setItems(page.items);
                setMessage(
                    page.items.some((a) => a.state === 'PENDING') ? '' : '확인할 변경이 없습니다.',
                );
            },
            (e: unknown) => {
                if (active) setMessage(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [responder]);

    const confirm = async (a: Acknowledgment) => {
        if (busy) return;
        setBusy(a.id);
        try {
            const result = await responder.ack(a.id, a.targetsHash);
            if (result.kind === 'done') {
                setItems((prev) => prev?.map((x) => (x.id === a.id ? result.data : x)) ?? null);
                setMessage('변경을 확인했습니다. 업무 수행은 내 업무에서 이어 갑니다.');
            } else {
                await refresh();
                setMessage(resultText(result, '변경 확인'));
            }
        } finally {
            setBusy(null);
        }
    };

    return (
        <EntryFrame title="변경 확인">
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            <p className="small">
                총괄이 승인해 바뀐 내 업무입니다. 내용을 보고 확인을 눌러주세요. 확인은 수행/완료와
                별개입니다.
            </p>
            <button className="btn" type="button" onClick={() => void refresh()}>
                변경 다시 조회
            </button>
            {message && <p role="status">{message}</p>}
            <ul aria-label="바뀐 업무" className="stack">
                {(items ?? [])
                    .filter((a) => a.state !== 'NO_CURRENT_TARGETS')
                    .map((a) => (
                        <li key={a.id} className="stack">
                            <b>{STATE_LABEL[a.state]}</b>
                            <ul className="small">
                                {a.targets.map((t) => (
                                    <li key={t.taskId}>
                                        {t.title} /{' '}
                                        {t.changeKinds.map((k) => ACK_KIND_LABEL[k]).join(', ')}{' '}
                                        변경
                                        {t.removed && ' / 내 업무에서 빠짐'} /{' '}
                                        {displayTime(t.changedAt)}
                                    </li>
                                ))}
                            </ul>
                            {a.state === 'PENDING' ? (
                                <button
                                    className="btn primary"
                                    type="button"
                                    disabled={busy !== null}
                                    onClick={() => void confirm(a)}
                                >
                                    변경 내용을 확인했습니다
                                </button>
                            ) : (
                                a.acknowledgedAt && (
                                    <p className="xs">확인 시각 {displayTime(a.acknowledgedAt)}</p>
                                )
                            )}
                        </li>
                    ))}
            </ul>
            <Link to={`/account/events/${client.scope.eventId}/tasks`}>내 업무 열기</Link>
        </EntryFrame>
    );
}
