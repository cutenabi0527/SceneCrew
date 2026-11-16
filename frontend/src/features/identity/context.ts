import { createContext, useContext } from 'react';
import type { IdentitySession } from './session';

export const IdentityContext = createContext<IdentitySession | null>(null);
export function useIdentity() {
    const session = useContext(IdentityContext);
    if (!session) throw new Error('인증 연결이 필요합니다.');
    return session;
}
