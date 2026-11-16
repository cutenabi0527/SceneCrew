import { useContext, type ReactNode } from 'react';
import { Navigate } from 'react-router';
import { useMe, useSession } from '../../domain/hooks';
import { AccountGuard } from '../../features/identity/screens';
import { LiveContext } from '../../live/context';
import { useDemoState } from '../../domain/store-context';

/** 로그인·행사 선택·참여 전에는 행사 화면으로 들어가지 않는다(WEB/APP 공통). */
export function RequireEvent({ children }: { children: ReactNode }) {
    const { me, eventId } = useSession();
    const { member } = useMe();
    // 실서버 모드에서 새로고침하면 공연을 서버에서 다시 읽는 동안 기다린다(로그인 화면으로 보내지 않는다).
    if (useContext(LiveContext)?.restoring)
        return <p role="status">공연을 다시 불러오고 있습니다.</p>;
    if (!me) return <Navigate to="/login" replace />;
    if (!eventId) return <Navigate to="/events" replace />;
    // 로그인 완료는 참여 성공이 아니다. 그룹을 골라 명시 참여해야 행사 화면이 열린다.
    if (!member) return <Navigate to="/join" replace />;
    return children;
}

export function RequireLogin({ children }: { children: ReactNode }) {
    const { me } = useSession();
    return me ? children : <Navigate to="/login" replace />;
}

/** 그룹 참여 화면: 초대받고 아직 참여하지 않은 계정만 */
export function RequireJoin({ children }: { children: ReactNode }) {
    const { me, eventId } = useSession();
    const { member, invitee } = useMe();
    if (!me) return <Navigate to="/login" replace />;
    if (!eventId) return <Navigate to="/events" replace />;
    if (member) return <Navigate to="/e/home" replace />;
    if (!invitee) return <Navigate to="/events" replace />;
    return children;
}

/** 행사 목록으로. 실제 계정(실서버 모드)은 서버 공연 목록, 데모는 데모 목록이다(#362). */
export function EventsRedirect() {
    const live = !!useDemoState().live;
    return <Navigate to={live ? '/account/events' : '/events'} replace />;
}

/**
 * 시작 경로. 데모 세션이 없으면 실제 계정 복구(#261)를 기다린 뒤, 계정이 있으면 공연 목록으로,
 * 없으면 로그인으로 보낸다. 앱 재시작 뒤 복구된 계정이 로그인 화면에 머무르지 않게 한다.
 */
export function EntryRedirect() {
    const { me, eventId } = useSession();
    if (me) return <Navigate to={eventId ? '/e/home' : '/events'} replace />;
    return (
        <AccountGuard>
            <Navigate to="/account/events" replace />
        </AccountGuard>
    );
}
