import { useState } from 'react';
import { useChangeReview, useData, useViewer } from '../../domain/hooks';
import { affectedOf, byId, groupName, isValidTimeRange, nameOf } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import type { ChangeOp, EventData } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import issueStyles from '../issue/issue.module.css';
import report from '../../shared/ui/report.module.css';
import { Empty } from '../../shared/ui/atoms';
import { ChangeTable, TextCompare } from './ChangeDiff';
import { ChangeEditor } from './ChangeEditor';
import { ChangeActions, ChangeNotice, ChangeStatusChip } from './ChangeParts';
import styles from './change.module.css';
import { AlternativesView, CoordinationView } from '../response/AgentRecords';
import { DocText } from './DocText';
import { checksOf, defaultChecks, draftOf, opsFromDraft, type ChangeDraft } from './change-doc';

/**
 * 변경안 전후 비교·확정을 보고서 형식으로 적는다. 변경 화면(X01)과 보고·대응(W04) 오른쪽 칸이 같은 컴포넌트를 쓴다.
 * 바깥 폭·여백은 감싸는 쪽이 정한다.
 */
export function ChangeReview({ id, onDone }: { id: string | undefined; onDone?: () => void }) {
    const d = useData();
    const v = useViewer();
    const dispatch = useDispatch();
    const review = useChangeReview(id);
    const shell = useShell();
    // 확정 전(READY) 변경안은 총괄이 시간·담당·절차·확인 필요 사항을 고칠 수 있다. 저장 전까지는 이 초안에만 쓴다
    const [draft, setDraft] = useState<(ChangeDraft & { id: string }) | null>(null);
    if (!review) return <Empty>변경안을 찾을 수 없습니다</Empty>;
    const { change: c, rows, stale, reasons } = review;
    const issue = c.issueId ? d.issues.find((i) => i.id === c.issueId) : undefined;
    const editing = draft?.id === c.id && c.status === 'READY' ? draft : null;
    // 영향 그룹·받는 사람은 편집 중 고른 시간·담당을 바로 따라간다
    const ops = editing ? opsFromDraft(d, editing) : c.ops;
    const who = affectedOf({ ...c, ops })
        .map((m) => byId(d, m))
        .filter((m) => !!m);
    const groups = [...new Set(who.map((m) => groupName(d, m.groupId)))];
    const patch = (next: Partial<ChangeDraft>) => setDraft((all) => all && { ...all, ...next });
    const checks = checksOf(c)
        .split('\n')
        .filter((l) => l.trim());
    const problem = editing?.ops.some(
        (o) => o.kind !== 'cancel' && !isValidTimeRange(o.after.start, o.after.end),
    )
        ? '시작/종료 시간을 입력하고, 종료를 시작보다 늦게 정해주세요.'
        : '';
    const save = () => {
        if (!editing || problem) return;
        dispatch({ type: 'editChange', id: c.id, ops, checks: editing.checks });
        setDraft(null);
    };

    return (
        <div className={report.wrap} data-change-editing={editing ? '' : undefined}>
            <div className={report.layout}>
                <div className={`stack ${report.body} ${report.report}`}>
                    {/* 제목은 가운데, 편집·확정·폐기(편집 중이면 저장·취소)는 보고서 오른쪽 위 */}
                    <header className={`${report.head} ${styles.reviewHead}`}>
                        <h3 className="h3">{c.title}</h3>
                        <div className={styles.headActions}>
                            {editing ? (
                                <>
                                    {problem && <span className="xs urgent">{problem}</span>}
                                    <button
                                        type="button"
                                        className="btn primary"
                                        disabled={!!problem}
                                        onClick={save}
                                    >
                                        편집 저장
                                    </button>
                                    <button
                                        type="button"
                                        className="btn"
                                        onClick={() => setDraft(null)}
                                    >
                                        취소
                                    </button>
                                </>
                            ) : (
                                <ChangeActions
                                    change={c}
                                    reasons={reasons}
                                    onDone={onDone}
                                    onEdit={
                                        v.isChief && c.status === 'READY'
                                            ? () => setDraft({ ...draftOf(d, c), id: c.id })
                                            : undefined
                                    }
                                />
                            )}
                        </div>
                        <p className={`small ${report.byline}`}>작성자: {nameOf(d, c.by)}</p>
                    </header>
                    {!editing && <ChangeNotice change={c} reasons={reasons} />}
                    <section className={report.section}>
                        <h4>1. 개요</h4>
                        <ul className={report.items}>
                            <li>
                                <b>상태:</b> <ChangeStatusChip change={c} stale={stale} />
                            </li>
                            <li>
                                <b>구분:</b> {c.mode === 'AI' ? 'AI 대응안(후보)' : '직접 변경'}
                            </li>
                            {c.at && (
                                <li>
                                    <b>작성:</b> {c.at}
                                </li>
                            )}
                            <li>
                                <b>연결 이슈:</b>{' '}
                                {issue
                                    ? // 실서버 이슈 id는 UUID라 제목만 보인다(#371)
                                      /^[0-9a-f-]{36}$/i.test(issue.id)
                                        ? issue.title
                                        : `${issue.id} ${issue.title}`
                                    : '없음 (일반 변경)'}
                            </li>
                            <li>
                                <b>확인 요청:</b> {who.length}명
                            </li>
                            {c.source && (
                                <li>
                                    <b>만든 곳:</b> {c.source}
                                </li>
                            )}
                            {stale && (
                                <li>
                                    <b className="urgent">낡은 근거:</b> 다시 확인하기 전에는 확정할
                                    수 없습니다.
                                    <ul className={report.sub}>
                                        {reasons.map((r) => (
                                            <li key={r}>{r}</li>
                                        ))}
                                    </ul>
                                </li>
                            )}
                            {c.previewHash && (
                                <li>
                                    <b>미리 보기 지문:</b>{' '}
                                    <span className="code">{c.previewHash}</span> / 기준 상태 번호{' '}
                                    {c.baseRevision}
                                </li>
                            )}
                        </ul>
                    </section>
                    {/* AI 대응안의 참고 기록(V9-D55, #389). 실서버 AI 변경안에만 있고 비어 있으면 그리지 않는다 */}
                    {(!!c.agentRecords?.alternatives?.length || !!c.agentRecords?.coordination) && (
                        <section className={report.section} aria-label="AI 참고 기록">
                            <h4>AI 참고 기록</h4>
                            <AlternativesView alternatives={c.agentRecords.alternatives} />
                            <CoordinationView coordination={c.agentRecords.coordination} />
                        </section>
                    )}
                    <section className={report.section}>
                        <h4>2. 변경 전후</h4>
                        {editing ? (
                            <ChangeEditor change={c} draft={editing} onChange={patch} />
                        ) : (
                            <ChangeTable rows={rows} />
                        )}
                        {!!c.reservations?.length && (
                            <ul className={report.items}>
                                <li>
                                    <b>장비 예약(확정 시 추가)</b>
                                    <ul className={report.sub}>
                                        {c.reservations.map((r) => (
                                            <li key={`${r.assetId}${r.start}`}>
                                                {d.equipment.find((x) => x.id === r.assetId)
                                                    ?.name ?? r.assetId}{' '}
                                                / {r.kind} {r.start}–{r.end} / {r.label}
                                            </li>
                                        ))}
                                    </ul>
                                </li>
                            </ul>
                        )}
                        {!!c.releases?.length && (
                            <ul className={report.items}>
                                <li>
                                    <b>장비 예약(확정 시 해제)</b>
                                    <ul className={report.sub}>
                                        {c.releases.map((id) => {
                                            const r = d.reservations.find((x) => x.id === id);
                                            return (
                                                <li key={id}>
                                                    {r
                                                        ? `${d.equipment.find((a) => a.id === r.assetId)?.name ?? r.assetId} / ${r.kind} ${r.start}–${r.end} / ${r.label}`
                                                        : `${id} / 이미 없는 예약`}
                                                </li>
                                            );
                                        })}
                                    </ul>
                                </li>
                            </ul>
                        )}
                    </section>
                    <section className={report.section}>
                        <h4>3. 영향</h4>
                        <ul className={report.items}>
                            <li>
                                <b>영향 그룹:</b> {groups.join(', ') || '없음'}
                            </li>
                            <li>
                                <b>받는 사람:</b>{' '}
                                {who
                                    .map((m) => `${m.name}${m.isReserve ? ' (예비 투입)' : ''}`)
                                    .join(', ') || '없음'}
                            </li>
                        </ul>
                    </section>
                    <section className={report.section}>
                        <h4>4. 확인 필요 사항</h4>
                        {/* 서버 변경안에 없는 칸(실서버)은 고칠 수 없다 */}
                        {editing && !c.fixedChecks ? (
                            <TextCompare original={defaultChecks(c)} edited={editing.checks}>
                                <DocText
                                    label="확인 필요 사항"
                                    value={editing.checks}
                                    onChange={(next) => patch({ checks: next })}
                                />
                            </TextCompare>
                        ) : checksOf(c).trim() !== defaultChecks(c).trim() ? (
                            <TextCompare original={defaultChecks(c)} edited={checksOf(c)} />
                        ) : (
                            <ul className={report.items}>
                                {checks.map((line, k) => (
                                    <li key={k}>{line}</li>
                                ))}
                            </ul>
                        )}
                    </section>
                </div>
                {/* 보고서 오른쪽(좁으면 아래): 이 대응안을 적용하면 바뀌는 업무 */}
                <aside className={`${report.side} ${styles.changeSide}`}>
                    <div className={issueStyles.relatedGroup}>
                        <h4>변경되는 업무</h4>
                        {changedTasks(d, ops).length ? (
                            changedTasks(d, ops).map((t) => (
                                <button
                                    key={t.key}
                                    type="button"
                                    className={`btn ${issueStyles.taskBtn} ${styles.changeTask}`}
                                    disabled={!t.taskId || !shell.canOpenTask}
                                    onClick={() => t.taskId && shell.openTask(t.taskId)}
                                >
                                    {/* 좁은 칸이라 표시는 제목 줄 오른쪽에, 시간·담당은 줄바꿈해 보인다 */}
                                    <span className={styles.changeTaskHead}>
                                        <b>{t.title}</b>
                                        <span className={styles.opChip} data-op={t.kind}>
                                            {OP_LABEL[t.kind]}
                                        </span>
                                    </span>
                                    {t.lines.map((l) => (
                                        <span key={l} className="xs">
                                            {l}
                                        </span>
                                    ))}
                                </button>
                            ))
                        ) : (
                            <span className="xs muted">바뀌는 업무가 없습니다.</span>
                        )}
                    </div>
                </aside>
            </div>
        </div>
    );
}

const OP_LABEL = { update: '변경', add: '추가', cancel: '취소' } as const;

/** 변경안의 업무 작업(변경·추가·취소)을 오른쪽 칸 목록으로 만든다. 공연 순서(program) 변경은 업무가 아니라 뺀다 */
type ChangedTask = {
    key: string;
    /** 새로 추가되는 업무는 아직 id가 없다 */
    taskId: string | null;
    kind: keyof typeof OP_LABEL;
    title: string;
    lines: string[];
};

function changedTasks(d: EventData, ops: ChangeOp[]): ChangedTask[] {
    const slot = (s: { start: string; end: string }) => `${s.start}–${s.end}`;
    return ops.flatMap((o, k): ChangedTask[] => {
        if (o.kind === 'program') return [];
        if (o.kind === 'add')
            return [
                {
                    key: `add-${k}`,
                    taskId: null,
                    kind: 'add' as const,
                    title: o.title,
                    lines: [
                        `시간: ${slot(o.after)}`,
                        `담당: ${o.after.assignee ? nameOf(d, o.after.assignee) : '미배정'}`,
                    ],
                },
            ];
        const title = d.tasks.find((t) => t.id === o.taskId)?.title ?? o.taskId;
        if (o.kind === 'cancel')
            return [
                {
                    key: `cancel-${o.taskId}`,
                    taskId: o.taskId,
                    kind: 'cancel' as const,
                    title,
                    lines: [`시간: ${slot(o.before)}`],
                },
            ];
        const lines: string[] = [];
        if (slot(o.before) !== slot(o.after))
            lines.push(`시간: ${slot(o.before)} → ${slot(o.after)}`);
        if (o.after.assignee !== undefined && o.after.assignee !== o.before.assignee)
            lines.push(
                `담당: ${nameOf(d, o.before.assignee) || '미배정'} → ${
                    o.after.assignee ? nameOf(d, o.after.assignee) : '미배정'
                }`,
            );
        if (o.after.zone && o.after.zone !== o.before.zone)
            lines.push(`구역: ${o.before.zone ?? '-'} → ${o.after.zone}`);
        if (o.after.steps) lines.push('절차 변경');
        return [
            {
                key: `update-${o.taskId}`,
                taskId: o.taskId,
                kind: 'update' as const,
                title,
                lines: lines.length ? lines : ['내용 변경'],
            },
        ];
    });
}
