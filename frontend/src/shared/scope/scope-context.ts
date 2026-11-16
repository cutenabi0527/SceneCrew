import { createContext, useContext } from 'react';
import type { ScopeManager } from './scope-manager';

export const ScopeContext = createContext<ScopeManager | null>(null);

export function useScopes(): ScopeManager {
    const manager = useContext(ScopeContext);
    if (!manager) throw new Error('ScopeProvider 안에서만 사용할 수 있습니다.');
    return manager;
}
