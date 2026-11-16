import { Navigate, Route, Routes } from 'react-router';
import { WebShell } from '../../layouts/web/WebShell';
import { EventsScreen } from '../../screens/entry/EventsScreen';
import { JoinScreen } from '../../screens/entry/JoinScreen';
import { LoginScreen } from '../../screens/entry/LoginScreen';
import { ArchiveScreen } from '../../screens/shared/ArchiveScreen';
import { ChangeScreen } from '../../screens/shared/ChangeScreen';
import { NoticesScreen } from '../../screens/shared/NoticesScreen';
import { WebHome } from '../../screens/web/WebHome';
import { WebPlan } from '../../screens/web/WebPlan';
import { WebPlanDraft } from '../../screens/web/WebPlanDraft';
import { PlanJobScreen } from '../../screens/web/PlanJobScreen';
import { WebResponse } from '../../screens/web/WebResponse';
import { WebCameraWallWindow, WebCameraWindow } from '../../screens/web/WebCameraWindow';
import { WebChat } from '../../screens/web/WebChat';
import { WebChatWindow } from '../../screens/web/WebChatWindow';
import { WebEquipment } from '../../screens/web/WebEquipment';
import { WebLinks } from '../../screens/web/WebLinks';
import { WebSafety } from '../../screens/web/WebSafety';
import { NotFound } from './NotFound';
import { WebChangeAck } from './WebChangeAck';
import { EntryRedirect, EventsRedirect, RequireEvent, RequireJoin, RequireLogin } from './guards';
import { identityRoutes } from '../../features/identity/routes';

/**
 * WEB 라우트 표(v9 IA). 주소 체계는 APP과 같고(딥링크 공유), 화면 선택만 다르다.
 *  [공유]  로그인(C01) · 행사 목록(C02) · 그룹 참여(C03) · 알림함 · 행사 생성(W00, 행사 목록 안)
 *  [총괄 메뉴] W01 공연 계획(개요·큐시트·적용된 변경 탭) · 운영(W02 현황·W06 운영진 탭) · W03 장비 운영 · W04 보고·대응(분할)
 *             · W05 안전 · W07 소통(브라우저 새 창, 방마다 한 창)
 *  [보조 진입] X01 변경 비교·승인 · A01 종료 요약(총괄)
 *  APP 전용 주소(내 업무·빠른 보고·변경 확인 카드·설정)는 WEB에서 대응 화면으로 돌린다.
 */
export function WebRoutes() {
    return (
        <Routes>
            <Route path="/" element={<EntryRedirect />} />
            {identityRoutes()}
            <Route path="/demo/login" element={<LoginScreen />} />
            {/* W05 카메라 새 창(메뉴 없음, 총괄 전용): 모든 카메라 분할 화면 / 한 대 */}
            <Route
                path="/cameras"
                element={
                    <RequireEvent>
                        <WebCameraWallWindow />
                    </RequireEvent>
                }
            />
            <Route
                path="/camera/:cameraId"
                element={
                    <RequireEvent>
                        <WebCameraWindow />
                    </RequireEvent>
                }
            />
            {/* W07 소통 새 창(메뉴 없음): 채널 목록 / 방마다 한 창 */}
            <Route
                path="/chat-window"
                element={
                    <RequireEvent>
                        <WebChatWindow />
                    </RequireEvent>
                }
            />
            <Route
                path="/chat-window/:roomId"
                element={
                    <RequireEvent>
                        <WebChatWindow />
                    </RequireEvent>
                }
            />
            <Route path="/events/:eventId/plan-jobs/:jobId" element={<PlanJobScreen />} />
            <Route
                path="/events"
                element={
                    <RequireLogin>
                        <EventsScreen />
                    </RequireLogin>
                }
            />
            <Route
                path="/join"
                element={
                    <RequireJoin>
                        <JoinScreen />
                    </RequireJoin>
                }
            />
            <Route
                path="/e"
                element={
                    <RequireEvent>
                        <WebShell />
                    </RequireEvent>
                }
            >
                <Route index element={<Navigate to="home" replace />} />
                <Route path="home" element={<WebHome />} />
                <Route path="plan" element={<WebPlan />} />
                {/* W01-DRAFT 계획 입력·초안·공개(실제 공연 총괄, #362) */}
                <Route path="plan/draft" element={<WebPlanDraft />} />
                <Route path="response" element={<WebResponse />} />
                <Route path="response/:issueId" element={<WebResponse />} />
                {/* 업무 상세 주소는 공연 계획 큐시트 탭에서 그 업무를 고른 채로 연다 */}
                <Route path="tasks" element={<WebPlan />} />
                <Route path="tasks/:taskId" element={<WebPlan />} />
                <Route path="equipment" element={<WebEquipment />} />
                <Route path="safety" element={<WebSafety />} />
                {/* L01 내 연결(#396): 노트북 카메라 웹 연결. 실서버 공연에서만 메뉴에 보인다 */}
                <Route path="links" element={<WebLinks />} />
                {/* 운영진 탭은 없앴다. 예전 주소는 운영 구성도로 보낸다 */}
                <Route path="crew" element={<Navigate to="/e/home" replace />} />
                <Route path="chat" element={<WebChat />} />
                <Route path="chat/:roomId" element={<WebChat />} />
                <Route path="changes/:changeId" element={<ChangeScreen />} />
                <Route path="notices" element={<NoticesScreen />} />
                <Route path="archive" element={<ArchiveScreen />} />
                {/* APP 전용 흐름의 주소로 들어오면 WEB의 대응 화면으로 */}
                <Route path="today" element={<Navigate to="/e/home" replace />} />
                <Route path="report" element={<Navigate to="/e/response" replace />} />
                {/* APP 설정 탭: WEB은 오른쪽 위 프로필 메뉴에서 같은 내 정보를 고친다 */}
                <Route path="settings" element={<Navigate to="/e/home" replace />} />
                <Route path="events" element={<EventsRedirect />} />
                <Route path="issues" element={<Navigate to="/e/response" replace />} />
                {/* 변경 확인: 데모 WEB은 알림함에서 바로 확인하고, 실서버는 이 화면에서 명시 확인한다(#373) */}
                <Route path="ack/:noticeId" element={<WebChangeAck />} />
            </Route>
            <Route path="*" element={<NotFound />} />
        </Routes>
    );
}
