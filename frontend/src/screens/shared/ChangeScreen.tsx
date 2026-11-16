import { useNavigate, useParams } from 'react-router';
import { ChangeReview } from '../../features/change/ChangeReview';
import styles from './shared.module.css';

/**
 * X01 변경 비교·승인(총괄 WEB 전용). 관리자 승인은 APP에 두지 않는다(IA02).
 * 같은 전후 행(useChangeReview)을 APP은 본인 변경 확인 카드(AppChangeAck)에서 카드로 그린다.
 */
export function ChangeScreen() {
    const { changeId } = useParams();
    const navigate = useNavigate();
    return (
        <div className={styles.narrow}>
            <ChangeReview id={changeId} onDone={() => navigate('/e/response')} />
        </div>
    );
}
