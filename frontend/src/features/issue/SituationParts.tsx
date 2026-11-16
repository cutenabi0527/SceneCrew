import { useRef, useState } from 'react';
import { useData } from '../../domain/hooks';
import { SITUATIONS, SITUATION_LABEL, nameOf } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Issue, SituationType } from '../../domain/types';
import { Icon } from '../../shared/ui/Icon';

/** 대표 상황(최대1개, 없으면 미분류)과 연관 상황, 출처(모델 추천/총괄 확인)를 한 줄로 보인다 */
export function SituationLine({ issue: i }: { issue: Issue }) {
    const d = useData();
    const s = i.situation;
    return (
        <ul className="xs" style={{ margin: '2px 0 0', paddingLeft: 16 }}>
            <li>
                대표: {s?.primary ? SITUATION_LABEL[s.primary] : '미분류'}
                {s && s.related.length > 0 && (
                    <> / 연관: {s.related.map((r) => SITUATION_LABEL[r]).join(', ')}</>
                )}
            </li>
            <li>
                출처: {s?.source ?? '분석 없음'}
                {s?.source === '총괄 확인' && ` / ${nameOf(d, s.by)} ${s.at}`}
            </li>
        </ul>
    );
}

/**
 * 총괄의 상황 유형 확인/정정(W04). 원문과 이전 모델 결과는 지우지 않고 유형만 새로 기록한다.
 * 대표 유형 때문에 다른 필요한 대응이 빠지지 않도록 연관 유형을 여러 개 고를 수 있다.
 */
/**
 * 상황 유형 확인/정정. iconClassName을 주면 글자 단추 대신 태그 아이콘 단추로 연다
 * (이름은 툴팁·스크린리더로 읽힌다).
 */
export function SituationEditor({
    issue: i,
    iconClassName,
}: {
    issue: Issue;
    iconClassName?: string;
}) {
    const dispatch = useDispatch();
    const ref = useRef<HTMLDialogElement>(null);
    const [primary, setPrimary] = useState<SituationType | ''>(i.situation?.primary ?? '');
    const [related, setRelated] = useState<SituationType[]>(i.situation?.related ?? []);
    const [reason, setReason] = useState('');
    // 실서버는 정정 사유가 필수다(V9-D48). 데모는 사유를 기록하지 않는다.
    const live = !!useDemoState().live;
    return (
        <>
            {iconClassName ? (
                <button
                    type="button"
                    className={iconClassName}
                    data-kind="situation"
                    aria-label="상황 유형 확인/정정"
                    title="상황 유형 확인/정정"
                    onClick={() => ref.current?.showModal()}
                >
                    <Icon name="tag" />
                </button>
            ) : (
                <button type="button" className="btn" onClick={() => ref.current?.showModal()}>
                    상황 유형 확인/정정
                </button>
            )}
            <dialog ref={ref} aria-label="상황 유형 확인" style={{ maxWidth: 420 }}>
                <form
                    method="dialog"
                    className="stack"
                    onSubmit={() =>
                        dispatch({
                            type: 'setSituation',
                            issueId: i.id,
                            primary: primary || null,
                            related,
                            reason: reason.trim() || undefined,
                        })
                    }
                >
                    <b>상황 유형 확인</b>
                    <label className="field">
                        <span className="lbl">대표 상황</span>
                        <select
                            value={primary}
                            onChange={(e) => setPrimary(e.target.value as SituationType | '')}
                        >
                            <option value="">미분류(추가 확인)</option>
                            {SITUATIONS.map((t) => (
                                <option key={t} value={t}>
                                    {SITUATION_LABEL[t]}
                                </option>
                            ))}
                        </select>
                    </label>
                    <fieldset className="stack s6" style={{ border: 0, padding: 0, margin: 0 }}>
                        <legend className="lbl">연관 상황</legend>
                        {SITUATIONS.filter((t) => t !== primary).map((t) => (
                            <label key={t} className="check">
                                <input
                                    type="checkbox"
                                    checked={related.includes(t)}
                                    onChange={(e) =>
                                        setRelated((xs) =>
                                            e.target.checked
                                                ? [...xs, t]
                                                : xs.filter((x) => x !== t),
                                        )
                                    }
                                />
                                {SITUATION_LABEL[t]}
                            </label>
                        ))}
                    </fieldset>
                    {live && (
                        <label className="field">
                            <span className="lbl">정정 사유 (필수)</span>
                            <textarea
                                value={reason}
                                maxLength={200}
                                rows={2}
                                placeholder="예) 현장 확인 결과 장비 이상이 맞음"
                                onChange={(e) => setReason(e.target.value)}
                            />
                        </label>
                    )}
                    <p className="xs muted">원문과 이전 분석은 그대로 남습니다.</p>
                    <div className="row" style={{ justifyContent: 'flex-end' }}>
                        <button
                            type="button"
                            className="btn ghost"
                            onClick={() => ref.current?.close()}
                        >
                            취소
                        </button>
                        <button
                            type="submit"
                            className="btn primary"
                            disabled={live && !reason.trim()}
                        >
                            확인
                        </button>
                    </div>
                </form>
            </dialog>
        </>
    );
}
