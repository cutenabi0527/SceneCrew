import { consentLabels, consentLimits, consentRequired } from './consent';
import type { Consents } from './contract';
import styles from './consent-inputs.module.css';

// 가입 동의 입력. 이메일 가입(RegisterScreen)과 소셜 가입(#379)이 함께 쓴다.
// 동의마다 필수/선택을 표시하고, 선택이지만 동의하지 않으면 일부 기능을 쓸 수 없는 항목은 그 내용을 함께 보인다.

export function ConsentInputs({
    value,
    change,
}: {
    value: Consents;
    change: (value: Consents) => void;
}) {
    return (
        <fieldset className="stack">
            <legend>동의 선택</legend>
            {(Object.keys(value) as (keyof Consents)[]).map((key) => {
                const limit = consentLimits[key];
                const noteId = `consent-limit-${key}`;
                return (
                    <div key={key} className={styles.item}>
                        <label className="check">
                            <input
                                type="checkbox"
                                checked={value[key]}
                                aria-describedby={limit ? noteId : undefined}
                                onChange={(e) => change({ ...value, [key]: e.target.checked })}
                            />{' '}
                            {consentLabels[key]}
                            <span
                                className={`${styles.tag} ${consentRequired[key] ? styles.required : ''}`}
                            >
                                {consentRequired[key] ? '필수' : '선택'}
                            </span>
                        </label>
                        {limit && (
                            <p id={noteId} className={styles.limit}>
                                동의하지 않으면 {limit} 이용이 제한될 수 있습니다.
                            </p>
                        )}
                    </div>
                );
            })}
        </fieldset>
    );
}
