import type { BaseHistory, HistoryLocation, HistoryUpdate } from './guarded-history';

// 테스트 전용. 브라우저 이력의 핵심 성질만 흉내 낸다.
//  - go(n)은 즉시 이동하지 않고 작업 큐에 들어가며, 실행 시점의 현재 위치 기준으로 이동한다.
//  - 이동이 일어나면 history.state.idx가 바뀐 뒤 popstate 상당 이벤트가 나간다(범위를 벗어나면 무시).
//  - 사용자의 Back/Forward 버튼도 같은 큐를 쓴다.
// 실제 브라우저의 동작은 Playwright 검사로 확인한다. 이 가짜가 실제와 같다는 보장은 아니다.
export class FakeBrowser {
    entries: { path: string; idx: number | null }[] = [{ path: '/', idx: 0 }];
    index = 0;
    readonly window: { history: { readonly state: unknown } };
    readonly history: BaseHistory;

    private queue: (() => void)[] = [];
    private traverse: (delta: number) => void;

    constructor() {
        let listener: ((update: HistoryUpdate) => void) | null = null;
        let lastIdx: number | null = 0;
        let lastAction = 'POP';
        const current = () => this.entries[this.index];

        const location = (): HistoryLocation => {
            const [pathAndSearch, hash = ''] = current().path.split('#');
            const [pathname, search = ''] = pathAndSearch.split('?');
            return { pathname, search: search ? `?${search}` : '', hash: hash ? `#${hash}` : '' };
        };
        const parse = (to: unknown): HistoryLocation => {
            const text = typeof to === 'string' ? to : '';
            const url = new URL(text || location().pathname, 'http://localhost');
            return { pathname: url.pathname, search: url.search, hash: url.hash };
        };
        const emit = (action: string, delta: number | null) => {
            lastAction = action;
            listener?.({ action, location: location(), delta });
        };

        this.traverse = (delta) => {
            const next = this.index + delta;
            if (next < 0 || next >= this.entries.length) return;
            this.index = next;
            const idx = current().idx;
            const relative = idx === null || lastIdx === null ? null : idx - lastIdx;
            lastIdx = idx;
            emit('POP', relative);
        };

        this.window = {
            history: {
                get state() {
                    const idx = current().idx;
                    return idx === null ? null : { idx };
                },
            },
        };
        this.history = {
            get action() {
                return lastAction;
            },
            get location() {
                return location();
            },
            createHref: (to) => String(to),
            createURL: (to) => new URL(String(to), 'http://localhost'),
            encodeLocation: parse,
            push: (to) => {
                this.entries.splice(this.index + 1);
                this.entries.push({ path: String(to), idx: this.index + 1 });
                this.index += 1;
                lastIdx = current().idx;
                emit('PUSH', 1);
            },
            replace: (to) => {
                this.entries[this.index] = { path: String(to), idx: current().idx };
                emit('REPLACE', 0);
            },
            go: (delta) => {
                this.queue.push(() => this.traverse(delta));
            },
            listen: (next) => {
                if (listener) throw new Error('A history only accepts one active listener');
                listener = next;
                return () => {
                    listener = null;
                };
            },
        };
    }

    get path() {
        return this.entries[this.index].path;
    }

    // 사용자가 브라우저 Back/Forward 버튼을 누른다(같은 큐를 쓴다).
    userGo(delta: number) {
        this.queue.push(() => this.traverse(delta));
    }

    // 큐에 쌓인 이동을 모두 처리한다(이동이 새 이동을 만들면 그것도 처리).
    async settle() {
        for (let guard = 0; guard < 100 && this.queue.length > 0; guard++) {
            this.queue.shift()!();
            await Promise.resolve();
        }
        if (this.queue.length > 0) throw new Error('이동이 끝나지 않습니다.');
    }

    // idx가 없는 외부 항목을 현재 위치 뒤에 끼워 넣는다.
    pushForeign(path: string) {
        this.entries.splice(this.index + 1);
        this.entries.push({ path, idx: null });
        this.index += 1;
    }
}
