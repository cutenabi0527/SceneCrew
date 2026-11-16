import { Route } from 'react-router';
import { PlanWorkflowRoute, TasksWorkflowRoute } from '../plan-workflow/Routes';
import { LiveEquipmentRoute } from '../equipment/LiveEquipment';
import { LiveClosingRoute } from '../event-close/LiveClosing';
import { LiveChangesRoute } from '../response/LiveChanges';
import { LiveResponseRoute } from '../response/LiveResponse';
import { LiveChatRoute } from '../live-chat/LiveChat';
import { LiveMembersRoute } from '../members/LiveMembers';
import { LiveNotifications } from '../notifications/LiveNotifications';
import { LiveSafetyRoute } from '../safety-map/LiveSafety';
import {
    AccountEvents,
    AccountGuard,
    AccountLogin,
    DeactivatedScreen,
    DeletionStatusScreen,
    InviteScreen,
    ParticipationScreen,
    ProfileScreen,
    RecoveryScreen,
    RegisterScreen,
} from './screens';
import { DownloadScreen } from '../distribution/DownloadScreen';
import { EventManageRoute } from '../event-admin/EventManage';
import { AppReturnScreen, OAuthReturnRoute } from './social';
import { OAUTH_RETURN_PATH } from './social-target';
import { ANDROID_OAUTH_RETURN_PATH } from './android-oauth';
import { platform } from '../../platform/runtime';
import { LiveEnter } from '../../live/LiveBridge';

export function identityRoutes() {
    return (
        <>
            <Route path="/login" element={<AccountLogin />} />
            <Route path="/download" element={<DownloadScreen />} />
            <Route path={OAUTH_RETURN_PATH} element={<OAuthReturnRoute />} />
            {/* Android 앱 복귀(#388). 앱에서는 교환하고, 브라우저에 열리면 앱으로 돌아가는 버튼만 보인다 */}
            <Route
                path={ANDROID_OAUTH_RETURN_PATH}
                element={platform === 'android' ? <OAuthReturnRoute /> : <AppReturnScreen />}
            />
            <Route path="/account/register" element={<RegisterScreen />} />
            <Route path="/account/recovery" element={<RecoveryScreen />} />
            <Route path="/account/deactivated" element={<DeactivatedScreen />} />
            <Route path="/account/deletion-status" element={<DeletionStatusScreen />} />
            <Route
                path="/account/events"
                element={
                    <AccountGuard>
                        <AccountEvents />
                    </AccountGuard>
                }
            />
            {/* 실제 계정으로 고른 공연을 운영 화면(디자인 화면)으로 연다(#362) */}
            <Route
                path="/live/events/:eventId"
                element={
                    <AccountGuard>
                        <LiveEnter />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId"
                element={
                    <AccountGuard>
                        <ParticipationScreen />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/manage"
                element={
                    <AccountGuard>
                        <EventManageRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/plan"
                element={
                    <AccountGuard>
                        <PlanWorkflowRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/tasks"
                element={
                    <AccountGuard>
                        <TasksWorkflowRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/equipment"
                element={
                    <AccountGuard>
                        <LiveEquipmentRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/issues"
                element={
                    <AccountGuard>
                        <LiveResponseRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/changes"
                element={
                    <AccountGuard>
                        <LiveChangesRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/safety"
                element={
                    <AccountGuard>
                        <LiveSafetyRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/members"
                element={
                    <AccountGuard>
                        <LiveMembersRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/chat"
                element={
                    <AccountGuard>
                        <LiveChatRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/events/:eventId/end"
                element={
                    <AccountGuard>
                        <LiveClosingRoute />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/notifications"
                element={
                    <AccountGuard>
                        <LiveNotifications />
                    </AccountGuard>
                }
            />
            <Route
                path="/account/profile"
                element={
                    <AccountGuard>
                        <ProfileScreen />
                    </AccountGuard>
                }
            />
            <Route path="/invites/:token" element={<InviteScreen />} />
        </>
    );
}
