import type { ReactNode } from 'react';
import { ScopeContext } from './scope-context';
import type { ScopeManager } from './scope-manager';

export function ScopeProvider({
    manager,
    children,
}: {
    manager: ScopeManager;
    children: ReactNode;
}) {
    return <ScopeContext.Provider value={manager}>{children}</ScopeContext.Provider>;
}
