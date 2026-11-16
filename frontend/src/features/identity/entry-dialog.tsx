import { useEffect, useId, useRef, type ReactNode } from 'react';
import styles from './entry-dialog.module.css';

/**
 * 로그인 화면 위에 띄우는 팝업(회원가입·비밀번호 변경). 열리면 바로 모달로 보이고,
 * 닫기 버튼·Esc·바깥 영역 클릭으로 닫힌다. 닫힘은 onClose로 알려 부모가 상태를 지운다.
 */
export function EntryDialog({
    title,
    onClose,
    children,
}: {
    title: string;
    onClose: () => void;
    children: ReactNode;
}) {
    const ref = useRef<HTMLDialogElement>(null);
    const titleId = useId();
    useEffect(() => {
        const dialog = ref.current;
        if (dialog && !dialog.open) dialog.showModal();
    }, []);
    return (
        <dialog
            ref={ref}
            className={styles.dialog}
            aria-labelledby={titleId}
            onClose={onClose}
            onClick={(e) => {
                // 바깥(배경) 클릭은 dialog 자신이 대상이다.
                if (e.target === e.currentTarget) e.currentTarget.close();
            }}
        >
            <div className={styles.body}>
                <div className={styles.head}>
                    <h2 id={titleId} className="h2">
                        {title}
                    </h2>
                    <button
                        type="button"
                        className={styles.close}
                        aria-label="닫기"
                        onClick={() => ref.current?.close()}
                    >
                        ×
                    </button>
                </div>
                {children}
            </div>
        </dialog>
    );
}
