import { useLayoutEffect, useRef, type KeyboardEvent, type SyntheticEvent } from 'react';
import styles from './change.module.css';

/**
 * 직접 고칠 수 있는 글 칸: 점선 테두리와 연한 회색 바탕으로 편집 가능함을 보이고, 내용만큼 늘어나 안쪽 스크롤이 없다.
 * Ctrl+Z 같은 글자 되돌리기는 브라우저 기본 동작을 그대로 쓴다.
 */
export function DocText({
    value,
    onChange,
    label,
    inputRef,
    onKeyDown,
    onSelect,
}: {
    value: string;
    onChange: (next: string) => void;
    label: string;
    inputRef?: (el: HTMLTextAreaElement | null) => void;
    onKeyDown?: (e: KeyboardEvent<HTMLTextAreaElement>) => void;
    onSelect?: (e: SyntheticEvent<HTMLTextAreaElement>) => void;
}) {
    const ref = useRef<HTMLTextAreaElement | null>(null);
    useLayoutEffect(() => {
        const el = ref.current;
        if (!el) return;
        const fit = () => {
            // 테두리는 scrollHeight에 들어가지 않으므로 더해 준다
            el.style.height = 'auto';
            el.style.height = `${el.scrollHeight + el.offsetHeight - el.clientHeight}px`;
        };
        fit();
        // 폭이 바뀌면 줄바꿈도 달라지므로 높이를 다시 맞춘다
        const watch = new ResizeObserver(fit);
        watch.observe(el);
        return () => watch.disconnect();
    }, [value]);
    return (
        <textarea
            ref={(el) => {
                ref.current = el;
                inputRef?.(el);
            }}
            rows={1}
            className={styles.docText}
            aria-label={label}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={onKeyDown}
            onSelect={onSelect}
        />
    );
}
