import { Link } from 'react-router';
import { StatusPanel } from '../../shared/ui/StatusPanel';

export function NotFound() {
    return (
        <main id="main" className="error-root" tabIndex={-1}>
            <StatusPanel
                title="페이지를 찾을 수 없어요"
                action={
                    <Link className="button" to="/">
                        홈으로 돌아가기
                    </Link>
                }
            >
                주소를 확인하거나 홈에서 다시 시작해주세요.
            </StatusPanel>
        </main>
    );
}
