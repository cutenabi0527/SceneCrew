import { createPortal } from 'react-dom';
import { useCallback, useEffect, useId, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure, type Failure } from '../../shared/api/failure';
import { ContractViolationError, asRecord, asUuid } from '../../shared/api/contract';
import { checkV9Shape } from '../../shared/api/v9';
import { useLeaveProtection } from '../../shared/navigation/leave-guard-context';
import { observe } from '../identity/observe';
import { EventAccess } from '../plan-workflow/Access';
import type { Frame } from '../plan-workflow/frame';
import { CommandNeedsReview, type Workflow } from '../plan-workflow/client';
import type { EventDetail, Task } from '../plan-workflow/contract';
import { MemberDesk } from '../members/client';
import type { Member } from '../members/contract';
import { unassignedBundles } from './bundles';
import { BASIC_FIELDS, STATE_LABEL, incompleteLine, type BasicKey } from './labels';
import { toLocalInput } from './time';
import { prefillEdits } from './prefill';
import { publishChecklist } from './publish-check';
import {
    BASIC_ROWS,
    FIELD_LIMIT,
    TIME_ONLY,
    example,
    formatHint,
    isDate,
    localTime,
    shownTime,
} from './basic-form';
import { Icon } from '../../shared/ui/Icon';
import { Dialog } from '../../shared/ui/Dialog';
import report from '../plan-workflow/report.module.css';
import { ReportTools } from '../plan-workflow/ReportTools';
import { basicsPatch } from './patch';

/** 업무 시각을 한국 시간 "M. D. HH:mm"으로 보여 준다(여러 날 묶음도 구분되게 날짜 포함). */
const kst = (iso: string) =>
    new Date(iso).toLocaleString('ko-KR', {
        timeZone: 'Asia/Seoul',
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
    });

// 공연 관리(#286): 총괄이 기본정보를 채우고 공개·시작·종료하며, 공개 뒤 묶음을 팀원에게 처음 배정한다(#344).
// 계획(/plan)·업무 목록 API가 아직 없어 이 화면은 행사 조회(023)만 읽는다.

type Detail = EventDetail & Partial<Record<BasicKey, string | null>>;

function failureOf(error: unknown): Failure {
    if (error instanceof CommandNeedsReview && error.outcome.status === 'failed')
        return error.outcome.failure;
    return classifyFailure(error);
}

function messageOf(error: unknown): string {
    if (error instanceof CommandNeedsReview && error.outcome.status === 'reconcile')
        return '이전 요청의 결과를 아직 모릅니다. 새로고침으로 현재 상태를 확인한 뒤 다시 시도해주세요.';
    if (error instanceof ContractViolationError) return '입력 형식을 확인해주세요.';
    const f = failureOf(error);
    if (f.kind === 'cancelled') return '';
    if (f.kind === 'version-conflict')
        return '다른 변경이 먼저 반영되었습니다. 새로고침한 뒤 다시 시도해주세요. 입력은 유지했습니다.';
    if (f.kind === 'access-denied') return '총괄만 할 수 있습니다. 현재 역할을 확인해주세요.';
    if (f.kind === 'rejected' && f.message) return f.message;
    if (f.intent === 'pending')
        return '처리 결과를 확인하지 못했습니다. 새로고침으로 현재 상태를 확인해주세요. 자동으로 다시 보내지 않습니다.';
    return '요청을 처리하지 못했습니다. 입력과 현재 상태를 확인해주세요.';
}

/**
 * 공연 관리. 기능 화면은 주소의 공연으로 열고, 디자인 화면의 W01-DRAFT(#362)는 eventId·셸 틀을 넘겨
 * embedded로 연다. onChanged는 공개·시작·종료·기본정보 저장 뒤 불린다(운영 화면이 다시 읽는다).
 */
export function EventManageRoute({
    eventId,
    frame = EntryFrame,
    embedded = false,
    onChanged,
    prefill,
    refresh,
    planEditing = false,
    toolsSlot,
    action,
    hideBasics = false,
}: {
    eventId?: string;
    /** 버튼 하나. embedded(보고서 틀)에서는 위 고정 바 왼쪽 끝, 그 밖에서는 제목 줄 오른쪽에 둔다(W01-DRAFT의 상세 정보 업로드) */
    action?: ReactNode;
    frame?: Frame;
    embedded?: boolean;
    onChanged?: () => void;
    /** 문서 추출로 채울 기본정보 후보(#362). 바뀔 때마다 편집 칸에 채우며 저장은 하지 않는다. */
    prefill?: Record<string, string> | null;
    /** 값이 바뀌면 편집 중인 기본정보는 두고 공연을 다시 읽는다(계획 작업 부분의 저장 뒤, #268) */
    refresh?: number;
    /** 같은 화면의 계획 작업 부분에 저장하지 않은 편집이 있다(공개를 막는다, #362) */
    planEditing?: boolean;
    /** 주면 형광펜·새로고침(과 진행 안내)을 카드 안이 아니라 이 자리(등록 화면 탭 줄 오른쪽)에 그린다 */
    toolsSlot?: HTMLElement | null;
    /** PDF 탭(V9-D65): 기본정보는 자동 진행이 저장했으므로 편집 칸을 숨기고 공개·진행만 보인다 */
    hideBasics?: boolean;
} = {}) {
    return (
        <EventAccess eventId={eventId} frame={frame}>
            {(client) => (
                <EventManage
                    client={client}
                    frame={frame}
                    embedded={embedded}
                    onChanged={onChanged}
                    prefill={prefill ?? null}
                    refresh={refresh ?? 0}
                    planEditing={planEditing}
                    toolsSlot={toolsSlot}
                    action={action}
                    hideBasics={hideBasics}
                />
            )}
        </EventAccess>
    );
}

function EventManage({
    client,
    frame: Frame,
    embedded,
    onChanged,
    prefill,
    refresh,
    planEditing,
    action,
    toolsSlot,
    hideBasics,
}: {
    client: Workflow;
    frame: Frame;
    embedded: boolean;
    onChanged?: () => void;
    prefill: Record<string, string> | null;
    refresh: number;
    planEditing: boolean;
    action?: ReactNode;
    toolsSlot?: HTMLElement | null;
    hideBasics: boolean;
}) {
    const [event, setEvent] = useState<Detail | null>(null);
    const [edits, setEdits] = useState<Partial<Record<BasicKey, string>>>({});
    const [busy, setBusy] = useState(false);
    // 새로고침 버튼으로 다시 읽는 중(카드 위에 반투명 층과 '새로고침 중' 안내를 띄운다)
    const [refreshing, setRefreshing] = useState(false);
    const [message, setMessage] = useState('');
    const [incomplete, setIncomplete] = useState<{ field: string; reason: string }[]>([]);
    const [confirm, setConfirm] = useState<'publish' | 'start' | 'end' | null>(null);
    const [reason, setReason] = useState('');
    const basicsFormId = useId();
    useLeaveProtection(Object.keys(edits).length > 0, 'unsaved');
    // 추출 후보를 편집 칸에 채운다. 시각은 입력 칸 형식(이 기기 시간대)으로, 날짜는 앞 10자로 바꾼다.
    // 시각·날짜로 읽을 수 없는 값은 채우지 않는다(추출 결과에 원문이 남아 있어 직접 입력한다).
    // 새 후보가 오면 그 렌더에서 바로 편집 칸을 채운다(React 권장: 이전 값과 비교해 상태 조정).
    const [seenPrefill, setSeenPrefill] = useState(prefill);
    if (prefill !== seenPrefill) {
        setSeenPrefill(prefill);
        // 직접 고친 칸은 덮어쓰지 않는다(#362 리뷰)
        if (prefill) setEdits((old) => ({ ...prefillEdits(prefill), ...old }));
    }

    const reload = useCallback(async () => {
        setEvent((await client.detail()) as Detail);
    }, [client]);
    // 같은 화면의 계획 작업 부분이 저장하면 공연(revision)만 다시 읽는다. 편집 중인 기본정보는 남긴다.
    // 다시 읽기가 끝나기 전에는 옛 revision으로 공개·시작·종료하지 않게 진행 버튼을 잠근다.
    const [synced, setSynced] = useState(refresh);
    const syncing = refresh !== synced;
    useEffect(() => {
        if (!refresh) return;
        return observe(
            () => client.detail(),
            (value) => {
                setEvent(value as Detail);
                setSynced(refresh);
            },
            (error) => {
                setMessage(messageOf(error));
                setSynced(refresh);
            },
        );
    }, [client, refresh]);
    useEffect(
        () =>
            observe(
                () => client.detail(),
                (value) => setEvent(value as Detail),
                (error) => setMessage(messageOf(error)),
            ),
        [client],
    );

    // 공개 조건 안내용 계획 개수. 상세 정보 팝업이 저장하면(refresh) 다시 읽는다. 못 읽으면 계획 조건은 띄우지 않는다
    const [planCounts, setPlanCounts] = useState<{ workGroups: number; tasks: number } | null>(
        null,
    );
    useEffect(
        () =>
            observe(
                () => client.planCounts(),
                setPlanCounts,
                () => setPlanCounts(null),
            ),
        [client, refresh],
    );
    // 입력을 고치거나 계획이 다시 저장되면 지난 공개 거절(422) 항목은 지운다.
    // 남은 조건은 화면 미리 검사가 다시 보여 주므로, 공개할 수 있게 되면 노란 카드가 사라진다
    const [seenInput, setSeenInput] = useState({ edits, refresh });
    if (seenInput.edits !== edits || seenInput.refresh !== refresh) {
        setSeenInput({ edits, refresh });
        if (incomplete.length) setIncomplete([]);
    }

    async function run(work: () => Promise<unknown>, done: string) {
        if (busy) return;
        setBusy(true);
        setMessage('');
        setIncomplete([]);
        try {
            const next = (await work()) as Detail;
            setEvent(next);
            setConfirm(null);
            setMessage(done);
            onChanged?.();
        } catch (error) {
            const f = failureOf(error);
            if (f.code === 'EVENT_INCOMPLETE' && f.fields?.length) setIncomplete(f.fields);
            setMessage(messageOf(error));
        } finally {
            setConfirm(null);
            setBusy(false);
        }
    }

    if (!event)
        return (
            <Frame title="공연 관리">
                <p role="status">{message || '공연 정보를 불러오고 있습니다.'}</p>
            </Frame>
        );
    const chief = event.myParticipation.eventRole === 'CHIEF';
    const draft = event.state === 'DRAFT';
    const value = (key: BasicKey, kind: string) => {
        if (edits[key] !== undefined) return edits[key]!;
        const raw = event[key] ?? '';
        return kind === 'time' ? toLocalInput(raw) : String(raw);
    };

    // 보고서 틀(embedded)의 날짜·시각 글자 칸: 칸에 보일 글자와 보낼 값(YYYY-MM-DDTHH:mm, 틀리면 null)
    const dateText = value('date', 'date');
    const shown = (key: BasicKey, kind: string) =>
        embedded && kind === 'time' ? shownTime(key, value(key, kind)) : value(key, kind);
    const local = (key: BasicKey, kind: string): string | null => {
        if (!embedded) return value(key, kind);
        if (kind === 'date') return !dateText.trim() || isDate(dateText) ? dateText.trim() : null;
        if (kind === 'time') return localTime(key, value(key, kind), dateText);
        return value(key, kind);
    };

    // 고친 칸만 024 본문으로 바꾼다(PDF 탭 자동 진행과 같은 규칙, patch.ts).
    // 보고서 틀의 글자 칸 값은 먼저 입력 칸 형식(YYYY-MM-DDTHH:mm)으로 맞추고, 형식이 틀리면 보내지 않는다.
    function changes(): Record<string, string | null> {
        const normalized: Partial<Record<BasicKey, string>> = {};
        for (const f of BASIC_FIELDS) {
            // 시각만 받는 칸은 공연 날짜가 바뀌면 새 날짜로 다시 보낸다
            const follows =
                embedded &&
                TIME_ONLY.includes(f.key) &&
                edits.date !== undefined &&
                !!shown(f.key, f.kind).trim();
            if (edits[f.key] === undefined && !follows) continue;
            const v = embedded ? local(f.key, f.kind) : (edits[f.key] ?? '');
            if (v === null)
                throw new ContractViolationError(
                    f.key,
                    `${f.label} 형식이 올바르지 않습니다(${formatHint(f.key)}).`,
                );
            normalized[f.key] = v;
        }
        return basicsPatch(normalized);
    }

    const unsaved = Object.keys(edits).length > 0;
    // 기본정보 저장. 보고서 틀(embedded)에서는 입력 칸 맨 아래에 '임시 저장'으로 둔다
    // (공개 전까지 몇 번이든 부분 저장할 수 있다)
    const saveButton = chief && draft && (
        <button
            type="submit"
            form={basicsFormId}
            className="btn"
            disabled={busy || syncing || !unsaved}
        >
            {embedded ? '임시 저장' : '기본정보 저장'}
        </button>
    );
    // 저장하지 않은 편집이 있으면 공개를 막는다. 보고서 틀은 안내문을 따로 두지 않아 버튼 설명으로만 알린다
    const publishBlocked = unsaved || planEditing;
    const publishButton = chief && draft && (
        <button
            className="btn primary"
            type="button"
            title={
                embedded && publishBlocked ? '저장하지 않은 내용을 먼저 저장해주세요' : undefined
            }
            disabled={busy || syncing || publishBlocked}
            onClick={() => setConfirm('publish')}
        >
            공연 공개
        </button>
    );
    // 공개 버튼 아래 노란 안내 카드: 아직 채우지 못한 공개 조건(화면 미리 검사)과 마지막 공개 거절(422) 항목
    const checklist = [
        ...publishChecklist({
            values: Object.fromEntries(
                BASIC_FIELDS.map((f) => [f.key, local(f.key, f.kind) ?? '']),
            ) as Record<BasicKey, string>,
            unsaved,
            planEditing,
            plan: planCounts,
            invalid: BASIC_FIELDS.filter((f) => local(f.key, f.kind) === null).map((f) => ({
                key: f.key,
                hint: formatHint(f.key),
            })),
        }),
        ...incomplete.map(incompleteLine),
    ].filter((line, i, all) => all.indexOf(line) === i);
    // 공개는 아래 팝업(publishDialog)으로 확인받는다. 시작·종료만 이 확인 칸을 쓴다
    const confirmBox = chief && confirm && confirm !== 'publish' && (
        <div className="card stack" role="alertdialog" aria-label="진행 확인">
            <p>
                {confirm === 'start' && '공연을 시작할까요? 되돌릴 수 없습니다.'}
                {confirm === 'end' &&
                    '공연을 종료할까요? 종료하면 업무 쓰기가 닫히고 되돌릴 수 없습니다.'}
            </p>
            {confirm === 'end' && (
                <div className="field">
                    <label htmlFor="end-reason">종료 사유(선택)</label>
                    <input
                        id="end-reason"
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                    />
                </div>
            )}
            <div className="row">
                <button
                    className="btn primary"
                    type="button"
                    disabled={busy || syncing}
                    onClick={() => {
                        if (confirm === 'start')
                            void run(() => client.start(event.revision), '공연을 시작했습니다.');
                        else
                            void run(
                                () => client.end(reason, event.revision),
                                '공연을 종료했습니다.',
                            );
                    }}
                >
                    확인
                </button>
                <button
                    className="btn"
                    type="button"
                    disabled={busy}
                    onClick={() => setConfirm(null)}
                >
                    취소
                </button>
            </div>
        </div>
    );
    // 공개 전 경고 팝업: 참여 모집이 시작되면 날짜·시각·장소를 고칠 수 없다는 것을 알리고 한 번 더 확인받는다
    const publishDialog = chief && confirm === 'publish' && (
        <Dialog title="공연을 공개할까요?" onClose={() => !busy && setConfirm(null)}>
            <div className="stack">
                <p role="alert">
                    공개하면 참여 모집이 시작되고, 그 뒤에는 공연 날짜·시각·장소를 수정할 수
                    없습니다. 공개는 되돌릴 수 없습니다.
                </p>
                <div className="row">
                    <button
                        className="btn primary"
                        type="button"
                        disabled={busy || syncing}
                        onClick={() =>
                            void run(() => client.publish(event.revision), '공연을 공개했습니다.')
                        }
                    >
                        공개 확정
                    </button>
                    <button
                        className="btn"
                        type="button"
                        disabled={busy}
                        onClick={() => setConfirm(null)}
                    >
                        취소
                    </button>
                </div>
            </div>
        </Dialog>
    );
    // 공연과 공개 조건용 계획 개수를 서버에서 다시 읽는다. 입력 중인 칸은 그대로 둔다.
    // 너무 빨리 끝나도 다시 읽었다는 걸 알 수 있게 안내를 잠깐(0.4초 이상) 보인다.
    async function refreshAll() {
        if (refreshing) return;
        setMessage('');
        setRefreshing(true);
        try {
            await Promise.all([
                reload(),
                client
                    .planCounts()
                    .then(setPlanCounts)
                    .catch(() => setPlanCounts(null)),
                new Promise((done) => setTimeout(done, 400)),
            ]);
            setMessage('최신 정보로 새로고침했습니다.');
        } catch (error) {
            setMessage(messageOf(error));
        } finally {
            setRefreshing(false);
        }
    }
    const refreshButton = (
        <button
            className={embedded ? report.barIcon : 'btn'}
            type="button"
            aria-label={embedded ? '새로고침' : undefined}
            title={embedded ? '새로고침' : undefined}
            disabled={busy || refreshing}
            onClick={() => void refreshAll()}
        >
            {embedded ? <Icon name="refresh" /> : '새로고침'}
        </button>
    );
    // 보고서 틀(embedded)에서는 위 고정 바 없이, 기본정보 첫 줄(주소 칸) 바로 위 오른쪽에 형광펜·새로고침을 두고
    // 왼쪽에 진행 안내를 둔다. 임시 저장·공개는 기본정보 입력 칸 맨 아래(희망 예비 인원 밑) 가운데에 나란히 둔다
    const tools = embedded && (
        <div className={report.formTools}>
            <p role="status">{message}</p>
            <ReportTools />
            {refreshButton}
        </div>
    );
    // 새로고침 중에는 카드 전체를 반투명 층으로 덮고 가운데에 안내한다
    const refreshLayer = embedded && refreshing && (
        <div className={report.refreshLayer} role="status" aria-live="polite">
            <span className={report.refreshBadge}>
                <span className={report.spinner} aria-hidden="true" />
                새로고침 중…
            </span>
        </div>
    );

    // 기본정보 칸 하나. 보고서 틀에서는 날짜·시각을 회색 예시가 있는 글자 칸으로, 한도는 제목 줄 오른쪽에 둔다
    function basicField(key: BasicKey) {
        const f = BASIC_FIELDS.find((x) => x.key === key)!;
        const id = `basic-${f.key}`;
        const onChange = (e: { target: { value: string } }) =>
            setEdits({ ...edits, [f.key]: e.target.value });
        const textual = embedded && (f.kind === 'date' || f.kind === 'time');
        const limit = embedded ? FIELD_LIMIT[f.key] : undefined;
        // 한도를 제목 줄 오른쪽에 따로 보이므로 제목의 괄호 범위(예: 정원 1~31)는 뺀다
        const label = limit ? f.label.replace(/\(.*\)$/, '') : f.label;
        const type = textual
            ? 'text'
            : f.kind === 'time'
              ? 'datetime-local'
              : f.kind === 'date'
                ? 'date'
                : 'text';
        return (
            <div className="field" key={f.key}>
                {limit ? (
                    <div className={report.fieldHead}>
                        <label htmlFor={id}>{label}</label>
                        <span className={report.fieldHint}>{limit}</span>
                    </div>
                ) : (
                    <label htmlFor={id}>{label}</label>
                )}
                {f.kind === 'textarea' ? (
                    <textarea id={id} value={shown(f.key, f.kind)} onChange={onChange} />
                ) : (
                    <input
                        id={id}
                        type={type}
                        inputMode={f.kind === 'number' ? 'numeric' : undefined}
                        placeholder={textual ? example(f.key, dateText) : undefined}
                        autoComplete={textual ? 'off' : undefined}
                        maxLength={f.key === 'name' ? 20 : undefined}
                        value={shown(f.key, f.kind)}
                        onChange={onChange}
                    />
                )}
            </div>
        );
    }

    return (
        <Frame
            title={embedded ? event.name : `${event.name} / 공연 관리`}
            sub={`상태: ${STATE_LABEL[event.state]}`}
            action={embedded ? undefined : action}
        >
            {refreshLayer}
            {/* 기본정보 첫 줄 바로 위(공개 뒤 기본정보 칸이 닫혀도 안내·새로고침은 남는다) */}
            {toolsSlot && tools ? createPortal(tools, toolsSlot) : tools}
            {!chief && <p role="status">공연 관리는 총괄만 할 수 있습니다.</p>}
            {chief && draft && !hideBasics && (
                <form
                    id={basicsFormId}
                    className="stack"
                    aria-label="공연 기본정보"
                    onSubmit={(e) => {
                        e.preventDefault();
                        void run(async () => {
                            const next = await client.basics(changes(), event.revision);
                            setEdits({});
                            return next;
                        }, '기본정보를 저장했습니다.');
                    }}
                >
                    {!embedded && <h2 className="h3">기본정보</h2>}
                    {embedded
                        ? BASIC_ROWS.map((row) => (
                              <div
                                  key={row.keys.join('-')}
                                  className={report.fieldRow}
                                  style={{
                                      gridTemplateColumns: row.ratio
                                          .map((r) => `minmax(0, ${r}fr)`)
                                          .join(' '),
                                  }}
                              >
                                  {row.keys.map((key) => basicField(key))}
                              </div>
                          ))
                        : BASIC_FIELDS.map((f) => basicField(f.key))}
                    {!embedded && (
                        <p className="small">
                            시각은 이 기기의 시간대로 입력합니다. 공개 전까지 언제든 고칠 수
                            있습니다.
                        </p>
                    )}
                    {!embedded && saveButton}
                    {embedded && (
                        <div className={report.formActions}>
                            {saveButton}
                            {publishButton}
                        </div>
                    )}
                    {embedded && checklist.length > 0 && (
                        <div className={report.publishNote} role="note" aria-label="공개 조건">
                            <b>공개하려면 아래를 채워주세요</b>
                            <ul>
                                {checklist.map((line) => (
                                    <li key={line}>{line}</li>
                                ))}
                            </ul>
                        </div>
                    )}
                </form>
            )}
            {chief && (
                <section className="stack" aria-label="공연 진행">
                    {!embedded && <h2 className="h3">공연 진행</h2>}
                    {!embedded && draft && (
                        <p className="small">
                            공개하면 참여 모집이 시작되고 기본 날짜/시각/장소는 더 고칠 수 없습니다.
                            공개하려면 기본정보가 모두 채워져 있고, 활성 직무 그룹과 업무가 1개 이상
                            있어야 합니다.
                        </p>
                    )}
                    {!embedded && draft && unsaved && (
                        <p className="small">
                            저장하지 않은 기본정보가 있습니다. 먼저 저장해주세요.
                        </p>
                    )}
                    {!embedded && draft && planEditing && (
                        <p className="small">
                            위 계획 초안/조건에 저장하지 않은 내용이 있습니다. 먼저 저장해주세요.
                        </p>
                    )}
                    {/* 보고서 틀에서는 공개 버튼이 기본정보 칸 아래에 있다. 기본정보 칸을 숨기면(PDF 탭) 여기 둔다 */}
                    {(!embedded || hideBasics) && publishButton}
                    {event.state === 'RECRUITING' && (
                        <button
                            className="btn primary"
                            type="button"
                            disabled={busy || syncing}
                            onClick={() => setConfirm('start')}
                        >
                            공연 시작(운영 중으로)
                        </button>
                    )}
                    {(event.state === 'RECRUITING' || event.state === 'LIVE') && (
                        <button
                            className="btn"
                            type="button"
                            disabled={busy || syncing}
                            onClick={() => setConfirm('end')}
                        >
                            공연 종료
                        </button>
                    )}
                    {event.state === 'ENDED' && (
                        <p>종료된 공연입니다. 기록 조회와 종료 요약만 할 수 있습니다.</p>
                    )}
                    {confirmBox}
                </section>
            )}
            {!embedded && incomplete.length > 0 && (
                <section className="stack" aria-label="공개하려면 고칠 항목">
                    <h2 className="h3">공개하려면 고칠 항목</h2>
                    <ul>
                        {incomplete.map((item, i) => (
                            <li key={`${item.field}-${item.reason}-${i}`}>
                                {incompleteLine(item)}
                            </li>
                        ))}
                    </ul>
                </section>
            )}
            {chief && (event.state === 'RECRUITING' || event.state === 'LIVE') && (
                <InitialAssignment
                    client={client}
                    revision={event.revision}
                    onApplied={() => {
                        void reload();
                        onChanged?.();
                    }}
                />
            )}
            {!embedded && (
                <>
                    <p role="status">{message}</p>
                    {refreshButton}
                </>
            )}
            {publishDialog}
            {!embedded && <Link to={`/account/events/${event.id}`}>공연 참여 화면</Link>}
        </Frame>
    );
}

interface AssignedTask {
    id: string;
    title: string;
    bundleKey: string;
    startAt: string;
    endAt: string;
}

function parseApplied(data: unknown, memberId: string) {
    checkV9Shape('InitialAssignmentApplied', data);
    const v = asRecord(data, 'initialAssignment');
    const tasks = (v['tasks'] as unknown[]).map((item) => {
        const t = asRecord(item, 'task');
        if (t['assigneeId'] !== memberId)
            throw new ContractViolationError('assigneeId', '고른 팀원에게 배정되어야 합니다.');
        return {
            id: asUuid(t['id'], 'task.id'),
            title: String(t['title']),
            bundleKey: String(t['bundleKey']),
            startAt: String(t['startAt']),
            endAt: String(t['endAt']),
        } satisfies AssignedTask;
    });
    return { tasks, eventRevision: String(v['eventRevision']) };
}

/** 045 수동 최초 배정(#344). 업무 목록(GET /events/{e}/tasks)에서 아직 배정되지 않은 묶음을 고른다(#354, V9-D47). */
function InitialAssignment({
    client,
    revision,
    onApplied,
}: {
    client: Workflow;
    revision: string;
    onApplied: () => void;
}) {
    const [members, setMembers] = useState<Member[] | null>(null);
    const [memberId, setMemberId] = useState('');
    const [bundleKey, setBundleKey] = useState('');
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [assigned, setAssigned] = useState<AssignedTask[]>([]);
    // 결과를 모르는 요청. 같은 키로 다시 보낼 때 처음 본문(revision 포함)을 그대로 쓴다.
    const [intent, setIntent] = useState<{
        key: string;
        body: { bundleKey: string; memberId: string; expectedEventRevision: string };
    } | null>(null);
    const [tasks, setTasks] = useState<Task[] | null>(null);
    const [loadCount, setLoadCount] = useState(0);
    useEffect(() => {
        const desk = new MemberDesk(client.session, client.scope);
        desk.members()
            .then((page) => setMembers(page.items))
            .catch((error: unknown) => setMessage(messageOf(error)));
    }, [client, loadCount]);
    useEffect(() => {
        let alive = true;
        // 총괄은 행사 전체 업무를 본다. 다음 쪽이 있으면 끝까지 읽어 묶음이 잘리지 않게 한다.
        (async () => {
            const all: Task[] = [];
            const seen = new Set<string>();
            let cursor: string | undefined;
            do {
                const page = await client.tasks(cursor);
                all.push(...page.items);
                cursor = page.nextCursor ?? undefined;
                // 같은 커서가 다시 오면 무한히 읽지 않는다
                if (cursor && seen.has(cursor))
                    throw new Error('업무 목록을 끝까지 읽지 못했습니다. 새로고침해주세요.');
                if (cursor) seen.add(cursor);
            } while (cursor);
            return all;
        })()
            .then((all) => {
                if (alive) setTasks(all);
            })
            .catch((error: unknown) => {
                if (alive) setMessage(messageOf(error));
            });
        return () => {
            alive = false;
        };
    }, [client, loadCount]);
    const bundles = unassignedBundles(tasks ?? []);
    const bundle = bundles.find((b) => b.bundleKey === bundleKey) ?? null;
    // 서버는 묶음과 같은 그룹의 팀원에게만 배정한다. 다른 그룹 팀원은 고를 수 없게 뺀다.
    const candidates = (members ?? []).filter(
        (m) =>
            m.active &&
            m.available &&
            !m.isReserve &&
            m.eventRole === 'WORKER' &&
            m.workGroupId &&
            (!bundle || m.workGroupId === bundle.workGroupId),
    );
    return (
        <form
            className="stack"
            aria-label="최초 업무 배정"
            onSubmit={(e) => {
                e.preventDefault();
                if (busy || !memberId || !bundle) return;
                setBusy(true);
                setMessage('');
                // 결과를 모르는 요청이 남아 있으면 같은 키로만 다시 보낸다(서버가 최초 응답을 돌려준다).
                const sent = intent ?? {
                    key: crypto.randomUUID(),
                    body: { bundleKey, memberId, expectedEventRevision: revision },
                };
                const key = sent.key;
                setIntent(sent);
                client.session.api
                    .command(
                        client.scope,
                        {
                            operation: `initial-assignment:${key}`,
                            intentId: key,
                            method: 'POST',
                            path: `/events/${client.scope.eventId}/initial-assignments`,
                            body: sent.body,
                            recovery: 'same-key',
                        },
                        (d) => parseApplied(d, sent.body.memberId),
                    )
                    .then((result) => {
                        if (result.status === 'succeeded' && result.data) {
                            setIntent(null);
                            setAssigned(result.data.tasks);
                            setMessage(
                                `업무 ${result.data.tasks.length}건을 배정했습니다. 팀원에게 첫 배정 알림이 갑니다.`,
                            );
                            setBundleKey('');
                            setMemberId('');
                            setLoadCount((n) => n + 1);
                            onApplied();
                            return;
                        }
                        if (result.status === 'failed' && result.failure.intent === 'finished')
                            setIntent(null);
                        setMessage(
                            result.status === 'failed'
                                ? messageOf(new CommandNeedsReview(result))
                                : '처리 결과를 확인하지 못했습니다. 같은 입력으로 다시 보내면 같은 요청으로 처리됩니다.',
                        );
                    })
                    .catch((error: unknown) => setMessage(messageOf(error)))
                    .finally(() => setBusy(false));
            }}
        >
            <h2 className="h3">최초 업무 배정</h2>
            <p className="small">
                아직 아무에게도 배정되지 않은 업무 묶음을, 활동 가능한 팀원에게 처음 배정합니다.
                같은 묶음의 업무가 모두 함께 배정되고, 받은 팀원에게 첫 배정 알림이 갑니다.
            </p>
            <div className="field">
                <label htmlFor="assign-bundle">업무 묶음</label>
                <select
                    id="assign-bundle"
                    value={bundleKey}
                    disabled={busy || intent !== null || tasks === null}
                    onChange={(e) => {
                        setBundleKey(e.target.value);
                        setMemberId('');
                    }}
                >
                    <option value="">
                        {tasks === null ? '업무 목록을 불러오는 중' : '묶음 선택'}
                    </option>
                    {bundles.map((b) => (
                        <option key={b.bundleKey} value={b.bundleKey}>
                            {b.bundleKey} / 업무 {b.taskCount}건 / {kst(b.startAt)}~{kst(b.endAt)}
                        </option>
                    ))}
                </select>
            </div>
            {tasks !== null && bundles.length === 0 && (
                <p className="small">아직 배정되지 않은 업무 묶음이 없습니다.</p>
            )}
            <div className="field">
                <label htmlFor="assign-member">팀원</label>
                <select
                    id="assign-member"
                    value={memberId}
                    disabled={busy || intent !== null || !bundle}
                    onChange={(e) => setMemberId(e.target.value)}
                >
                    <option value="">팀원 선택</option>
                    {candidates.map((m) => (
                        <option key={m.memberId} value={m.memberId}>
                            {m.displayName}
                        </option>
                    ))}
                </select>
            </div>
            {members && candidates.length === 0 && (
                <p className="small">
                    배정할 수 있는 팀원이 없습니다(묶음과 같은 그룹의 활동 가능/비예비 팀원만 배정할
                    수 있습니다).
                </p>
            )}
            <button className="btn" disabled={busy || !memberId || !bundle}>
                {intent ? '같은 요청 다시 보내기' : '배정'}
            </button>
            {intent && (
                <button
                    className="btn"
                    type="button"
                    disabled={busy}
                    onClick={() => {
                        client.session.scopes.intentKeys.release(
                            client.scope,
                            `initial-assignment:${intent.key}`,
                            intent.key,
                        );
                        setIntent(null);
                        setMessage('');
                    }}
                >
                    새 배정으로 시작
                </button>
            )}
            <p role="status">{message}</p>
            {assigned.length > 0 && (
                <ul>
                    {assigned.map((t) => (
                        <li key={t.id}>
                            {t.title} ({t.bundleKey}) / {kst(t.startAt)} ~ {kst(t.endAt)}
                        </li>
                    ))}
                </ul>
            )}
        </form>
    );
}
