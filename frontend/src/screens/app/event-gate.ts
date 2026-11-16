import { useMe, useSession } from '../../domain/hooks';
import { useDemoState } from '../../domain/store-context';
import type { EventInfo } from '../../domain/types';

/** 공연 상태 이름(APP). 운영 중·작성 중처럼 지금 상태로 읽히게 쓴다 */
export const APP_EVENT_STATE: Record<EventInfo['state'], string> = {
    DRAFT: '작성 중',
    RECRUITING: '모집 중',
    LIVE: '운영 중',
    ENDED: '종료',
};

/** 장소가 '미정'이면 무엇이 미정인지 붙여 쓴다 */
export const placeText = (e: EventInfo) => (e.place === '미정' ? '장소 미정' : e.place);

/**
 * 공연을 열 수 있는가(웹 공용 EventList와 같은 규칙). 실서버는 지금 연 공연 하나만 들고 있다(#362).
 * 데모는 e1 한 벌만 열 수 있고, 아직 참여 전인 초대받은 사람은 그룹 참여로 간다.
 */
export function useEventGate() {
    const { live } = useDemoState();
    const { eventId } = useSession();
    const { member, invitee } = useMe();
    return (e: EventInfo) => {
        const invited = !live && !member && !!invitee && e.id === 'e1';
        const open = e.state === 'LIVE' || e.state === 'ENDED';
        const enabled = live ? e.id === eventId : e.id === 'e1' && open && (!!member || invited);
        return { invited, enabled, current: e.id === eventId && !!member };
    };
}
