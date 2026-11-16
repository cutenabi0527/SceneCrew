import { createContext, useContext } from 'react';
import type { UiVariant } from '../platform/ui-variant';

/**
 * 같은 버튼이 레이아웃마다 다르게 열리는 지점.
 * 예) "빠른 보고": WEB은 현재 화면 위 모달, APP은 전체 화면 보고 페이지로 이동.
 * 화면 컴포넌트는 이 훅만 호출하고 어디서 열리는지는 각 셸(WebShell/AppShell)이 정한다.
 */
export type ShellActions = {
    variant: UiVariant;
    openReport: (o?: {
        taskId?: string;
        issueId?: string;
        kind?: 'FOLLOWUP' | 'RESOLUTION';
    }) => void;
    openChat: (roomId?: string) => void;
    openIssue: (id: string) => void;
    openTask: (id: string) => void;
    /**
     * 업무 상세를 열 수 있는가. APP은 본인 업무 상세(M05)가 있어 항상 열린다.
     * WEB의 업무 상세는 업무 조정(총괄 전용)이라 총괄만 열 수 있고, 그 밖에는 업무를 누를 수 없게 한다.
     */
    canOpenTask: boolean;
    openChange: (id: string) => void;
};

export const ShellActionsContext = createContext<ShellActions | null>(null);

export function useShell() {
    const a = useContext(ShellActionsContext);
    if (!a) throw new Error('셸 안에서만 사용할 수 있습니다.');
    return a;
}
