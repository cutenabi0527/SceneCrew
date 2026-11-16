import { RefreshNotAppliedError, type RefreshStore, type StoredRefresh } from './refresh-store';

// 시험 전용: Android 기기 Refresh 저장소(RefreshTokenSlots)의 동작을 흉내 낸다.
// - 정식 칸(main)과 로그인 임시 칸(pending). take는 정식 칸만 꺼내고 임시 칸을 버린다.
// - commit은 임시 칸을 정식 칸으로 한 번에 옮긴다(native의 AtomicFile 교체).
// - pause/crash로 특정 동작을 멈추거나, 그 지점에서 프로세스가 종료된 것처럼 영원히 끝나지 않게 한다.
//   같은 기기 저장소로 새 IdentitySession을 만들면 앱 재시작이다.

export type VaultOp = 'save' | 'stage' | 'commit' | 'discard' | 'clear';
/** before: 효과 없이 종료, after: 효과를 남기고 종료 */
export type CrashPoint = { op: VaultOp | 'firstWrite'; when: 'before' | 'after' };

export function deviceVault(initial: StoredRefresh | null = null) {
    let main = initial;
    let pending: StoredRefresh | null = null;
    let crash: CrashPoint | null = null;
    const pauses = new Map<VaultOp, Promise<void>>();
    const waiting = new Set<VaultOp>();
    const calls: VaultOp[] = [];
    const never = () => new Promise<never>(() => undefined);

    async function run(op: VaultOp, effect: () => void, write = false) {
        calls.push(op);
        const pause = pauses.get(op);
        if (pause) {
            pauses.delete(op);
            waiting.add(op);
            await pause;
            waiting.delete(op);
        }
        const hit = crash && (crash.op === op || (crash.op === 'firstWrite' && write));
        if (hit && crash!.when === 'before') return never();
        effect();
        if (hit) return never();
    }

    const store: RefreshStore = {
        take: async () => {
            pending = null;
            const value = main;
            main = null;
            return value;
        },
        save: (value) =>
            run(
                'save',
                () => {
                    main = { ...value };
                },
                true,
            ),
        stage: (value) =>
            run(
                'stage',
                () => {
                    pending = { ...value };
                },
                true,
            ),
        commit: () =>
            run('commit', () => {
                if (!pending) throw new RefreshNotAppliedError();
                main = pending;
                pending = null;
            }),
        discard: () =>
            run('discard', () => {
                pending = null;
            }),
        clear: () =>
            run('clear', () => {
                main = null;
                pending = null;
            }),
    };

    return {
        store,
        get main() {
            return main;
        },
        get pending() {
            return pending;
        },
        calls,
        /** 다음 op 한 번을 release 전까지 멈춘다. */
        pause(op: VaultOp) {
            let release!: () => void;
            pauses.set(
                op,
                new Promise<void>((resolve) => {
                    release = resolve;
                }),
            );
            return release;
        },
        /** op가 멈춰 있는 중인가 */
        isPaused: (op: VaultOp) => waiting.has(op),
        /** 이 지점에서 프로세스가 종료된 것으로 본다. */
        crashAt(point: CrashPoint) {
            crash = point;
        },
        /** 앱 재시작. 종료 지점 설정을 지운다(저장된 칸은 그대로). */
        restart() {
            crash = null;
            pauses.clear();
        },
    };
}
