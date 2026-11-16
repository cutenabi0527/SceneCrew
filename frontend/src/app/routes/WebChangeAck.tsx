import { Navigate } from 'react-router';
import { useDemoState } from '../../domain/store-context';
import { AppChangeAck } from '../../screens/app/AppChangeAck';

/** WEB 변경 확인 주소. 실서버는 M08 화면을 그대로 쓰고, 데모는 알림함으로 돌린다(#373). */
export function WebChangeAck() {
    const live = !!useDemoState().live;
    return live ? <AppChangeAck /> : <Navigate to="/e/notices" replace />;
}
