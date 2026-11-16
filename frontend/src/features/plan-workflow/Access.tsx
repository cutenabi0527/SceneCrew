import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { useIdentity } from '../identity/context';
import { observe } from '../identity/observe';
import type { EventSummary } from '../identity/contract';
import type { ParticipationScope } from '../../shared/scope/scope';
import { asUuid } from '../../shared/api/contract';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { workflow, type Workflow } from './client';
import type { Frame } from './frame';

/**
 * 공연 참여를 확인한 뒤 그 공연의 클라이언트를 넘긴다. 기본은 주소의 :eventId를 쓰고,
 * 주소에 공연이 없는 디자인 화면(/e/…, #362)은 eventId와 셸용 틀(frame)을 직접 넘긴다.
 */
export function EventAccess({
    eventId: given,
    frame = EntryFrame,
    children,
}: {
    eventId?: string;
    frame?: Frame;
    children: (client: Workflow, event: EventSummary) => ReactNode;
}) {
    const session = useIdentity();
    const account = useSyncExternalStore(session.subscribe, session.snapshot).account;
    const { eventId: param = '' } = useParams();
    const eventId = given ?? param;
    return (
        <AccessContent key={`${account?.id}:${eventId}`} eventId={eventId} frame={frame}>
            {children}
        </AccessContent>
    );
}
function AccessContent({
    eventId,
    frame: Frame,
    children,
}: {
    eventId: string;
    frame: Frame;
    children: (client: Workflow, event: EventSummary) => ReactNode;
}) {
    const session = useIdentity();
    const [value, setValue] = useState<{ event: EventSummary; scope: ParticipationScope } | null>(
        null,
    );
    const [error, setError] = useState('');
    let valid = true;
    try {
        asUuid(eventId, 'eventId');
    } catch {
        valid = false;
    }
    useEffect(() => {
        if (!valid) return;
        return observe(
            () => session.event(eventId),
            (event) => {
                const scope = session.scopes.participation(eventId);
                if (scope && event.myParticipation.active) {
                    setValue({ event, scope });
                    setError('');
                } else {
                    setValue(null);
                    setError('현재 참여 중인 공연이 아닙니다.');
                }
            },
            () => {
                setValue(null);
                setError('공연과 현재 참여 정보를 확인하지 못했습니다.');
            },
        );
    }, [session, eventId, valid]);
    return value ? (
        <LiveScope
            key={`${value.scope.memberId}:${value.scope.participationRevision}`}
            scope={value.scope}
            frame={Frame}
        >
            {children(workflow(session, value.scope), value.event)}
        </LiveScope>
    ) : (
        <Frame title="공연 연결">
            <p role="status">
                {valid
                    ? error || '공연 참여 정보를 확인하고 있습니다.'
                    : '공연 주소를 확인해주세요.'}
            </p>
            <Link to="/account/events">내 공연 목록</Link>
        </Frame>
    );
}
function LiveScope({
    scope,
    frame: Frame,
    children,
}: {
    scope: ParticipationScope;
    frame: Frame;
    children: ReactNode;
}) {
    const current = useSyncExternalStore(
        (listener) => {
            scope.signal.addEventListener('abort', listener);
            return () => scope.signal.removeEventListener('abort', listener);
        },
        () => scope.isCurrent(),
    );
    return current ? (
        children
    ) : (
        <Frame title="참여 정보 변경">
            <p role="status">참여 정보가 바뀌었습니다. 공연을 다시 열어주세요.</p>
            <Link to="/account/events">내 공연 목록</Link>
        </Frame>
    );
}
