import { createContext, useContext } from 'react';
import type { PushRegistrar } from './registrar';

/** Android 앱에서만 값이 있다. WEB은 null이다. */
export const PushContext = createContext<PushRegistrar | null>(null);
export const usePush = () => useContext(PushContext);
