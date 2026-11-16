import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { Link } from 'react-router';
import { EventManageRoute } from '../event-admin/EventManage';
import { BASIC_FIELDS } from '../event-admin/labels';
import { parsePlanJob, type PlanQuestion } from '../plan-job/contract';
import { questionTarget } from '../plan-job/question-target';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { extractionLabel } from '../plan-workflow/extraction-fields';
import { ShellFrame, type Frame } from '../plan-workflow/frame';
import { jobMemoryKey, readConditions, writeConditions } from '../plan-workflow/job-memory';
import { DraftDesk } from '../unconfirmed-draft/client';
import { dropFiles, peekFiles } from './handoff';
import { pdfRunner } from './runner';
import { STEP_LABEL, type PdfAnswers, type PdfDeps, type PdfStep } from './pipeline';

const STEPS = Object.keys(STEP_LABEL) as PdfStep[];

function depsOf(client: Workflow, desk: DraftDesk): PdfDeps {
    const key = jobMemoryKey(client.scope);
    return {
        read: () => client.read(),
        upload: (files) => client.upload(files),
        generate: (kind, revision) => client.generate(kind, revision),
        lastJob: (kind) => client.jobs.get(kind),
        extraction: (id) => client.extraction(id),
        planJob: (id) =>
            client.session.api.get(client.scope, `/ai-jobs/${id}`, (d) => parsePlanJob(d, id)),
        basics: (fields, revision) => client.basics(fields, revision),
        input: (fields, revision) => client.input(fields, revision),
        save: (draft, revision) => client.save(draft, revision),
        candidate: (id, revision) => client.candidate(id, revision),
        savedConditions: () => readConditions(key),
        rememberConditions: (conditions) => writeConditions(key, conditions),
        readUnconfirmed: () => desk.read(),
        saveUnconfirmed: (input) => desk.save(input),
        wait: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
        tempId: () => `tmp-${crypto.randomUUID()}`,
    };
}

/**
 * W01-DRAFT PDF 탭(V9-D65). 문서를 올리고 추출을 한 번 누르면 공개 직전까지 자동으로 저장하고,
 * 빈 필수칸·충돌·추가 질문이 있을 때만 그 칸을 보여 준다. 끝나면 공개 버튼만 남는다.
 */
export function QuickPdfPanel({
    eventId,
    frame = ShellFrame,
    onChanged,
}: {
    eventId: string;
    frame?: Frame;
    onChanged?: () => void;
}) {
    return (
        <EventAccess eventId={eventId} frame={frame}>
            {(client) => <Panel client={client} eventId={eventId} onChanged={onChanged} />}
        </EventAccess>
    );
}

function Panel({
    client,
    eventId,
    onChanged,
}: {
    client: Workflow;
    eventId: string;
    onChanged?: () => void;
}) {
    const deps = useMemo(
        () => depsOf(client, new DraftDesk(client.session, client.scope)),
        [client],
    );
    const runner = useMemo(() => pdfRunner(jobMemoryKey(client.scope)), [client]);
    const { running, step, outcome } = useSyncExternalStore(runner.subscribe, runner.snapshot);
    const [files, setFiles] = useState<File[]>([]);
    const [answers, setAnswers] = useState<Record<string, string>>({});

    const start = (options: { files?: File[]; answers?: PdfAnswers }) => {
        if (runner.start(deps, options)) setAnswers({});
    };

    // 공연 만들기 PDF 탭에서 넘어왔으면 바로 시작한다. 이 탭에서 PDF 진행을 시작한 적이 있으면(새로고침) 이어 간다.
    // 직접 입력 탭에서 한 추출만으로는 저절로 저장하지 않는다.
    // 이미 돌고 있거나 결과가 있으면 그대로 보인다(실행기는 화면과 떨어져 한 번만 돈다).
    useEffect(() => {
        const state = runner.snapshot();
        if (state.running || state.outcome) return;
        const handed = peekFiles(eventId);
        if (handed?.length) {
            if (runner.start(deps, { files: handed })) dropFiles(eventId);
        } else if (runner.started()) runner.start(deps, {});
    }, [runner, deps, eventId]);
    // 진행이 끝나면(완료·멈춤) 운영 화면 값을 다시 읽는다. 같은 결과로 두 번 알리지 않는다
    const notified = useRef(outcome);
    useEffect(() => {
        if (!outcome || notified.current === outcome) return;
        notified.current = outcome;
        onChanged?.();
    }, [outcome, onChanged]);

    const answerInput = (key: string, label: string, kind: string) => (
        <div className="field" key={key}>
            <label htmlFor={`pdf-${key}`}>{label}</label>
            <input
                id={`pdf-${key}`}
                type={kind === 'time' ? 'datetime-local' : kind === 'date' ? 'date' : 'text'}
                inputMode={kind === 'number' ? 'numeric' : undefined}
                value={answers[key] ?? ''}
                onChange={(e) => setAnswers({ ...answers, [key]: e.target.value })}
            />
        </div>
    );
    const basicKind = (key: string) => BASIC_FIELDS.find((f) => f.key === key)?.kind ?? 'text';
    const basicKeyOf = (q: PlanQuestion) =>
        q.field.startsWith('payload.event.')
            ? q.field.slice('payload.event.'.length).split(/[.[]/)[0]
            : '';

    return (
        <section className="card stack" aria-label="PDF로 계획 만들기">
            <h2 className="h3">PDF로 계획 만들기</h2>
            <p className="small muted">
                문서를 올리고 추출을 누르면 기본정보, 구역, 직무 그룹, 공연 순서, AI 계획을 차례로
                저장합니다. 문서에 없는 값은 채우지 않고 물어봅니다. 다 되면 공개 버튼만 남습니다.
            </p>
            <ol className="stack" aria-label="진행 단계">
                {STEPS.map((s) => {
                    const index = step ? STEPS.indexOf(step) : -1;
                    const mine = STEPS.indexOf(s);
                    const done = outcome?.kind === 'done' || mine < index;
                    const now = s === step && running;
                    return (
                        <li key={s} className="small" aria-current={now ? 'step' : undefined}>
                            {done ? '완료' : now ? '진행 중' : '대기'} / {STEP_LABEL[s]}
                        </li>
                    );
                })}
            </ol>
            {running && step && (
                <p role="status">{STEP_LABEL[step]} 중입니다. 이 화면을 열어 두세요.</p>
            )}
            {!running && outcome?.kind !== 'done' && (
                <div className="stack">
                    <label className="field">
                        <span>공연 문서</span>
                        <input
                            type="file"
                            accept=".pdf,.txt,.md"
                            multiple
                            onChange={(e) => setFiles([...(e.target.files ?? [])])}
                        />
                    </label>
                    <p className="xs muted">
                        PDF/TXT/MD 최대 3개, 파일당 20MiB / 합계 30MiB입니다.
                    </p>
                    <button
                        className="btn primary"
                        type="button"
                        disabled={!files.length}
                        onClick={() => start({ files })}
                    >
                        추출 시작
                    </button>
                </div>
            )}
            {!running && outcome?.kind === 'need-files' && (
                <p role="status">계획을 만들 공연 문서를 골라 추출을 시작하세요.</p>
            )}
            {!running && outcome?.kind === 'missing' && (
                <form
                    className="stack"
                    aria-label="문서에서 찾지 못한 기본정보"
                    onSubmit={(e) => {
                        e.preventDefault();
                        start({ answers: { basics: answers } });
                    }}
                >
                    <p role="alert">
                        문서에서 아래 값을 찾지 못했거나 값이 둘 이상입니다. 채운 뒤 이어서
                        진행하세요.
                        {outcome.unreadable > 0 &&
                            ` 읽지 못한 문서 ${outcome.unreadable}개가 있습니다.`}
                    </p>
                    {outcome.fields.map((key) =>
                        answerInput(key, extractionLabel(key), basicKind(key)),
                    )}
                    {outcome.conflicts.map((c) => (
                        <div key={c.field} className="stack">
                            {answerInput(c.field, extractionLabel(c.field), basicKind(c.field))}
                            <p className="xs muted">문서에 적힌 값: {c.values.join(', ')}</p>
                        </div>
                    ))}
                    <button className="btn primary">이어서 진행</button>
                </form>
            )}
            {!running && outcome?.kind === 'questions' && (
                <form
                    className="stack"
                    aria-label="AI 추가 질문"
                    onSubmit={(e) => {
                        e.preventDefault();
                        const basics: Record<string, string> = {};
                        const conditions: Record<string, string> = {};
                        outcome.questions.forEach((q, i) => {
                            const value = answers[`q${i}`]?.trim();
                            if (!value) return;
                            const target = questionTarget(q);
                            if (target.kind === 'condition') conditions[target.key] = value;
                            else if (target.kind === 'basic' && basicKeyOf(q))
                                basics[basicKeyOf(q)] = value;
                        });
                        start({ answers: { basics, conditions } });
                    }}
                >
                    <p role="alert">AI가 계획을 만들려면 아래 내용이 더 필요합니다.</p>
                    {outcome.questions.map((q, i) => {
                        const target = questionTarget(q);
                        return target.kind === 'plan' ? (
                            <p key={`q${i}`} className="small">
                                {q.question} ({target.label}은 직접 입력 탭에서 보완해 주세요.)
                            </p>
                        ) : (
                            answerInput(
                                `q${i}`,
                                q.question,
                                target.kind === 'basic' ? basicKind(basicKeyOf(q)) : 'text',
                            )
                        );
                    })}
                    <button className="btn primary">이어서 진행</button>
                </form>
            )}
            {!running && outcome?.kind === 'failed' && (
                <div className="stack">
                    <p role="alert">
                        {STEP_LABEL[outcome.step]}에서 멈췄습니다. {outcome.message}
                    </p>
                    <button className="btn" type="button" onClick={() => start({})}>
                        이어서 진행
                    </button>
                </div>
            )}
            {outcome?.kind === 'done' && (
                <div className="stack">
                    <p role="status">
                        공개 준비가 끝났습니다. 직무 그룹 {outcome.summary.workGroups}개, 업무{' '}
                        {outcome.summary.tasks}개, 구역 {outcome.summary.zones}개, 공연 순서{' '}
                        {outcome.summary.programs}개
                        {outcome.summary.floorPlan ? ', 평면도 포함' : ''}입니다. 내용은 직접 입력
                        탭에서 확인하고 고칠 수 있습니다.
                    </p>
                    {outcome.summary.warning && <p role="alert">{outcome.summary.warning}</p>}
                    <EventManageRoute
                        eventId={eventId}
                        frame={ShellFrame}
                        embedded
                        hideBasics
                        onChanged={onChanged}
                    />
                    <p className="small muted">
                        공개한 뒤 <Link to="/e/home">홈</Link>의 팀원 목록에서 초대 링크를 보낼 수
                        있습니다.
                    </p>
                </div>
            )}
        </section>
    );
}
