import { useId } from 'react';
export function Field({
    label,
    value,
    change,
    multiline = false,
    placeholder,
    hideLabel = false,
}: {
    label: string;
    value: string;
    change: (value: string) => void;
    multiline?: boolean;
    /** 칸 안에 흐리게 보이는 예시 */
    placeholder?: string;
    /** 이름표를 숨긴다(읽기 도구에는 남는다) */
    hideLabel?: boolean;
}) {
    const id = useId();
    return (
        <label className="field" htmlFor={id}>
            <span className={hideLabel ? 'sr-only' : undefined}>{label}</span>
            {multiline ? (
                <textarea
                    id={id}
                    aria-label={label}
                    placeholder={placeholder}
                    value={value}
                    onChange={(e) => change(e.target.value)}
                />
            ) : (
                <input
                    id={id}
                    aria-label={label}
                    placeholder={placeholder}
                    value={value}
                    onChange={(e) => change(e.target.value)}
                />
            )}
        </label>
    );
}
