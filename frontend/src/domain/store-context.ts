import { createContext, useContext, type Dispatch } from 'react';
import type { Action } from './reducer';
import type { DemoState } from './types';

export const StateContext = createContext<DemoState | null>(null);
export const DispatchContext = createContext<Dispatch<Action> | null>(null);

export function useDemoState() {
    const s = useContext(StateContext);
    if (!s) throw new Error('DemoStoreProvider 안에서만 사용할 수 있습니다.');
    return s;
}

export function useDispatch() {
    const d = useContext(DispatchContext);
    if (!d) throw new Error('DemoStoreProvider 안에서만 사용할 수 있습니다.');
    return d;
}

let counter = 0;
/** 화면에서 새 항목 id를 만든다. reducer가 StrictMode에서 두 번 실행돼도 같은 id를 쓰도록 액션에 실어 보낸다. */
export const newId = (prefix: string) => `${prefix}${Date.now().toString(36)}${counter++}`;
