import { createContext, useContext, useEffect, useRef } from 'react';
import type { LeaveCopy, LeaveGuardController, ProtectionReason } from './leave-guard';

export const LeaveGuardContext = createContext<LeaveGuardController | null>(null);

export function useLeaveGuard(): LeaveGuardController {
    const controller = useContext(LeaveGuardContext);
    if (!controller) throw new Error('LeaveGuardProvider 안에서만 사용할 수 있습니다.');
    return controller;
}

// 미저장 입력/녹음이 있는 동안 active를 true로 둔다. false가 되거나 화면이 사라지면 해제된다.
export function useLeaveProtection(
    active: boolean,
    reason: ProtectionReason,
    copy?: Partial<LeaveCopy>,
) {
    const controller = useLeaveGuard();
    const { title, message, stay, leave } = copy ?? {};
    useEffect(() => {
        if (!active) return;
        return controller.registerProtection({ reason, copy: { title, message, stay, leave } });
    }, [controller, active, reason, title, message, stay, leave]);
}

// 모달/바텀시트가 열린 동안 open을 true로 둔다. Android Back은 이 모달을 먼저 닫는다.
export function useModalBackHandler(open: boolean, close: () => void) {
    const controller = useLeaveGuard();
    // 최신 close를 쓰되 close 함수가 바뀔 때마다 다시 등록하지 않는다(Back 순서가 뒤바뀌지 않게).
    const closeRef = useRef(close);
    useEffect(() => {
        closeRef.current = close;
    });
    useEffect(() => {
        if (!open) return;
        return controller.registerModal(() => closeRef.current());
    }, [controller, open]);
}
