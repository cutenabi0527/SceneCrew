import { Link, useParams } from 'react-router';
import { useIdentity } from '../../features/identity/context';
import { PlanJobPanel } from '../../features/plan-job/PlanJobPanel';
import { asUuid } from '../../shared/api/contract';
import { EntryFrame } from '../entry/EntryFrame';

// 생성 접수증/재열기 링크에서 진입한다. 데모 계정으로 실제 API 범위를 만들지 않는다.
// 로그인한 세션의 API를 그대로 쓴다. WEB은 쿠키, Android는 토큰이 붙는다(#268).
export function PlanJobScreen() {
    const { eventId, jobId } = useParams();
    const session = useIdentity();
    let valid = true;
    try {
        asUuid(eventId, 'eventId');
        asUuid(jobId, 'jobId');
    } catch {
        valid = false;
    }
    const scope = valid && eventId ? session.scopes.participation(eventId) : null;
    return (
        <EntryFrame title="공연 계획 생성">
            {!valid ? (
                <p role="alert">계획 생성 주소를 확인해주세요.</p>
            ) : scope && jobId ? (
                <PlanJobPanel api={session.api} scope={scope} jobId={jobId} />
            ) : (
                <p role="alert">로그인과 행사 참여 정보를 확인한 뒤 생성 결과를 다시 열어주세요.</p>
            )}
            {/* 질문 보완·초안 검토·공개는 운영 화면의 계획 입력(W01-DRAFT)에서 한다 */}
            {valid && scope && (
                <Link className="btn" to={`/live/events/${eventId}?next=plan-draft`}>
                    계획 입력 화면에서 이어 하기
                </Link>
            )}
            <Link className="btn ghost" to="/account/events">
                내 공연 목록
            </Link>
        </EntryFrame>
    );
}
