import { useEffect, useRef, useSyncExternalStore, type ReactNode } from 'react';
import { LeaveGuardContext } from './leave-guard-context';
import type { LeaveGuardController } from './leave-guard';
import styles from './LeaveGuardHost.module.css';

// 컨트롤러를 내려주고, 이탈 확인 창과 브라우저 새로고침/탭 닫기 보호를 붙인다.
export function LeaveGuardHost({
    controller,
    children,
}: {
    controller: LeaveGuardController;
    children: ReactNode;
}) {
    const { protected: isProtected, prompt } = useSyncExternalStore(
        controller.subscribe,
        controller.getSnapshot,
    );
    const dialogRef = useRef<HTMLDialogElement>(null);
    const stayRef = useRef<HTMLButtonElement>(null);
    // 앱 상태로 창을 닫았을 때 뒤늦게 오는 close 이벤트를 구분한다.
    const closedByState = useRef(false);

    // hard reload·탭 닫기·외부 주소 이동은 앱이 막을 수 없고, 브라우저의 일반 확인만 띄울 수 있다
    // (문구 변경 불가, 사용자 상호작용 이후에만 표시). 보호가 있는 동안에만 등록한다.
    useEffect(() => {
        if (!isProtected) return;
        const warn = (event: BeforeUnloadEvent) => {
            event.preventDefault();
            event.returnValue = '';
        };
        window.addEventListener('beforeunload', warn);
        return () => window.removeEventListener('beforeunload', warn);
    }, [isProtected]);

    // 네이티브 dialog: 배경을 inert로 만들고 닫을 때 원래 위치로 초점을 돌려준다.
    // 안전한 선택(계속하기)에 초점을 둔다. 열리기 전에 실행되는 autoFocus는 쓸 수 없다.
    useEffect(() => {
        const dialog = dialogRef.current;
        if (!dialog) return;
        if (prompt && !dialog.open) {
            dialog.showModal();
            stayRef.current?.focus();
        }
        if (!prompt && dialog.open) {
            closedByState.current = true;
            dialog.close();
        }
    }, [prompt]);

    return (
        <LeaveGuardContext.Provider value={controller}>
            {children}
            <dialog
                ref={dialogRef}
                className={styles.dialog}
                role="alertdialog"
                aria-labelledby="leave-guard-title"
                aria-describedby="leave-guard-message"
                // Esc는 "계속 작성하기"와 같다. 브라우저가 먼저 닫지 못하게 막고 상태로 닫는다.
                onCancel={(event) => {
                    event.preventDefault();
                    controller.stay();
                }}
                // 브라우저가 cancel 없이 닫을 수도 있다(Esc 연타 등). 앱 상태를 닫힘과 맞춘다.
                // close 이벤트는 닫은 뒤 늦게 도착한다. 앱 상태로 닫은 창의 이벤트는 무시한다.
                // 그사이 Back을 다시 눌러 새 확인이 열렸다면(Back 연타) 그 확인을 취소하면 안 된다.
                onClose={() => {
                    if (closedByState.current) {
                        closedByState.current = false;
                        return;
                    }
                    controller.stay();
                }}
            >
                {prompt && (
                    <>
                        <h2 id="leave-guard-title">{prompt.copy.title}</h2>
                        <p id="leave-guard-message">{prompt.copy.message}</p>
                        <div className={styles.actions}>
                            <button type="button" ref={stayRef} onClick={() => controller.stay()}>
                                {prompt.copy.stay}
                            </button>
                            <button
                                type="button"
                                className={styles.leave}
                                onClick={() => controller.leave()}
                            >
                                {prompt.copy.leave}
                            </button>
                        </div>
                    </>
                )}
            </dialog>
        </LeaveGuardContext.Provider>
    );
}
