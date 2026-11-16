import { useId, useLayoutEffect, useRef, type ReactNode } from 'react';
import { useModalBackHandler } from '../navigation/leave-guard-context';
import { Icon } from './Icon';
import styles from './Dialog.module.css';

/**
 * 가운데 팝업창. 바깥을 누르거나 ✕·Esc·Android Back으로 닫힌다.
 * 화면 위 보조 작업(장비 등록, 복구 대안 확인 등)에 쓴다.
 */
export function Dialog({
    title,
    sub,
    wide = false,
    className,
    actions,
    onClose,
    children,
}: {
    title: ReactNode;
    sub?: ReactNode;
    wide?: boolean;
    /** 창 크기 등을 고정할 때 덧붙이는 class */
    className?: string;
    /** 제목 줄 오른쪽, 닫기(✕) 왼쪽에 두는 버튼 */
    actions?: ReactNode;
    onClose: () => void;
    children: ReactNode;
}) {
    useModalBackHandler(true, onClose);
    // 팝업 안에 팝업을 열어도 각자 자기 제목으로 읽히게 제목 id를 팝업마다 따로 둔다
    const titleId = useId();
    // 나중에 연 팝업이 앞에 오게, 열릴 때 이미 열린 팝업 수만큼 z-index를 올린다
    // (팝업 안에서 연 확인 창이 그 팝업에 가려지지 않게). 화면에 그리기 전에 정한다.
    const scrimRef = useRef<HTMLDivElement>(null);
    useLayoutEffect(() => {
        // 열릴 때 이미 열려 있던 팝업 수(자기 자신 제외). 화면 속 위치가 아니라 연 순서로 쌓는다
        const before = document.querySelectorAll('[data-dialog-scrim]').length - 1;
        if (scrimRef.current) scrimRef.current.style.zIndex = String(60 + Math.max(0, before));
    }, []);
    return (
        <div ref={scrimRef} className={styles.scrim} data-dialog-scrim="" onClick={onClose}>
            <div
                className={`${styles.dialog} ${wide ? styles.wide : ''} ${className ?? ''}`}
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(e) => e.key === 'Escape' && onClose()}
            >
                <header className={styles.head}>
                    <div className="stack s6">
                        <b id={titleId} className="h3">
                            {title}
                        </b>
                        {sub && <span className="xs muted">{sub}</span>}
                    </div>
                    {actions && <div className={styles.actions}>{actions}</div>}
                    <button
                        type="button"
                        className={styles.close}
                        aria-label="닫기"
                        autoFocus
                        onClick={onClose}
                    >
                        <Icon name="close" />
                    </button>
                </header>
                <div className={styles.body}>{children}</div>
            </div>
        </div>
    );
}
