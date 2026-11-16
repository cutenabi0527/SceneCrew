import { createContext, useContext } from 'react';
import type { Workflow } from '../features/plan-workflow/client';
import type { LiveSafetyLink } from './safety-link';

export interface LiveValue {
    /** 실서버 모드로 이 행사를 연다 */
    open: (eventId: string) => Promise<void>;
    /** 현재 행사를 서버에서 다시 읽는다 */
    reload: () => Promise<void>;
    status: string;
    /** 새로고침 뒤 고르던 공연을 서버에서 다시 읽는 중이다. 이동 보호는 이 동안 기다린다. */
    restoring: boolean;
    /** 실서버 총괄의 안전 지도·카메라 연결(#372). 데모·팀원·읽지 못했으면 null */
    safety: LiveSafetyLink | null;
    /** 실서버 공연의 서버 연결(총괄·팀원 모두). 내 연결(L01, #396)처럼 디자인 화면이 직접 부르는 API에 쓴다. 데모면 null */
    client: Workflow | null;
}

export const LiveContext = createContext<LiveValue | null>(null);

export function useLive() {
    const value = useContext(LiveContext);
    if (!value) throw new Error('LiveBridge 안에서만 사용할 수 있습니다.');
    return value;
}
