import { displayTime } from './time';
import { useEffect, useState } from 'react';
import { useInvalidation } from '../../shared/live/invalidation';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { useLeaveProtection } from '../../shared/navigation/leave-guard-context';
import { EventAccess } from './Access';
import type { Workflow } from './client';
import type { Task } from './contract';
import { useWorkAction } from './action';
import { TaskContext } from './TaskContext';

const labels = { WAITING: '시작 전', IN_PROGRESS: '수행 중', DONE: '완료', CANCELED: '취소됨' };
export function WorkTasks() {
    return <EventAccess>{(client) => <Tasks client={client} />}</EventAccess>;
}
function Tasks({ client }: { client: Workflow }) {
    const [items, setItems] = useState<Task[]>([]),
        [cursor, setCursor] = useState<string | null>(null),
        [selected, setSelected] = useState<Task | null>(null);
    const [revision, setRevision] = useState('0');
    const [checks, setChecks] = useState<Record<string, boolean>>({});
    const [dirty, setDirty] = useState(false);
    const action = useWorkAction();
    const { setMessage } = action;
    useLeaveProtection(dirty, 'unsaved');
    const [liveTick, setLiveTick] = useState(0);
    // 서버가 업무·공연 변경을 알리면 다시 조회한다. 점검 항목을 고치는 중이면 입력을 지우지 않게 기다린다.
    useInvalidation(client.session.api, client.scope, ['TASKS', 'EVENT'], () => {
        if (!dirty) setLiveTick((n) => n + 1);
    });
    const load = async () => {
        const event = await client.session.event(client.scope.eventId);
        const page = await client.tasks();
        return { revision: event.revision, page };
    };
    useEffect(() => {
        let active = true;
        const read = async () => {
            const event = await client.session.event(client.scope.eventId);
            return { revision: event.revision, page: await client.tasks() };
        };
        read()
            .then((v) => {
                if (active) {
                    setItems(v.page.items.filter((t) => t.assigneeId === client.scope.memberId));
                    setCursor(v.page.nextCursor);
                    setRevision(v.revision);
                }
            })
            .catch(() => {
                if (active) setMessage('내 업무를 확인하지 못했습니다. 다시 조회해주세요.');
            });
        return () => {
            active = false;
        };
    }, [client, setMessage, liveTick]);
    const select = (t: Task) => {
        setSelected(t);
        setChecks(Object.fromEntries(t.checklist.map((c) => [c.key, c.checked])));
        setDirty(false);
    };
    const perform = (op: 'start' | 'complete' | 'checklist') =>
        action.run(
            () =>
                client.perform(
                    selected!,
                    op,
                    revision,
                    op === 'checklist'
                        ? selected!.checklist.map((c) => ({
                              key: c.key,
                              checked: checks[c.key] ?? c.checked,
                          }))
                        : undefined,
                ),
            (v) => {
                // 시작·완료 응답(Task)에는 장비·안전 정보가 없다. 단건 조회에서 받은 값을 유지한다.
                const next = {
                    ...v.task,
                    equipment: selected!.equipment,
                    safety: selected!.safety,
                };
                setItems(items.map((t) => (t.id === v.task.id ? v.task : t)));
                setRevision(v.eventRevision);
                select(next);
                action.setMessage('서버의 업무 상태를 반영했습니다.');
            },
        );
    return (
        <EntryFrame title="내 업무">
            <Link to={`/account/events/${client.scope.eventId}/plan`}>공통 공연 계획</Link>
            <p role="status">{action.message}</p>
            <button
                className="btn"
                disabled={action.busy}
                onClick={() => {
                    void action.run(load, (v) => {
                        setItems(
                            v.page.items.filter((t) => t.assigneeId === client.scope.memberId),
                        );
                        setCursor(v.page.nextCursor);
                        setRevision(v.revision);
                        setSelected(null);
                        setDirty(false);
                    });
                }}
            >
                {dirty ? '변경 취소하고 내 업무 다시 조회' : '내 업무 새로고침'}
            </button>
            {!items.length && <p>현재 배정된 업무가 없습니다.</p>}
            {items.map((t) => (
                <button
                    className="btn block"
                    key={t.id}
                    disabled={action.busy || dirty}
                    onClick={() => {
                        void action.run(
                            () => client.task(t.id),
                            (value) => {
                                if (value.assigneeId === client.scope.memberId) select(value);
                                else {
                                    setSelected(null);
                                    action.setMessage('현재 본인에게 배정된 업무가 아닙니다.');
                                }
                            },
                        );
                    }}
                >
                    {t.title} / {labels[t.status]}
                </button>
            ))}
            {cursor && (
                <button
                    className="btn"
                    disabled={action.busy || dirty}
                    onClick={() => {
                        void action.run(
                            () => client.tasks(cursor),
                            (v) => {
                                setItems([
                                    ...items,
                                    ...v.items.filter(
                                        (t) => t.assigneeId === client.scope.memberId,
                                    ),
                                ]);
                                setCursor(v.nextCursor);
                            },
                        );
                    }}
                >
                    다음 업무 보기
                </button>
            )}
            {selected && (
                <section className="card stack">
                    <h2>{selected.title}</h2>
                    <p>
                        {labels[selected.status]} / {displayTime(selected.startAt)} ~{' '}
                        {displayTime(selected.endAt)}
                    </p>
                    <ol>
                        {selected.steps.map((s, i) => (
                            <li key={i}>{s}</li>
                        ))}
                    </ol>
                    <TaskContext
                        client={client}
                        task={selected}
                        revision={revision}
                        onRevision={setRevision}
                    />
                    <fieldset
                        disabled={
                            action.busy ||
                            selected.status === 'DONE' ||
                            selected.status === 'CANCELED' ||
                            client.pending(`task:${selected.id}`)
                        }
                    >
                        {selected.checklist.map((c) => (
                            <label className="check" key={c.key}>
                                <input
                                    type="checkbox"
                                    checked={checks[c.key] ?? c.checked}
                                    onChange={(e) => {
                                        setChecks({ ...checks, [c.key]: e.target.checked });
                                        setDirty(true);
                                    }}
                                />{' '}
                                {c.text}
                                {c.required && ' (필수)'}
                            </label>
                        ))}
                        <button
                            className="btn"
                            disabled={!dirty}
                            onClick={() => {
                                void perform('checklist');
                            }}
                        >
                            체크리스트 저장
                        </button>
                        <button
                            className="btn"
                            disabled={dirty || selected.status !== 'WAITING'}
                            onClick={() => {
                                void perform('start');
                            }}
                        >
                            업무 시작
                        </button>
                        <button
                            className="btn primary"
                            disabled={
                                dirty ||
                                selected.status !== 'IN_PROGRESS' ||
                                selected.checklist.some((c) => c.required && !c.checked)
                            }
                            onClick={() => {
                                void perform('complete');
                            }}
                        >
                            업무 완료
                        </button>
                    </fieldset>
                </section>
            )}
        </EntryFrame>
    );
}
