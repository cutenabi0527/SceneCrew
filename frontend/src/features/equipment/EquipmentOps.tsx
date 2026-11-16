import { useEffect, useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import { displayTime, toLocalInput } from '../plan-workflow/time';
import { Field } from '../plan-workflow/ui';
import type { Result } from '../response/client';
import { resultText } from '../response/ui';
import type {
    Connection,
    Dependency,
    EquipmentCommands,
    EquipmentContext,
    Requirement,
    TypedRef,
} from './commands';
import { TECH_LABEL, type LiveAsset, type TechnicalState } from './live-contract';

// 총괄 장비 운영 명령 화면 부품(#308 T2). 결과 처리 규칙:
// - 완료: 알리고 현황·관계를 다시 조회한다.
// - 응답 유실(retry): 같은 내용으로만 다시 보내는 버튼을 보인다. 입력을 바꾸면 그 의도를 버리고 새로 보낸다.
// - 결과 불명·충돌·거절: 다시 보내지 않고 현재 상태를 다시 조회해 확인하게 한다.

export interface PlanRefs {
    zones: { id: string; name: string }[];
    tasks: { id: string; title: string }[];
    programs: { id: string; name: string }[];
}

type Run = <T>(key: string, work: () => Promise<Result<T>>, done: string) => Promise<boolean>;

/** 명령 하나의 진행·안내·재전송 상태 */
function useRun(commands: EquipmentCommands, onChanged: () => void) {
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [retry, setRetry] = useState<string | null>(null);
    const run: Run = async (key, work, done) => {
        if (busy) return false;
        setBusy(true);
        setMessage('');
        try {
            const result = await work();
            if (result.kind === 'done') {
                setRetry(null);
                setMessage(done);
                onChanged();
                return true;
            }
            setRetry(result.kind === 'retry' ? key : null);
            if (result.kind !== 'retry') onChanged();
            setMessage(resultText(result, '요청'));
            return false;
        } catch (error) {
            // 보내기 전 입력 검사 실패(ContractViolationError)
            setMessage(
                error instanceof Error ? error.message.replace(/^.*?: /, '') : '보내지 못했습니다.',
            );
            return false;
        } finally {
            setBusy(false);
        }
    };
    /** 입력이 바뀌었으면 남은 의도를 버린다 */
    const fresh = (key: string) => {
        if (retry === key) setRetry(null);
        commands.forget(key);
    };
    return { busy, message, retry, run, fresh };
}

const parsePorts = (text: string) =>
    text
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean)
        .map((t) => {
            const [key, kind] = t.split(':').map((x) => x.trim());
            return { key, kind: kind || '일반' };
        });

export function RegisterAsset({
    commands,
    refs,
    eventRevision,
    onChanged,
}: {
    commands: EquipmentCommands;
    refs: PlanRefs;
    eventRevision: string;
    onChanged: () => void;
}) {
    const [name, setName] = useState('');
    const [model, setModel] = useState('');
    const [ports, setPorts] = useState('');
    const [zone, setZone] = useState('');
    const [note, setNote] = useState('');
    const { busy, message, retry, run, fresh } = useRun(commands, onChanged);
    const change = (set: (v: string) => void) => (v: string) => {
        fresh('register');
        set(v);
    };
    const submit = async () => {
        const ok = await run(
            'register',
            () =>
                commands.register(
                    {
                        name,
                        model: model || null,
                        ports: parsePorts(ports),
                        storageZoneId: zone || null,
                        note,
                    },
                    eventRevision,
                ),
            `${name.trim()}을(를) 등록했습니다.`,
        );
        if (ok) {
            setName('');
            setModel('');
            setPorts('');
            setNote('');
        }
    };
    return (
        <section aria-label="장비 등록" className="card stack">
            <h2 className="h3">장비 등록</h2>
            <Field label="장비 이름" value={name} change={change(setName)} />
            <Field label="모델(선택)" value={model} change={change(setModel)} />
            <Field label="포트(예: IN-A:XLR, OUT-1:XLR)" value={ports} change={change(setPorts)} />
            <label className="small">
                보관 구역{' '}
                <select
                    value={zone}
                    onChange={(e) => {
                        fresh('register');
                        setZone(e.target.value);
                    }}
                >
                    <option value="">지정 안 함</option>
                    {refs.zones.map((z) => (
                        <option key={z.id} value={z.id}>
                            {z.name}
                        </option>
                    ))}
                </select>
            </label>
            <Field label="확인 메모(선택)" value={note} change={change(setNote)} />
            <p className="xs">
                등록하면 총괄이 직접 확인한 장비로 기록됩니다. 상태는 서버가 정합니다.
            </p>
            <button
                className="btn primary"
                type="button"
                disabled={busy || !name.trim()}
                onClick={() => void submit()}
            >
                {retry === 'register' ? '같은 내용으로 다시 보내기' : '장비 등록'}
            </button>
            {message && <p role="status">{message}</p>}
        </section>
    );
}

const STATES: TechnicalState[] = ['VERIFIED_USABLE', 'CHECK_REQUIRED', 'UNUSABLE', 'UNKNOWN'];

export function ObserveAsset({
    commands,
    asset,
    eventRevision,
    onChanged,
}: {
    commands: EquipmentCommands;
    asset: LiveAsset;
    eventRevision: string;
    onChanged: () => void;
}) {
    const [state, setState] = useState<TechnicalState>(asset.technicalState);
    const [note, setNote] = useState('');
    const key = `observe:${asset.id}`;
    const { busy, message, retry, run, fresh } = useRun(commands, onChanged);
    return (
        <section aria-label="상태 기록" className="stack">
            <b>상태 기록</b>
            <label className="small">
                확인한 상태{' '}
                <select
                    value={state}
                    onChange={(e) => {
                        fresh(key);
                        setState(e.target.value as TechnicalState);
                    }}
                >
                    {STATES.map((s) => (
                        <option key={s} value={s}>
                            {TECH_LABEL[s]}
                        </option>
                    ))}
                </select>
            </label>
            <Field
                label="관측 메모(선택)"
                value={note}
                change={(v) => {
                    fresh(key);
                    setNote(v);
                }}
            />
            <button
                className="btn"
                type="button"
                disabled={busy}
                onClick={() =>
                    void run(
                        key,
                        () => commands.observe(asset, state, note, eventRevision),
                        `${TECH_LABEL[state]}(으)로 기록했습니다.`,
                    )
                }
            >
                {retry === key ? '같은 내용으로 다시 보내기' : '상태 기록'}
            </button>
            {message && <p role="status">{message}</p>}
        </section>
    );
}

type Editing =
    | { kind: 'connections'; item: Connection | null }
    | { kind: 'requirements'; item: Requirement | null }
    | { kind: 'dependencies'; item: Dependency | null }
    | null;

const refKey = (r: TypedRef) => `${r.kind}:${r.id}`;
const parseRef = (v: string): TypedRef => {
    const [kind, id] = v.split(':');
    return { kind: kind as TypedRef['kind'], id };
};
const toLocal = toLocalInput;

export function RelationsPanel({
    commands,
    assets,
    refs,
    eventRevision,
    onChanged,
}: {
    commands: EquipmentCommands;
    assets: LiveAsset[];
    refs: PlanRefs;
    eventRevision: string;
    onChanged: () => void;
}) {
    const [ctx, setCtx] = useState<EquipmentContext | null>(null);
    const [loadMessage, setLoadMessage] = useState('장비 관계를 불러오고 있습니다.');
    const [reload, setReload] = useState(0);
    const [editing, setEditing] = useState<Editing>(null);
    const [releasing, setReleasing] = useState<string | null>(null);
    const changed = () => {
        setReload((n) => n + 1);
        onChanged();
    };
    const { busy, message, retry, run, fresh } = useRun(commands, changed);

    useEffect(() => {
        const abort = new AbortController();
        commands.context(abort.signal).then(
            (value) => {
                setCtx(value);
                setLoadMessage('');
            },
            (e: unknown) => {
                const f = classifyFailure(e);
                if (f.kind === 'cancelled') return;
                if (f.kind === 'access-denied' || f.kind === 'unauthenticated') setCtx(null);
                setLoadMessage(
                    '장비 관계를 확인하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.',
                );
            },
        );
        return () => abort.abort();
    }, [commands, reload]);

    const name = (id: string) => assets.find((a) => a.id === id)?.name ?? '다른 장비';
    const refName = (r: TypedRef) =>
        r.kind === 'TASK'
            ? `업무 ${refs.tasks.find((t) => t.id === r.id)?.title ?? '(알 수 없음)'}`
            : `프로그램 ${refs.programs.find((p) => p.id === r.id)?.name ?? '(알 수 없음)'}`;
    const release = (
        kind: 'connections' | 'requirements' | 'dependencies',
        id: string,
        revision: string | null,
    ) =>
        run(
            `${kind}:release:${id}`,
            () => commands.release(kind, id, eventRevision, revision),
            '관계를 해제했습니다.',
        ).then(() => setReleasing(null));
    const releaseButton = (
        kind: 'connections' | 'requirements' | 'dependencies',
        id: string,
        revision: string | null,
    ) =>
        releasing === id ? (
            <>
                <button
                    className="btn"
                    type="button"
                    disabled={busy}
                    onClick={() => void release(kind, id, revision)}
                >
                    {retry === `${kind}:release:${id}` ? '같은 해제 다시 보내기' : '해제 확정'}
                </button>
                <button className="btn" type="button" onClick={() => setReleasing(null)}>
                    취소
                </button>
            </>
        ) : (
            <button className="btn" type="button" disabled={busy} onClick={() => setReleasing(id)}>
                해제
            </button>
        );

    return (
        <section aria-label="장비 관계" className="card stack">
            <h2 className="h3">장비 관계</h2>
            <p className="xs">
                연결/필요 조건/선후 관계는 총괄이 확인한 사실만 기록합니다. 복구 대안 계산이 이
                관계를 씁니다.
            </p>
            {loadMessage && <p role="status">{loadMessage}</p>}
            {message && <p role="status">{message}</p>}
            {ctx && (
                <>
                    <b>연결</b>
                    <ul aria-label="연결 목록" className="small stack">
                        {ctx.connections.map((c) => (
                            <li key={c.id}>
                                {name(c.fromAssetId)}:{c.fromPort} → {name(c.toAssetId)}:{c.toPort}
                                {c.active ? '' : ' (해제됨)'} / 확인 {displayTime(c.confirmedAt)}{' '}
                                {c.active && (
                                    <>
                                        <button
                                            className="btn"
                                            type="button"
                                            onClick={() =>
                                                setEditing({ kind: 'connections', item: c })
                                            }
                                        >
                                            바꾸기
                                        </button>
                                        {releaseButton('connections', c.id, c.revision)}
                                    </>
                                )}
                            </li>
                        ))}
                        {!ctx.connections.length && <li>확인된 연결 없음</li>}
                    </ul>
                    <button
                        className="btn"
                        type="button"
                        onClick={() => setEditing({ kind: 'connections', item: null })}
                    >
                        연결 추가
                    </button>
                    <b>필요 조건</b>
                    <ul aria-label="필요 조건 목록" className="small stack">
                        {ctx.requirements.map((q) => (
                            <li key={q.id}>
                                {q.taskIds
                                    .map((t) => refName({ kind: 'TASK', id: t }))
                                    .join(', ') ||
                                    (q.programId
                                        ? refName({ kind: 'PROGRAM', id: q.programId })
                                        : '')}
                                {' / '}
                                {q.requirements
                                    .map((r) => `${r.assetIds.map(name).join('/')} ${r.quantity}개`)
                                    .join(', ')}{' '}
                                / {displayTime(q.readyBy)}까지{' '}
                                <button
                                    className="btn"
                                    type="button"
                                    onClick={() => setEditing({ kind: 'requirements', item: q })}
                                >
                                    바꾸기
                                </button>
                                {releaseButton('requirements', q.id, q.revision)}
                            </li>
                        ))}
                        {!ctx.requirements.length && <li>필요 조건 없음</li>}
                    </ul>
                    <button
                        className="btn"
                        type="button"
                        onClick={() => setEditing({ kind: 'requirements', item: null })}
                    >
                        필요 조건 추가
                    </button>
                    <b>선후 관계</b>
                    <ul aria-label="선후 관계 목록" className="small stack">
                        {ctx.dependencies.map((d) => (
                            <li key={d.id}>
                                {refName(d.from)} 끝난 뒤 {d.lagSec}초 → {refName(d.to)}{' '}
                                <button
                                    className="btn"
                                    type="button"
                                    onClick={() => setEditing({ kind: 'dependencies', item: d })}
                                >
                                    바꾸기
                                </button>
                                {releaseButton('dependencies', d.id, null)}
                            </li>
                        ))}
                        {!ctx.dependencies.length && <li>선후 관계 없음</li>}
                    </ul>
                    <button
                        className="btn"
                        type="button"
                        onClick={() => setEditing({ kind: 'dependencies', item: null })}
                    >
                        선후 관계 추가
                    </button>
                </>
            )}
            {editing && (
                <RelationForm
                    key={`${editing.kind}:${editing.item?.id ?? 'new'}`}
                    editing={editing}
                    assets={assets}
                    refs={refs}
                    busy={busy}
                    retry={retry}
                    onClose={() => setEditing(null)}
                    onSubmit={async (key, work, done) => {
                        if (await run(key, work, done)) setEditing(null);
                    }}
                    fresh={fresh}
                    commands={commands}
                    eventRevision={eventRevision}
                />
            )}
        </section>
    );
}

function RelationForm({
    editing,
    assets,
    refs,
    busy,
    retry,
    onClose,
    onSubmit,
    fresh,
    commands,
    eventRevision,
}: {
    editing: NonNullable<Editing>;
    assets: LiveAsset[];
    refs: PlanRefs;
    busy: boolean;
    retry: string | null;
    onClose: () => void;
    onSubmit: (key: string, work: () => Promise<Result<unknown>>, done: string) => Promise<void>;
    fresh: (key: string) => void;
    commands: EquipmentCommands;
    eventRevision: string;
}) {
    const item = editing.item;
    const key = item ? `${editing.kind}:${item.id}` : `${editing.kind}:create`;
    const conn = editing.kind === 'connections' ? editing.item : null;
    const req = editing.kind === 'requirements' ? editing.item : null;
    const dep = editing.kind === 'dependencies' ? editing.item : null;
    const [from, setFrom] = useState(conn?.fromAssetId ?? assets[0]?.id ?? '');
    const [fromPort, setFromPort] = useState(conn?.fromPort ?? '');
    const [to, setTo] = useState(conn?.toAssetId ?? assets[1]?.id ?? assets[0]?.id ?? '');
    const [toPort, setToPort] = useState(conn?.toPort ?? '');
    const [task, setTask] = useState(req?.taskIds[0] ?? refs.tasks[0]?.id ?? '');
    const [asset, setAsset] = useState(req?.requirements[0]?.assetIds[0] ?? assets[0]?.id ?? '');
    const [quantity, setQuantity] = useState(String(req?.requirements[0]?.quantity ?? 1));
    const [readyBy, setReadyBy] = useState(req ? toLocal(req.readyBy) : '');
    const targets = [
        ...refs.tasks.map((t) => ({ value: `TASK:${t.id}`, label: `업무 ${t.title}` })),
        ...refs.programs.map((p) => ({ value: `PROGRAM:${p.id}`, label: `프로그램 ${p.name}` })),
    ];
    const [depFrom, setDepFrom] = useState(dep ? refKey(dep.from) : (targets[0]?.value ?? ''));
    const [depTo, setDepTo] = useState(dep ? refKey(dep.to) : (targets[1]?.value ?? ''));
    const [lag, setLag] = useState(String(dep?.lagSec ?? 0));
    const [summary, setSummary] = useState('');
    const edit = (set: (v: string) => void) => (v: string) => {
        fresh(key);
        set(v);
    };
    const portsOf = (id: string) => assets.find((a) => a.id === id)?.ports ?? [];
    const select = (
        label: string,
        value: string,
        set: (v: string) => void,
        options: { value: string; label: string }[],
    ) => (
        <label className="small">
            {label}{' '}
            <select aria-label={label} value={value} onChange={(e) => edit(set)(e.target.value)}>
                <option value="">선택</option>
                {options.map((o) => (
                    <option key={o.value} value={o.value}>
                        {o.label}
                    </option>
                ))}
            </select>
        </label>
    );
    const assetOptions = assets.map((a) => ({ value: a.id, label: a.name }));
    const title = { connections: '연결', requirements: '필요 조건', dependencies: '선후 관계' }[
        editing.kind
    ];

    const submit = () => {
        if (editing.kind === 'connections')
            return onSubmit(
                key,
                () =>
                    commands.saveConnection(
                        { fromAssetId: from, fromPort, toAssetId: to, toPort, summary },
                        eventRevision,
                        conn ? { id: conn.id, revision: conn.revision } : undefined,
                    ),
                conn ? '연결을 바꿨습니다.' : '연결을 추가했습니다.',
            );
        if (editing.kind === 'requirements')
            return onSubmit(
                key,
                () =>
                    commands.saveRequirement(
                        {
                            taskIds: task ? [task] : [],
                            programId: null,
                            assetIds: asset ? [asset] : [],
                            quantity: Number(quantity),
                            readyBy: readyBy ? new Date(readyBy).toISOString() : '',
                            summary,
                        },
                        eventRevision,
                        req ? { id: req.id, revision: req.revision } : undefined,
                    ),
                req ? '필요 조건을 바꿨습니다.' : '필요 조건을 추가했습니다.',
            );
        return onSubmit(
            key,
            () =>
                commands.saveDependency(
                    { from: parseRef(depFrom), to: parseRef(depTo), lagSec: Number(lag), summary },
                    eventRevision,
                    dep ? { id: dep.id } : undefined,
                ),
            dep ? '선후 관계를 바꿨습니다.' : '선후 관계를 추가했습니다.',
        );
    };

    return (
        <section aria-label={`${title} ${item ? '바꾸기' : '추가'}`} className="stack">
            <b>
                {title} {item ? '바꾸기' : '추가'}
            </b>
            {editing.kind === 'connections' && (
                <>
                    {select('보내는 장비', from, setFrom, assetOptions)}
                    {select(
                        '보내는 포트',
                        fromPort,
                        setFromPort,
                        portsOf(from).map((p) => ({ value: p.key, label: p.key })),
                    )}
                    {select('받는 장비', to, setTo, assetOptions)}
                    {select(
                        '받는 포트',
                        toPort,
                        setToPort,
                        portsOf(to).map((p) => ({ value: p.key, label: p.key })),
                    )}
                </>
            )}
            {editing.kind === 'requirements' && (
                <>
                    {select(
                        '업무',
                        task,
                        setTask,
                        refs.tasks.map((t) => ({ value: t.id, label: t.title })),
                    )}
                    {select('필요 장비', asset, setAsset, assetOptions)}
                    <Field label="수량" value={quantity} change={edit(setQuantity)} />
                    <label className="small">
                        준비 완료 시각{' '}
                        <input
                            type="datetime-local"
                            aria-label="준비 완료 시각"
                            value={readyBy}
                            onChange={(e) => edit(setReadyBy)(e.target.value)}
                        />
                    </label>
                </>
            )}
            {editing.kind === 'dependencies' && (
                <>
                    {select('먼저 끝나는 대상', depFrom, setDepFrom, targets)}
                    {select('뒤에 시작하는 대상', depTo, setDepTo, targets)}
                    <Field label="간격(초)" value={lag} change={edit(setLag)} />
                </>
            )}
            <Field label="확인 내용" value={summary} change={edit(setSummary)} />
            <div className="row">
                <button
                    className="btn primary"
                    type="button"
                    disabled={busy}
                    onClick={() => void submit()}
                >
                    {retry === key ? '같은 내용으로 다시 보내기' : '저장'}
                </button>
                <button className="btn" type="button" onClick={onClose}>
                    닫기
                </button>
            </div>
        </section>
    );
}
