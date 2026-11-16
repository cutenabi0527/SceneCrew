import type { ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useData, useEvent, useSession, useTask, useViewer } from '../../domain/hooks';
import {
    NOW_MIN,
    OBS_FRESH_LABEL,
    OBS_QUALITY_LABEL,
    byId,
    nameOf,
    stepLevel,
    tasksOf,
    toMin,
    zoneReading,
} from '../../domain/selectors';
import { EVENT_DATE } from '../../domain/seed';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { EquipStep, SafetyStep, Task } from '../../domain/types';
import { LinkTaskNote } from '../../features/camera-link/LinkTaskNote';
import { ZoneStateChip } from '../../features/safety/SafetyParts';
import { useShell } from '../../layouts/shell-actions';
import { Icon, type IconName } from '../../shared/ui/Icon';
import { AppTaskActions, LateChip, ProgressBar, StatusChip } from './AppTaskParts';
import { dateLabel, isClosed } from './app-task-utils';
import s from './appTask.module.css';

const EQUIP_STEPS: [EquipStep, string][] = [
    ['pickup', '인수'],
    ['install', '설치'],
    ['retest', '재시험'],
];
const SAFETY_STEPS: [SafetyStep, string][] = [
    ['arrive', '도착'],
    ['check', '현장 확인'],
];

/** 운영 중인 내 업무만 체크·시작/완료·수행 기록을 한다. 종료(ENDED) 뒤에는 읽기만 한다 */
function useMine(t: Task) {
    const ev = useEvent();
    const { me } = useSession();
    return ev.state === 'LIVE' && t.assignee === me && !isClosed(t);
}

/**
 * 수행 단계(위에서 아래로): 끝난 단계는 ✓와 기록 시각, 지금 할 단계는 진한 번호와 기록 버튼(내 업무일 때),
 * 남은 단계는 흐린 번호와 '앞 단계 뒤'. 글자 대신 번호와 진하기로 순서를 보인다.
 */
function StepList<K extends string>({
    steps,
    records,
    action,
    onRecord,
}: {
    steps: [K, string][];
    records: Partial<Record<K, string>>;
    /** 버튼 글자: '기록'(장비) · '보고'(안전) */
    action: string;
    onRecord?: (k: K) => void;
}) {
    const next = steps.findIndex(([k]) => !records[k]);
    return (
        <ol className={s.steps}>
            {steps.map(([k, label], i) => {
                const at = records[k];
                const state = at ? 'done' : i === next ? 'now' : 'later';
                return (
                    <li key={k} data-state={state}>
                        {at ? (
                            <svg
                                className={s.stepDone}
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2.2"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                role="img"
                                aria-label="완료"
                            >
                                <path d="m5 12 5 5 9-10" />
                            </svg>
                        ) : (
                            <span className={s.stepNum}>{i + 1}</span>
                        )}
                        <span>{label}</span>
                        {at && <small>{at}</small>}
                        {state === 'now' && onRecord && (
                            <button type="button" className={s.stepBtn} onClick={() => onRecord(k)}>
                                {label} {action}
                            </button>
                        )}
                        {state === 'later' && i > 0 && <small>{steps[i - 1][1]} 뒤</small>}
                    </li>
                );
            })}
        </ol>
    );
}

/** 장비 수행(M05): 장비·인수 위치→도착 구역·이유와 인수/설치/재시험. 예약/배정만으로는 기록하지 않는다 */
function EquipmentSection({ task: t }: { task: Task }) {
    const d = useData();
    const dispatch = useDispatch();
    const mine = useMine(t);
    if (!t.equipment) return null;
    const e = t.equipment;
    const names = e.assets.map((id) => d.equipment.find((a) => a.id === id)?.name ?? id).join(', ');
    return (
        <section aria-labelledby="task-eq" className={s.section}>
            <h2 id="task-eq" className={s.h2}>
                장비 · {names}
            </h2>
            <div className={s.box}>
                <span className={s.meta}>
                    {e.pickup} → {e.dest} · {e.reason}
                </span>
                <StepList
                    steps={EQUIP_STEPS}
                    records={e.records}
                    action="기록"
                    onRecord={
                        mine
                            ? (step) => dispatch({ type: 'equipmentRecord', taskId: t.id, step })
                            : undefined
                    }
                />
                <span className={s.note}>
                    예약·배정만으로는 기록되지 않습니다. 재시험 뒤 객석 출력 확인은 보고로 남겨
                    주세요
                </span>
            </div>
        </section>
    );
}

/** 안전 수행(M05): 담당 구역 추정 인원(참고용)과 도착/현장 확인 보고 */
function SafetySection({ task: t }: { task: Task }) {
    const d = useData();
    const dispatch = useDispatch();
    const mine = useMine(t);
    if (!t.safety) return null;
    const sf = t.safety;
    const r = zoneReading(d, sf.zoneId);
    return (
        <section aria-labelledby="task-safety" className={s.section}>
            <h2 id="task-safety" className={s.h2}>
                안전 · {r.zone?.name ?? '담당 구역'}
            </h2>
            <div className={s.box}>
                <div className={s.row}>
                    <span className={s.meta}>
                        추정 인원 {r.usable ? `${r.obs?.count}명` : '확인 필요'}
                    </span>
                    <ZoneStateChip zoneId={sf.zoneId} />
                </div>
                <span className={s.sub}>
                    관측 {r.obs?.at ?? '시각 불명'} ·{' '}
                    {r.obs
                        ? `${OBS_QUALITY_LABEL[r.obs.quality]} · ${OBS_FRESH_LABEL[r.obs.fresh]}`
                        : '미관측'}
                </span>
                {sf.keep && <span className={s.meta}>유지 조건 · {sf.keep}</span>}
                <StepList
                    steps={SAFETY_STEPS}
                    records={sf.records}
                    action="보고"
                    onRecord={
                        mine
                            ? (step) => dispatch({ type: 'safetyRecord', taskId: t.id, step })
                            : undefined
                    }
                />
                <span className={s.note}>
                    관측 수치는 참고용 추정입니다. 이동·통제 지시는 총괄 승인 뒤에만 따릅니다
                </span>
            </div>
        </section>
    );
}

/** 권한 없음·못 찾음: 이유와 다음에 갈 곳(내 업무, 계획) */
function Blocked({
    icon,
    title,
    children,
}: {
    icon: IconName;
    title: string;
    children: ReactNode;
}) {
    const navigate = useNavigate();
    return (
        <section className={s.blocked} aria-labelledby="task-blocked">
            <span className={s.blockedIcon}>
                <Icon name={icon} />
            </span>
            <h2 id="task-blocked">{title}</h2>
            <p>{children}</p>
            <div className={s.blockedBtns}>
                <button type="button" className={s.primary} onClick={() => navigate('/e/today')}>
                    내 업무로
                </button>
                <button type="button" className={s.secondary} onClick={() => navigate('/e/plan')}>
                    계획에서 보기
                </button>
            </div>
        </section>
    );
}

/** 요약 카드 오른쪽 위: 예정 시간과 남은(또는 시작까지·지난) 시간 */
function timeLeft(t: Task) {
    if (isClosed(t)) return null;
    if (t.status === 'doing') {
        const left = toMin(t.end) - NOW_MIN;
        return left >= 0 ? `${left}분 남음` : `${-left}분 지남`;
    }
    const until = toMin(t.start) - NOW_MIN;
    return until > 0 ? `${until}분 뒤 시작` : null;
}

/**
 * M05 업무 상세(APP): 요약 카드(상태·남은 시간·완료/빠른 보고) → 할 일 → (장비 수행 / 안전 수행) →
 * 확인 항목(내 업무면 체크) → 관련 이슈 → 그룹·담당과 끝난 뒤 다음 업무 한 줄.
 * 장비 업무는 인수·설치·재시험을, 안전 업무는 담당 구역 최소 집계와 도착·현장 확인을 따로 기록한다.
 * WEB은 업무 조정 오른쪽 패널에 업무 카드가 뜬다.
 */
export function AppTask() {
    const { taskId } = useParams();
    const t = useTask(taskId);
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const dispatch = useDispatch();
    const shell = useShell();
    const live = !!useDemoState().live;
    const { me } = useSession();
    if (!t)
        return (
            <Blocked icon="info" title="업무를 찾을 수 없습니다">
                취소되었거나 주소가 바뀐 업무입니다. 내 업무나 계획에서 다시 찾아 주세요.
            </Blocked>
        );
    if (!v.canViewTask(t))
        return (
            <Blocked icon="lock" title="다른 사람의 업무입니다">
                업무 상세는 담당자만 볼 수 있습니다. 시간과 구역은 계획에서 확인하세요.
            </Blocked>
        );
    const issues = d.issues.filter((i) => i.tasks.includes(t.id));
    // 종료(ENDED) 뒤에는 읽기만 한다: 체크·시작/완료·수행 기록을 막는다
    const mine = ev.state === 'LIVE' && t.assignee === me && !isClosed(t);
    const program = ev.programs.find((p) => p.id === t.program);
    const group = d.groups.find((g) => g.id === t.groupId);
    const checked = t.checklist.filter((c) => c.done).length;
    const day = t.date ?? EVENT_DATE;
    const left = timeLeft(t);
    // 끝난 뒤 이어지는 담당자의 다음 업무(같은 날, 이 업무 끝 이후 시작)와 근무 끝 시각
    const after = tasksOf(d, t.assignee ?? '').find(
        (x) =>
            x.id !== t.id &&
            !isClosed(x) &&
            (x.date ?? EVENT_DATE) === day &&
            toMin(x.start) >= toMin(t.end),
    );
    const workEnd = byId(d, t.assignee)?.avail?.split(/[–~-]/)[1]?.trim();
    return (
        <div className={s.page}>
            {/* 카메라 연결 담당 업무: 휴대폰이 아니라 노트북 WEB의 내 연결에서 연결한다(#396) */}
            {live && <LinkTaskNote taskId={t.id} />}
            <section
                aria-label="업무 요약"
                className={`${s.card} ${t.status === 'doing' ? s.now : ''}`}
            >
                <div className={s.row}>
                    <div className={s.chips}>
                        <StatusChip task={t} withChecks />
                        <LateChip task={t} />
                    </div>
                    <span className={s.sub}>
                        {day !== EVENT_DATE && `${dateLabel(day)} · `}
                        {t.start}–{t.end}
                        {left && (
                            <>
                                {' · '}
                                <strong style={{ color: 'var(--fg)' }}>{left}</strong>
                            </>
                        )}
                    </span>
                </div>
                <div className={s.nowTitle}>
                    <h2 className={s.title}>
                        {t.title}
                        {/* 실서버 업무 id는 UUID라 보이지 않는다(#373) */}
                        {!live && <span className={s.code}> {t.id}</span>}
                    </h2>
                    <span className={s.meta}>
                        {t.zone}
                        {program && ` · ${program.name}`}
                    </span>
                </div>
                {t.status === 'doing' && <ProgressBar task={t} />}
                {t.changed && !t.ack && <div className={s.changed}>변경됨 · {t.changed}</div>}
                <AppTaskActions task={t} />
            </section>

            {t.steps.length > 0 && (
                <section aria-labelledby="task-steps" className={s.section}>
                    <h2 id="task-steps" className={s.h2}>
                        할 일
                    </h2>
                    <ol className={s.todo}>
                        {t.steps.map((step, i) => (
                            <li key={step} style={{ marginLeft: stepLevel(step) * 16 }}>
                                <span className={s.num}>{i + 1}</span>
                                {step.trim()}
                            </li>
                        ))}
                    </ol>
                </section>
            )}

            <EquipmentSection task={t} />
            <SafetySection task={t} />

            {t.checklist.length > 0 && (
                <section aria-labelledby="task-checks" className={s.section}>
                    <h2 id="task-checks" className={s.h2}>
                        확인 항목 {checked}/{t.checklist.length}
                    </h2>
                    <div className={s.checks}>
                        {t.checklist.map((c, i) => (
                            <label
                                key={c.text}
                                className={s.check}
                                data-done={c.done}
                                style={{ marginLeft: stepLevel(c.text) * 16 }}
                            >
                                <input
                                    type="checkbox"
                                    checked={c.done}
                                    disabled={!mine}
                                    onChange={() =>
                                        dispatch({ type: 'toggleCheck', id: t.id, index: i })
                                    }
                                />
                                {c.text.trim()}
                            </label>
                        ))}
                    </div>
                </section>
            )}

            {issues.length > 0 && (
                <section aria-labelledby="task-issues" className={s.section}>
                    <h2 id="task-issues" className={s.h2}>
                        관련 이슈
                    </h2>
                    <div>
                        {issues.map((i) => (
                            <button
                                key={i.id}
                                type="button"
                                className={s.issue}
                                onClick={() => shell.openIssue(i.id)}
                            >
                                {i.title}
                                {!live && <small>{i.id}</small>}
                            </button>
                        ))}
                    </div>
                </section>
            )}

            <section aria-label="업무 정보" className={s.footer}>
                <span>
                    {[group?.name, `담당 ${nameOf(d, t.assignee)}`].filter(Boolean).join(' · ')}
                </span>
                {!isClosed(t) && (
                    <span>
                        {after
                            ? `끝나면 다음 업무 ${after.start} ${after.title}`
                            : ['끝나면 다음 업무 없음', workEnd && `근무 ${workEnd}까지`]
                                  .filter(Boolean)
                                  .join(' · ')}
                    </span>
                )}
            </section>
        </div>
    );
}
