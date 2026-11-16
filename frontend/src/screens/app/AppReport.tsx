import { useNavigate, useSearchParams } from 'react-router';
import { AppReportForm } from '../../features/issue/AppReportForm';

/**
 * 빠른 보고(APP): WEB은 ReportForm을 모달로 띄우지만,
 * APP은 현장에서 한 손으로 쓰도록 전체 화면 페이지(AppReportForm)로 열고 머리줄 X·Back으로 닫는다.
 * 열자마자 음성 입력 화면부터 띄운다(음성 먼저).
 */
export function AppReport() {
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const kind = params.get('kind');
    return (
        <AppReportForm
            taskId={params.get('task') ?? undefined}
            issueId={params.get('issue') ?? undefined}
            kind={kind === 'RESOLUTION' || kind === 'FOLLOWUP' ? kind : undefined}
            onDone={() => navigate('/e/today?tab=reports', { replace: true })}
        />
    );
}
