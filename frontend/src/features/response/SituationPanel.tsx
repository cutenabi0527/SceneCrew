import { useState } from 'react';
import type { IssueSituation, SituationType } from './contract';

// 총괄의 운영 상황 확인·정정(#359, V9-D48). 실서버 보고·대응 화면에서 쓴다.
// 정정에는 사유가 필수이고, 정정값이 AI 추천보다 우선한다. 보고별 AI 추천은 지워지지 않고 따로 보인다.

const SITUATION_TEXT: Record<SituationType, string> = {
    SCHEDULE_DELAY: '일정 지연',
    STAFF_GAP: '인력 공백',
    TASK_CONFLICT: '업무 충돌',
    EQUIPMENT_ANOMALY: '장비 이상',
    PERFORMANCE_ORDER_CHANGE: '공연 순서 변경',
    AUDIENCE_SAFETY: '관객 안전',
};
const TYPES = Object.keys(SITUATION_TEXT) as SituationType[];

const label = (t: SituationType | null) => (t ? SITUATION_TEXT[t] : '미분류');
const line = (primary: SituationType | null, related: SituationType[]) =>
    related.length
        ? `${label(primary)} / 연관: ${related.map((r) => SITUATION_TEXT[r]).join(', ')}`
        : label(primary);

export interface SituationInput {
    primaryType: SituationType | null;
    relatedTypes: SituationType[];
    reason: string;
}

export function SituationPanel({
    situation,
    busy,
    onSubmit,
}: {
    situation: IssueSituation | null | undefined;
    busy: boolean;
    onSubmit: (input: SituationInput) => Promise<boolean>;
}) {
    const [editing, setEditing] = useState(false);
    const [primary, setPrimary] = useState<SituationType | ''>('');
    const [related, setRelated] = useState<SituationType[]>([]);
    const [reason, setReason] = useState('');
    const open = () => {
        setPrimary(situation?.primaryType ?? '');
        setRelated(situation?.relatedTypes ?? []);
        setReason('');
        setEditing(true);
    };
    const suggestion = situation?.suggestion;
    return (
        <section aria-label="운영 상황" className="stack">
            <b>운영 상황</b>
            {situation ? (
                <>
                    <p className="small">
                        {line(situation.primaryType, situation.relatedTypes)} /{' '}
                        {situation.confirmed ? '총괄 확인' : 'AI 추천(확인 전)'}
                    </p>
                    {situation.confirmed && situation.reason && (
                        <p className="xs">정정 사유: {situation.reason}</p>
                    )}
                    {situation.confirmed && suggestion && (
                        <p className="xs">
                            최신 AI 추천: {line(suggestion.primaryType, suggestion.relatedTypes)}
                        </p>
                    )}
                </>
            ) : (
                <p className="small">아직 상황 추천이 없습니다. 직접 확인해 기록할 수 있습니다.</p>
            )}
            {!editing ? (
                <button className="btn" type="button" disabled={busy} onClick={open}>
                    상황 확인/정정
                </button>
            ) : (
                <form
                    className="stack"
                    aria-label="운영 상황 정정"
                    onSubmit={(e) => {
                        e.preventDefault();
                        if (!reason.trim()) return;
                        void onSubmit({
                            primaryType: primary || null,
                            relatedTypes: related.filter((r) => r !== primary),
                            reason: reason.trim(),
                        }).then((ok) => {
                            if (ok) setEditing(false);
                        });
                    }}
                >
                    <label className="field">
                        <span>대표 상황</span>
                        <select
                            value={primary}
                            onChange={(e) => setPrimary(e.target.value as SituationType | '')}
                        >
                            <option value="">미분류</option>
                            {TYPES.map((t) => (
                                <option key={t} value={t}>
                                    {SITUATION_TEXT[t]}
                                </option>
                            ))}
                        </select>
                    </label>
                    <fieldset className="stack">
                        <legend className="small">연관 상황(여러 개 가능)</legend>
                        {TYPES.filter((t) => t !== primary).map((t) => (
                            <label key={t} className="row">
                                <input
                                    type="checkbox"
                                    checked={related.includes(t)}
                                    onChange={(e) =>
                                        setRelated((cur) =>
                                            e.target.checked
                                                ? [...cur, t]
                                                : cur.filter((x) => x !== t),
                                        )
                                    }
                                />
                                {SITUATION_TEXT[t]}
                            </label>
                        ))}
                    </fieldset>
                    <label className="field">
                        <span>정정 사유(필수)</span>
                        <textarea
                            aria-label="정정 사유"
                            value={reason}
                            maxLength={1000}
                            onChange={(e) => setReason(e.target.value)}
                        />
                    </label>
                    <div className="row">
                        <button className="btn" type="submit" disabled={busy || !reason.trim()}>
                            저장
                        </button>
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={() => setEditing(false)}
                        >
                            취소
                        </button>
                    </div>
                </form>
            )}
        </section>
    );
}
