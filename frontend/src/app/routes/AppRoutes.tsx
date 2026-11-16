import { Navigate, Route, Routes } from 'react-router';
import { AppShell } from '../../layouts/app/AppShell';
import { AppChangeAck } from '../../screens/app/AppChangeAck';
import { AppChat, AppRoom } from '../../screens/app/AppChat';
import { AppEvent } from '../../screens/app/AppEvent';
import { AppReport } from '../../screens/app/AppReport';
import { AppIssues } from '../../screens/app/AppIssues';
import { AppIssue } from '../../screens/app/AppResponse';
import { AppTask } from '../../screens/app/AppTasks';
import { AppToday } from '../../screens/app/AppToday';
import { AppEvents, AppJoin } from '../../screens/app/AppEntry';
import { AppMe, AppMeEdit } from '../../screens/app/AppSettings';
import { LoginScreen } from '../../screens/entry/LoginScreen';
import { AppNotices } from '../../screens/app/AppNotices';
import { NotFound } from './NotFound';
import { EntryRedirect, RequireEvent, RequireJoin, RequireLogin } from './guards';
import { identityRoutes } from '../../features/identity/routes';

/**
 * APP 라우트 표(하단 탭: 내 업무·계획 + 가운데 보고 + 이슈·소통. 행사 목록은 행사 이름 펼침, 설정은 상단 프로필 팝업). 팀원 현장 기능 우선.
 *  행사에 들어오면(로그인 → 행사 선택·참여 뒤) 항상 내 업무 탭에서 시작한다. 공용 화면이 보내는 /e/home도 내 업무로 돌린다.
 *  [공유]  로그인 · 행사 목록 · 그룹 참여 · 알림함
 *  [APP 배치] M02 공연 계획(=WEB W01) · 진행 보고 탭/M07 보고 상세(=WEB W04 보고·대응) · M05 업무 상세
 *  [APP 전용] M01 내 업무 · M03 소통 탭/방 · M06 빠른 보고 페이지 · M08 변경 확인 카드(푸시 진입) · 설정
 *  [APP에 없음] X01 변경 비교·승인, A01 종료 요약(총괄 WEB 전용)
 */
export function AppRoutes() {
    return (
        <Routes>
            <Route path="/" element={<EntryRedirect />} />
            {identityRoutes()}
            <Route path="/demo/login" element={<LoginScreen />} />
            <Route
                path="/events"
                element={
                    <RequireLogin>
                        <AppEvents />
                    </RequireLogin>
                }
            />
            <Route
                path="/join"
                element={
                    <RequireJoin>
                        <AppJoin />
                    </RequireJoin>
                }
            />
            <Route
                path="/e"
                element={
                    <RequireEvent>
                        <AppShell />
                    </RequireEvent>
                }
            >
                <Route index element={<Navigate to="today" replace />} />
                <Route path="home" element={<Navigate to="/e/today" replace />} />
                <Route path="today" element={<AppToday />} />
                <Route path="response" element={<Navigate to="/e/today?tab=reports" replace />} />
                <Route path="response/:issueId" element={<AppIssue />} />
                <Route path="plan" element={<AppEvent />} />
                <Route path="issues" element={<AppIssues />} />
                {/* 행사 목록은 왼쪽 위 행사 이름의 펼침 목록이 되었다 */}
                <Route path="events" element={<Navigate to="/e/issues" replace />} />
                {/* 설정은 머리줄 아바타의 내 정보 화면이 되었다. 옛 주소는 내 정보로 */}
                <Route path="settings" element={<Navigate to="/e/me" replace />} />
                <Route path="me" element={<AppMe />} />
                <Route path="me/edit" element={<AppMeEdit />} />
                <Route path="chat" element={<AppChat />} />
                <Route path="chat/:roomId" element={<AppRoom />} />
                <Route path="tasks" element={<Navigate to="/e/today" replace />} />
                <Route path="tasks/:taskId" element={<AppTask />} />
                <Route path="changes/:changeId" element={<Navigate to="/e/notices" replace />} />
                <Route path="notices" element={<AppNotices />} />
                <Route path="ack/:noticeId" element={<AppChangeAck />} />
                <Route path="report" element={<AppReport />} />
                <Route path="archive" element={<Navigate to="/e/plan" replace />} />
            </Route>
            <Route path="*" element={<NotFound />} />
        </Routes>
    );
}
