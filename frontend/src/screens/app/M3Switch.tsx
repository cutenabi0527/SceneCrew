import styles from './switch.module.css';

/**
 * 안드로이드 Material 3 스위치(APP). 켜짐은 초록 바탕 + 체크 손잡이, 꺼짐은 회색 테두리 + 작은 손잡이.
 * 글자 이름은 바깥 줄의 글자를 aria-labelledby로 잇는다.
 */
export function M3Switch({
    checked,
    onChange,
    labelledBy,
    describedBy,
    disabled,
}: {
    checked: boolean;
    onChange: (next: boolean) => void;
    labelledBy: string;
    describedBy?: string;
    disabled?: boolean;
}) {
    return (
        <button
            type="button"
            role="switch"
            className={styles.switch}
            aria-checked={checked}
            aria-labelledby={labelledBy}
            aria-describedby={describedBy}
            disabled={disabled}
            onClick={() => onChange(!checked)}
        >
            <span className={styles.thumb}>
                {checked && (
                    <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
                        <path
                            d="m5 12 5 5 9-10"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="3"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    </svg>
                )}
            </span>
        </button>
    );
}
