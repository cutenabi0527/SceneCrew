import { useEffect, useReducer, useRef, type ReactNode } from 'react';
import { reducer, type Action } from './reducer';
import { initialState } from './seed';
import { DispatchContext, StateContext } from './store-context';
import type { DemoState } from './types';

const KEY = 'scenecrew-draft-v2';
/** 같은 브라우저의 다른 창(새 창으로 연 소통 창 등)과 데모 상태를 맞추는 채널 */
const SYNC = 'scenecrew-demo-sync';

/** 다른 창에서 받은 상태로 통째로 바꾼다(이 창 안에서만 쓰는 내부 액션) */
type SyncAction = { type: '__syncState'; state: DemoState };
const syncedReducer = (state: DemoState, action: Action | SyncAction): DemoState =>
    action.type === '__syncState' ? action.state : reducer(state, action);

function load(): DemoState {
    try {
        const raw = sessionStorage.getItem(KEY);
        if (raw) {
            const saved = JSON.parse(raw) as DemoState;
            if (saved?.data?.groups && Array.isArray(saved.events)) return saved;
        }
    } catch {
        // 저장소를 못 쓰는 환경(사생활 모드 등)은 초기 데이터로 시작한다.
    }
    return initialState();
}

/** 화면 초안용 데모 상태. 새로고침해도 같은 탭에서는 진행 상황을 유지한다. */
export function DemoStoreProvider({ children }: { children: ReactNode }) {
    const [state, dispatch] = useReducer(syncedReducer, undefined, load);
    useDemoSync(state, dispatch);
    useEffect(() => {
        try {
            // 실서버 모드의 화면 상태는 저장하지 않는다(새로 열면 서버에서 다시 읽는다). 이전 데모 저장분도
            // 지워, 새로고침 뒤 실서버 복구에 실패했을 때 지난 데모 화면이 실데이터처럼 보이지 않게 한다.
            if (state.live) sessionStorage.removeItem(KEY);
            else sessionStorage.setItem(KEY, JSON.stringify(state));
        } catch {
            // 저장 실패는 화면 동작에 영향이 없다.
        }
    }, [state]);

    // 데모: 서버의 최초 배정 Job을 흉내 낸다. 대기 → 작업 중 → (잠시 뒤) 결과. 화면은 상태만 그린다.
    const waiting = state.data.members.find((m) => m.assign === 'WAITING')?.id;
    const running = state.data.members.find((m) => m.assign === 'RUNNING')?.id;
    useEffect(() => {
        if (!waiting) return;
        const h = window.setTimeout(() => dispatch({ type: 'assignRun', memberId: waiting }), 400);
        return () => window.clearTimeout(h);
    }, [waiting]);
    useEffect(() => {
        if (!running) return;
        const h = window.setTimeout(
            () => dispatch({ type: 'assignResult', memberId: running }),
            1600,
        );
        return () => window.clearTimeout(h);
    }, [running]);

    return (
        <StateContext.Provider value={state}>
            <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
        </StateContext.Provider>
    );
}

/**
 * 데모 모드에서 같은 브라우저의 창끼리 상태를 맞춘다. 새 창은 sessionStorage 복사본으로 시작하고,
 * 이후 어느 창에서 바뀌든(예: 소통 창에서 보낸 메시지) 다른 창에 그대로 전한다.
 * 실서버 모드는 각 창이 서버에서 다시 읽으므로 전하지 않는다.
 */
function useDemoSync(state: DemoState, dispatch: (a: SyncAction) => void) {
    const channel = useRef<BroadcastChannel | null>(null);
    // 다른 창에서 받은 상태는 되돌려 보내지 않는다
    const received = useRef<DemoState | null>(null);
    const mounted = useRef(false);
    useEffect(() => {
        if (typeof BroadcastChannel === 'undefined') return;
        const ch = new BroadcastChannel(SYNC);
        channel.current = ch;
        ch.onmessage = (e: MessageEvent<DemoState>) => {
            const next = e.data;
            if (!next?.data || next.live) return;
            received.current = next;
            dispatch({ type: '__syncState', state: next });
        };
        return () => {
            ch.close();
            channel.current = null;
        };
    }, [dispatch]);
    useEffect(() => {
        // 처음 그릴 때는 이미 같은 복사본이므로 보내지 않는다
        if (!mounted.current) {
            mounted.current = true;
            return;
        }
        if (state.live || state === received.current) return;
        try {
            channel.current?.postMessage(state);
        } catch {
            // 보내지 못해도 이 창의 화면 동작에는 영향이 없다.
        }
    }, [state]);
}
