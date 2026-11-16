// 화면이 살아 있는 동안 복귀/재연결 시 현재 서버 상태를 조회한다. 명령은 재실행하지 않는다.
export function observe<T>(
    load: () => Promise<T>,
    accept: (value: T) => void,
    reject: (error: unknown) => void,
) {
    let active = true;
    let pending = false;
    const reload = () => {
        if (pending || document.visibilityState === 'hidden') return;
        pending = true;
        void load()
            .then((value) => {
                if (active) accept(value);
            })
            .catch((error: unknown) => {
                if (active) reject(error);
            })
            .finally(() => {
                pending = false;
            });
    };
    reload();
    window.addEventListener('focus', reload);
    window.addEventListener('online', reload);
    document.addEventListener('visibilitychange', reload);
    return () => {
        active = false;
        window.removeEventListener('focus', reload);
        window.removeEventListener('online', reload);
        document.removeEventListener('visibilitychange', reload);
    };
}
