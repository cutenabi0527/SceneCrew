import { displayTime } from './time';
import {
    Fragment,
    useEffect,
    useId,
    useMemo,
    useRef,
    useState,
    type CSSProperties,
    type ReactNode,
} from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { PlanJobPanel } from '../plan-job/PlanJobPanel';
import { questionHint, questionTarget } from '../plan-job/question-target';
import type { PlanQuestion } from '../plan-job/contract';
import type { EventSummary } from '../identity/contract';
import { useLeaveProtection } from '../../shared/navigation/leave-guard-context';
import { EventAccess } from './Access';
import type { Frame } from './frame';
import { DraftEditor } from './Editor';
import { draftProblem, explainDraftRejection, tidyDraft } from './draft-check';
import { ExtractionPanel } from './ExtractionPanel';
import { ConditionSummary, ConditionsDialog } from './Conditions';
import { ReferenceFiles } from './ReferenceFiles';
import { useAvailableHeight } from './fit-height';
import { sameZoneNames, syncZones } from './zone-sync';
import { useFloorZoneNames, FLOOR_RATIO } from '../safety/shared-floor-plan';
import { Field } from './ui';
import { Icon } from '../../shared/ui/Icon';
import { Dialog } from '../../shared/ui/Dialog';
import { useWorkAction, workFailureText } from './action';
import type { Workflow } from './client';
import {
    jobMemoryKey,
    hasConditionsMemory,
    readConditions,
    readDocuments,
    readLastExtraction,
    writeConditions,
    writeDocuments,
    writeLastExtraction,
    type UploadedDocument,
} from './job-memory';
import { refId, type Draft } from './contract';
import { zonesFromCondition } from './extraction-fields';
import { UnconfirmedDraftPanel } from '../unconfirmed-draft/DraftPanel';
import { assetsFromSheet, layoutFromFloorBoxes } from '../unconfirmed-draft/auto-fill';
import { useSharedFloorPlan } from '../safety/shared-floor-plan';
import type { DraftLayout } from '../unconfirmed-draft/layout';
import { createPortal } from 'react-dom';
import styles from './workspace.module.css';
import report from './report.module.css';

const basicsLabels = {
    name: '공연 이름',
    description: '공연 설명',
    date: '공연 날짜',
    prepStartAt: '준비 시작 시각',
    openAt: '공연 시작 시각',
    closeAt: '공연 종료 시각',
    teardownEndAt: '철수 종료 시각',
    locationName: '장소 이름',
    address: '주소',
    totalCapacity: '수용 인원',
    expectedVisitors: '예상 관객',
    peakVisitors: '최대 예상 관객',
    desiredReserveCount: '희망 예비 인원',
};
/**
 * 공연 계획 작업 화면(W01-DRAFT). 기능 화면은 주소의 공연으로 열고, 디자인 화면(#362)은
 * eventId·셸 틀을 넘겨 embedded로 연다. embedded에서는 기본정보·공개·시작을 아래 공연 관리 부분이 맡는다.
 */
export function PlanWorkspace({
    eventId,
    frame = EntryFrame,
    embedded = false,
    onExtractBasics,
    onChanged,
    refresh = 0,
    onEditingChange,
    aside,
    fitGap,
    barSlot,
    draftPanelSlot,
}: {
    eventId?: string;
    frame?: Frame;
    embedded?: boolean;
    /** embedded에서 추출한 공연 기본정보를 공연 관리의 기본정보 칸에 채운다 */
    onExtractBasics?: (basics: Record<string, string>) => void;
    /** 이 화면의 저장으로 공연 revision·상태가 바뀌면 알린다(같은 화면의 공연 관리가 다시 읽는다, #268) */
    onChanged?: () => void;
    /** 값이 바뀌면 편집 중인 초안은 두고 공연·계획만 다시 읽는다(공연 관리의 공개·저장 뒤, #268) */
    refresh?: number;
    /** 저장하지 않은 초안·조건·기본정보 편집이 있는지(같은 화면의 공연 관리가 공개를 막는다) */
    onEditingChange?: (editing: boolean) => void;
    /** 계획 초안 오른쪽 열에 둘 부분(W01-DRAFT의 공연 관리) */
    aside?: ReactNode;
    /** 계획 칸 아래에 남길 여백(px). 정하지 않으면 본문 아래 여백만큼 둔다 */
    fitGap?: number;
    /** 계획 초안 카드의 편집·저장 바를 그릴 다른 자리(등록 화면 탭 줄 오른쪽) */
    barSlot?: HTMLElement | null;
    /** 미확정 초안 패널(#372·#395)을 그릴 다른 자리(등록 화면의 미확정 초안 탭). 없으면 계획 화면 아래에 둔다 */
    draftPanelSlot?: HTMLElement | null;
} = {}) {
    return (
        <EventAccess eventId={eventId} frame={frame}>
            {(client, event) => (
                <Workspace
                    client={client}
                    event={event}
                    frame={frame}
                    embedded={embedded}
                    onExtractBasics={onExtractBasics}
                    onChanged={onChanged}
                    refresh={refresh}
                    onEditingChange={onEditingChange}
                    aside={aside}
                    fitGap={fitGap}
                    barSlot={barSlot}
                    draftPanelSlot={draftPanelSlot}
                />
            )}
        </EventAccess>
    );
}
function Workspace({
    client,
    event,
    frame: Frame,
    embedded,
    onExtractBasics,
    onChanged,
    refresh,
    onEditingChange,
    aside,
    fitGap,
    barSlot,
    draftPanelSlot,
}: {
    client: Workflow;
    event: EventSummary;
    frame: Frame;
    embedded: boolean;
    onExtractBasics?: (basics: Record<string, string>) => void;
    onChanged?: () => void;
    refresh: number;
    onEditingChange?: (editing: boolean) => void;
    aside?: ReactNode;
    fitGap?: number;
    barSlot?: HTMLElement | null;
    draftPanelSlot?: HTMLElement | null;
}) {
    const [data, setData] = useState<Awaited<ReturnType<Workflow['read']>> | null>(null);
    const [draft, setDraft] = useState<Draft | null>(null);
    // 서버에서 읽은(또는 마지막으로 저장한) 초안. 편집 중인 초안과 내용이 같으면 바뀐 것이 없다.
    // 썼다가 지워 원래와 같아진 경우도 바뀌지 않은 것으로 본다.
    const [baseline, setBaseline] = useState<Draft | null>(null);
    const dirty = !sameDraft(draft, baseline);
    // 구역은 평면도에 올린 구역 이름·개수를 그대로 따른다(계획 수정에서는 고치지 않는다)
    const floorZones = useFloorZoneNames(client.scope.eventId);
    // 왼쪽 칸 너비 = 평면도 너비 + 구역 표 너비. 문서|AI 조건 줄과 장비 줄도 이 너비를 쓴다
    // 평면도 칸은 5177×2748 비율이다
    const floorRatio = FLOOR_RATIO;
    // 계획 수정 칸들이 한 화면(탭 막대 아래부터 창 아래까지)에 꼭 맞게 높이를 잰다
    const fitRef = useAvailableHeight(fitGap);
    // 렌더 중에 맞춘다(이펙트를 거치면 한 번 어긋난 화면이 보인다). 맞춘 뒤에는 같아서 다시 바꾸지 않는다
    if (draft && floorZones && !sameZoneNames(draft, floorZones))
        setDraft(syncZones(draft, floorZones, () => `tmp-${crypto.randomUUID()}`));
    // 초안 저장의 안내는 버튼 옆에도 보인다(위쪽 상태 줄은 스크롤하면 보이지 않는다, #387)
    const [saveNote, setSaveNote] = useState<string | null>(null);
    const [saveTried, setSaveTried] = useState(false);
    // 검토로 가져온 결과6 평면도와 마지막 초안 저장의 id 대응(#372). 미확정 초안 패널이 쓴다.
    const [incomingLayout, setIncomingLayout] = useState<{
        layout: DraftLayout;
        jobId: string;
    } | null>(null);
    const [idMappings, setIdMappings] = useState<{ tempId: string; id: string }[]>([]);
    // 편집 중인 초안을 가져온 계획 생성 작업과, 그 초안을 저장(028)한 작업(#395).
    // 저장하면 미확정 초안의 출처(sourceJobId)로 남겨 공개 때 그 결과의 카메라·장비로 칸을 만든다.
    const [candidateJob, setCandidateJob] = useState<string | null>(null);
    const [appliedJob, setAppliedJob] = useState<string | null>(null);
    // 상세 정보 업로드의 장비 목록(첫 시트)과 이 브라우저에 둔 평면도. 미확정 초안 탭으로 자동 반영한다
    const [sheetRows, setSheetRows] = useState<string[][] | null>(null);
    const sheetAssets = useMemo(() => (sheetRows ? assetsFromSheet(sheetRows) : null), [sheetRows]);
    const sharedFloor = useSharedFloorPlan(client.scope.eventId);
    // 이 탭에서 마지막으로 저장한 조건으로 시작한다(서버 조회에는 조건이 없고 저장은 전체 교체다)
    // 서버에 저장된 것으로 아는 조건 이름. null이면 이 탭에서 저장한 적이 없어 서버의 기존 조건을 모른다
    const [savedKeys, setSavedKeys] = useState<string[] | null>(() =>
        hasConditionsMemory(jobMemoryKey(client.scope))
            ? Object.keys(readConditions(jobMemoryKey(client.scope)))
            : null,
    );
    // 저장이 기존 조건을 지울 수 있으면 먼저 묻는다
    const [confirmReplace, setConfirmReplace] = useState<{
        next: Record<string, string>;
        removed: string[];
    } | null>(null);
    // AI 조건 전체 팝업
    const [conditionsOpen, setConditionsOpen] = useState(false);
    const [fields, setFields] = useState<Record<string, string>>(() =>
        readConditions(jobMemoryKey(client.scope)),
    );
    const [conditionsDirty, setConditionsDirty] = useState(false);
    const [basics, setBasics] = useState<Record<string, string | null>>({});
    // 이 탭에서 올린 문서(이름·올린 시각)와 방금 올리지 못한 파일 이름
    const [documents, setDocuments] = useState<UploadedDocument[]>(() =>
        readDocuments(jobMemoryKey(client.scope)),
    );
    // 문서 업로드 실패 팝업. retry: 결과를 모르는 채 끝나 같은 요청으로 다시 확인할 수 있다
    const [uploadError, setUploadError] = useState<{ message: string; retry: boolean } | null>(
        null,
    );
    const [dragging, setDragging] = useState(false);
    const [confirmingDelete, setConfirmingDelete] = useState(false);
    const fileInputId = useId();
    const [lastExtraction, setLastExtraction] = useState(() =>
        readLastExtraction(jobMemoryKey(client.scope)),
    );
    // 같은 추출 작업은 처음 끝난 것을 본 시각을 그대로 둔다(다시 열어도 시각이 바뀌지 않는다)
    const recordExtraction = (jobId: string) => {
        if (lastExtraction?.jobId === jobId) return;
        const record = { jobId, at: new Date().toISOString() };
        setLastExtraction(record);
        writeLastExtraction(jobMemoryKey(client.scope), record);
    };
    const [job, setJob] = useState(client.jobs.get('generate'));
    const [extraction, setExtraction] = useState(client.jobs.get('extract'));
    const [questions, setQuestions] = useState<PlanQuestion[]>([]);
    const work = useWorkAction();
    // 초안 저장(explain을 넘기는 작업)이 아닌 작업을 시작하면 안내를 다시 위쪽 상태 줄에 보인다
    const action = {
        ...work,
        run: <T,>(
            task: () => Promise<T>,
            accept?: (value: T) => void,
            explain?: Parameters<typeof work.run>[2],
        ) => {
            if (!explain) {
                setSaveTried(false);
                setSaveNote(null);
            }
            return work.run(task, accept, explain);
        },
    };
    const { setMessage } = action;
    const unsaved = dirty || conditionsDirty || Object.keys(basics).length > 0;
    useLeaveProtection(unsaved, 'unsaved');
    // 저장하지 않은 계획 편집이 있으면 같은 화면의 공연 관리가 공개를 막는다(공개하면 편집할 수 없게 된다)
    useEffect(() => onEditingChange?.(unsaved), [unsaved, onEditingChange]);
    useEffect(() => {
        let active = true;
        client
            .read()
            .then((value) => {
                if (active) {
                    setData(value);
                    setDraft(value.draft);
                    setBaseline(value.draft);
                }
            })
            .catch(() => {
                if (active) setMessage('현재 계획을 확인하지 못했습니다. 다시 조회해주세요.');
            });
        return () => {
            active = false;
        };
    }, [client, setMessage]);
    // 같은 화면의 다른 부분(공연 관리)이 공개·저장하면 공연·계획만 다시 읽는다.
    // 편집 중인 초안은 그대로 두고, 편집 중이 아니면 서버 초안으로 맞춘다.
    const editingDraft = useRef(false);
    useEffect(() => {
        editingDraft.current = dirty;
    }, [dirty]);
    useEffect(() => {
        if (!refresh) return;
        let active = true;
        client
            .read()
            .then((value) => {
                if (!active) return;
                setData(value);
                if (!editingDraft.current) {
                    setDraft(value.draft);
                    setBaseline(value.draft);
                }
            })
            .catch(() => {
                if (active) setMessage('최신 계획을 확인하지 못했습니다. 다시 조회해주세요.');
            });
        return () => {
            active = false;
        };
    }, [client, refresh, setMessage]);
    // 이 화면의 저장(입력·문서·초안·기본정보·시작)으로 revision·상태가 바뀌면 알린다.
    const seen = useRef<string | null>(null);
    const stamp = data ? `${data.event.revision}:${data.event.state}` : null;
    useEffect(() => {
        if (!stamp) return;
        if (seen.current !== null && seen.current !== stamp) onChanged?.();
        seen.current = stamp;
    }, [stamp, onChanged]);
    const reload = () =>
        action.run(
            () => client.read(),
            (v) => {
                setData(v);
                setDraft(v.draft);
                setBaseline(v.draft);
                setBasics({});
                setConditionsDirty(false);
                action.setMessage('최신 계획을 불러왔습니다.');
            },
        );
    const [uploading, setUploading] = useState(false);
    // 문서를 고르면 바로 올린다. 같은 요청의 결과를 모르면(files 빈 배열) 같은 요청으로 다시 확인한다.
    const uploadFiles = (picked: File[]) => {
        if (!data || uploading || (!picked.length && !client.pending('upload'))) return;
        if (picked.length > MAX_DOCUMENTS - documents.length) {
            setUploadError({
                message: `문서는 모두 ${MAX_DOCUMENTS}개까지 올릴 수 있습니다. ${MAX_DOCUMENTS - documents.length}개 이하로 골라주세요.`,
                retry: false,
            });
            return;
        }
        setUploading(true);
        setUploadError(null);
        void (async () => {
            try {
                const result = await client.upload(picked);
                const now = new Date().toISOString();
                const added = result.files.flatMap((f) =>
                    f.status === 'REGISTERED'
                        ? [{ id: f.document.id, name: f.document.originalName, uploadedAt: now }]
                        : [],
                );
                const next = [
                    ...documents.filter((d) => !added.some((x) => x.id === d.id)),
                    ...added,
                ];
                setDocuments(next);
                writeDocuments(jobMemoryKey(client.scope), next);
                setData({ ...data, event: { ...data.event, revision: result.eventRevision } });
                const failed = result.files.flatMap((f) =>
                    f.status === 'FAILED' ? [picked[f.index]?.name ?? `${f.index + 1}번 파일`] : [],
                );
                if (failed.length)
                    setUploadError({
                        message: `올리지 못한 문서: ${failed.join(', ')}. 형식과 등록 한도를 확인해주세요.`,
                        retry: false,
                    });
                if (added.length) action.setMessage(`문서 ${added.length}개를 올렸습니다.`);
            } catch (error) {
                const message = workFailureText(error);
                if (message)
                    setUploadError({
                        message: client.pending('upload')
                            ? '문서 등록 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인할 수 있습니다(같은 파일이 두 번 올라가지 않습니다).'
                            : message,
                        retry: client.pending('upload'),
                    });
            } finally {
                setUploading(false);
            }
        })();
    };
    // 047은 내가 올린 문서를 모두 지운다. 지운 뒤 공연 revision이 바뀌므로 계획을 다시 읽는다.
    const deleteDocuments = () => {
        if (!data) return;
        void action.run(
            async () => {
                await client.deleteDocuments(data.event.revision);
                return client.read();
            },
            (current) => {
                setData(current);
                setDocuments([]);
                setConfirmingDelete(false);
                writeDocuments(jobMemoryKey(client.scope), []);
                action.setMessage('올린 문서를 모두 지웠습니다.');
            },
        );
    };
    if (!data)
        return (
            <Frame title="공연 계획">
                <p role="status">{action.message || '계획을 확인하고 있습니다.'}</p>
                <button
                    className="btn"
                    disabled={action.busy}
                    onClick={() => {
                        void reload();
                    }}
                >
                    현재 계획 다시 조회
                </button>
                {!embedded && <Link to="/account/events">내 공연 목록</Link>}
                {/* 계획을 못 읽어도 공연 관리(공개·기본정보)는 쓸 수 있게 둔다 */}
                {aside}
            </Frame>
        );
    const editable =
        event.myParticipation.eventRole === 'CHIEF' &&
        data.plan.viewerRole === 'CHIEF' &&
        event.state === 'DRAFT' &&
        data.event.state === 'DRAFT';
    const editing = dirty || conditionsDirty || Object.keys(basics).length > 0;
    // 미확정 초안(#372·#395): 검토로 가져온 결과6 평면도·마지막 저장의 id 대응·저장한 초안의 생성 작업을 넘긴다.
    // 등록 화면의 미확정 초안 탭에서는 평면도 초안 화면을 숨기고, 상세 정보 업로드에서 올린 평면도(구역 상자)와
    // 장비 목록을 미확정 초안에 자동으로 반영한다. 구역 상자는 이름으로 계획 초안 구역에 맞춘다.
    const planZones = (draft ?? data.plan).zones;
    const zoneKey = new Map(planZones.map((z) => [z.name.trim(), refId(z)]));
    const autoLayout =
        draftPanelSlot && sharedFloor
            ? layoutFromFloorBoxes(
                  sharedFloor.zones.flatMap((z) => {
                      const key = zoneKey.get(z.name.trim());
                      return key ? [{ key, rect: z.rect }] : [];
                  }),
                  sharedFloor.plan,
              )
            : null;
    const draftPanel = (
        <UnconfirmedDraftPanel
            client={client}
            zones={planZones.map((z) => ({ key: refId(z), name: z.name }))}
            incoming={incomingLayout}
            mappings={idMappings}
            appliedJobId={appliedJob}
            hideVenue={!!draftPanelSlot}
            autoLayout={autoLayout}
            autoAssets={draftPanelSlot ? sheetAssets : null}
        />
    );
    // 계획·조건·기본정보를 고치는 중에는 문서를 올리지 않는다(같은 공연 revision을 바꾼다)
    const uploadLocked = action.busy || uploading || editing;
    // 점선 상자와 '+ 문서 추가하기' 행이 같은 파일 선택·끌어 놓기를 쓴다
    const fileInput = (
        <input
            id={fileInputId}
            type="file"
            className="sr-only"
            aria-label="공연 문서"
            accept=".pdf,.txt,.md"
            multiple
            disabled={uploadLocked}
            onChange={(e) => {
                const picked = [...(e.target.files ?? [])];
                // 같은 파일을 다시 골라도 바뀜이 생기도록 비운다
                e.target.value = '';
                uploadFiles(picked);
            }}
        />
    );
    const dropProps = {
        'data-dragging': dragging || undefined,
        'data-disabled': uploadLocked || undefined,
        onDragOver: (e: React.DragEvent) => {
            if (uploadLocked) return;
            e.preventDefault();
            setDragging(true);
        },
        onDragLeave: () => setDragging(false),
        onDrop: (e: React.DragEvent) => {
            e.preventDefault();
            setDragging(false);
            if (!uploadLocked) uploadFiles([...e.dataTransfer.files]);
        },
    };
    const setEvent = (next: typeof data.event) => setData({ ...data, event: next });
    // 조건으로 답할 질문만 조건 칸을 연다. 기본정보·계획 초안에서 고칠 질문은 그 위치를 안내한다(#268).
    const revise = (qs: PlanQuestion[]) => {
        setQuestions(qs);
        const keys = qs.flatMap((q) => {
            const target = questionTarget(q);
            return target.kind === 'condition' ? [target.key] : [];
        });
        if (keys.length) {
            setFields((old) => ({ ...Object.fromEntries(keys.map((k) => [k, ''])), ...old }));
            setConditionsDirty(true);
        }
    };
    /**
     * 조건 저장은 전체 교체다. 서버의 기존 조건을 모르거나, 일부러 지운 것(removed) 말고도 사라지는
     * 조건이 있으면 먼저 확인한다.
     */
    const saveConditions = (
        next: Record<string, string>,
        removed: string[] = [],
        confirmed = false,
    ) => {
        if (!data) return;
        const drops =
            savedKeys === null ||
            savedKeys.some((k) => !Object.hasOwn(next, k) && !removed.includes(k));
        if (drops && !confirmed) {
            setConfirmReplace({ next, removed });
            return;
        }
        setConfirmReplace(null);
        setFields(next);
        setConditionsDirty(true);
        void action.run(
            () => client.input(next, data.event.revision),
            (e) => {
                setEvent(e);
                writeConditions(jobMemoryKey(client.scope), next);
                setSavedKeys(Object.keys(next));
                setConditionsDirty(false);
            },
        );
    };
    const conditionQuestion = (key: string) =>
        questions.find((q) => {
            const target = questionTarget(q);
            return target.kind === 'condition' && target.key === key;
        })?.question;
    return (
        <Frame title={`${data.event.name} 공연 계획`}>
            {!embedded && (
                <nav className="row">
                    <Link to={`/account/events/${event.id}`}>참여 정보</Link>
                    <Link to={`/account/events/${event.id}/tasks`}>내 업무</Link>
                    <Link to="/account/events">내 공연 목록</Link>
                </nav>
            )}
            {/* 초안 저장의 안내는 저장 버튼 옆에만 보인다 */}
            <p role="status" className={`small ${styles.status}`}>
                {saveTried ? '' : action.error}
            </p>
            {/* 편집할 수 있으면 아래 편집 칸에 지금 계획이 그대로 채워지므로 요약은 읽기 전용일 때만 보인다 */}
            {!editable && (
                <details className={styles.section} open>
                    <summary className={styles.sectionHead}>
                        <h2 className="h3">공통 공연 계획</h2>
                        <span className="xs muted">
                            그룹 {data.plan.workGroups.length} / 구역 {data.plan.zones.length} /
                            순서 {data.plan.programs.length} / 업무 {data.plan.tasks.length}
                        </span>
                    </summary>
                    <dl className={styles.summary}>
                        {data.plan.workGroups.map((g) => (
                            <Fragment key={g.id}>
                                <dt>{g.name}</dt>
                                <dd>{g.instructions ?? '현재 소속에 공개되지 않은 안내입니다.'}</dd>
                            </Fragment>
                        ))}
                        {data.plan.zones.map((z) => (
                            <Fragment key={z.id}>
                                <dt>{z.name}</dt>
                                <dd>{z.description || '설명 없음'}</dd>
                            </Fragment>
                        ))}
                        {data.plan.programs.map((p) => (
                            <Fragment key={p.id}>
                                <dt>{p.name}</dt>
                                <dd className="mono">
                                    {displayTime(p.startAt)} ~ {displayTime(p.endAt)}
                                </dd>
                            </Fragment>
                        ))}
                        {data.plan.tasks.map((t) => (
                            <Fragment key={t.id}>
                                <dt>{t.title}</dt>
                                <dd className="mono">
                                    {displayTime(t.startAt)} ~ {displayTime(t.endAt)}
                                </dd>
                            </Fragment>
                        ))}
                        {data.plan.instructions.map((i, n) => (
                            <Fragment key={n}>
                                <dt>공통 안내</dt>
                                <dd>{i.body}</dd>
                            </Fragment>
                        ))}
                    </dl>
                    {!data.plan.tasks.length && (
                        <p className="small muted">아직 등록된 업무가 없습니다.</p>
                    )}
                </details>
            )}
            {editable && (
                <>
                    {/* embedded에서는 아래 공연 관리의 기본정보 입력(날짜·시각 선택)을 쓴다 */}
                    <details hidden={embedded}>
                        <summary>공연 기본정보 편집</summary>
                        <fieldset
                            className="stack"
                            disabled={action.busy || dirty || conditionsDirty}
                        >
                            {Object.entries(basicsLabels).map(([key, label]) => (
                                <Field
                                    key={key}
                                    label={label}
                                    value={String(
                                        basics[key] !== undefined
                                            ? (basics[key] ?? '')
                                            : ((data.event as unknown as Record<string, unknown>)[
                                                  key
                                              ] ?? ''),
                                    )}
                                    multiline={key === 'description'}
                                    change={(v) =>
                                        setBasics({
                                            ...basics,
                                            [key]:
                                                v ||
                                                ([
                                                    'name',
                                                    'description',
                                                    'desiredReserveCount',
                                                ].includes(key)
                                                    ? ''
                                                    : null),
                                        })
                                    }
                                />
                            ))}
                            <p>시각에는 시간대를 포함해주세요. 예: 2026-11-18T10:00:00+09:00</p>
                            <button
                                className="btn"
                                disabled={!Object.keys(basics).length}
                                onClick={() => {
                                    void action.run(
                                        () => client.basics(basics, data.event.revision),
                                        (e) => {
                                            setEvent(e);
                                            setBasics({});
                                            action.setMessage('선택한 기본정보를 저장했습니다.');
                                        },
                                    );
                                }}
                            >
                                기본정보 저장
                            </button>
                        </fieldset>
                    </details>
                    {/* 보고/대응 화면처럼: 왼쪽 칸(문서 / 평면도·구역 / 장비) | 가운데 계획 초안 문서 */}
                    <div className={styles.planWrap}>
                        <div
                            className={styles.planSplit}
                            ref={fitRef}
                            data-aside={aside ? '' : undefined}
                            data-embedded={embedded ? '' : undefined}
                        >
                            <div
                                className={styles.planSide}
                                style={{ '--floor-ratio': floorRatio } as CSSProperties}
                            >
                                {/* 위: 문서 업로드 | AI 조건 입력, 아래: 평면도·구역, 장비 */}
                                <div className={styles.sideTop}>
                                    <section
                                        className={styles.aiConditions}
                                        aria-label="AI 조건 입력"
                                    >
                                        {/* 제목 줄: 왼쪽 제목, 오른쪽 저장 / 생성 */}
                                        <div className={styles.aiHead}>
                                            <h2 className="h3">AI 조건 입력</h2>
                                            <div className={styles.aiHeadActions}>
                                                <button
                                                    className="btn sm"
                                                    disabled={
                                                        action.busy ||
                                                        dirty ||
                                                        Object.keys(basics).length > 0 ||
                                                        !conditionsDirty
                                                    }
                                                    onClick={() => saveConditions(fields)}
                                                >
                                                    저장
                                                </button>
                                                <button
                                                    className={`btn sm primary ${styles.aiGenerate}`}
                                                    disabled={
                                                        action.busy ||
                                                        dirty ||
                                                        Object.keys(basics).length > 0 ||
                                                        conditionsDirty
                                                    }
                                                    onClick={() => {
                                                        void action.run(
                                                            () =>
                                                                client.generate(
                                                                    'generate',
                                                                    data.event.revision,
                                                                ),
                                                            setJob,
                                                        );
                                                    }}
                                                >
                                                    <Icon name="sparkle" />
                                                    {client.pending('generate')
                                                        ? '같은 생성 요청 확인'
                                                        : '생성'}
                                                </button>
                                            </div>
                                        </div>
                                        {confirmReplace && (
                                            <Dialog
                                                title="기존 조건을 바꿀까요?"
                                                onClose={() => setConfirmReplace(null)}
                                            >
                                                <div className="stack">
                                                    <p>
                                                        저장하면 서버에 있던 기존 조건이 지금 화면의
                                                        조건으로 모두 바뀝니다. 화면에 없는 조건은
                                                        지워집니다.
                                                    </p>
                                                    <div className={styles.actions}>
                                                        <button
                                                            type="button"
                                                            className="btn sm"
                                                            onClick={() => setConfirmReplace(null)}
                                                        >
                                                            취소
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className="btn sm primary"
                                                            onClick={() =>
                                                                saveConditions(
                                                                    confirmReplace.next,
                                                                    confirmReplace.removed,
                                                                    true,
                                                                )
                                                            }
                                                        >
                                                            바꿔서 저장
                                                        </button>
                                                    </div>
                                                </div>
                                            </Dialog>
                                        )}
                                        {questions.map((q, index) => {
                                            const hint = questionHint(q, embedded);
                                            return (
                                                <div key={`${q.key}-${index}`}>
                                                    <p>{q.question}</p>
                                                    {hint && <p className="small muted">{hint}</p>}
                                                </div>
                                            );
                                        })}
                                        <ConditionSummary
                                            fields={fields}
                                            labelOf={(key) => conditionQuestion(key) ?? key}
                                            disabled={
                                                action.busy ||
                                                dirty ||
                                                Object.keys(basics).length > 0
                                            }
                                            onOpen={() => setConditionsOpen(true)}
                                        />
                                        {conditionsOpen && (
                                            <ConditionsDialog
                                                fields={fields}
                                                labelOf={(key) => conditionQuestion(key) ?? key}
                                                busy={action.busy}
                                                onSave={(next, removed) =>
                                                    saveConditions(next, removed)
                                                }
                                                onClose={() => setConditionsOpen(false)}
                                            />
                                        )}
                                        {job && (
                                            <>
                                                <PlanJobPanel
                                                    api={client.session.api}
                                                    scope={client.scope}
                                                    jobId={job}
                                                    onRevise={!dirty ? revise : undefined}
                                                    onOpenDraft={
                                                        !dirty
                                                            ? () => {
                                                                  void action.run(
                                                                      () =>
                                                                          client.candidate(
                                                                              job,
                                                                              data.event.revision,
                                                                          ),
                                                                      (candidate) => {
                                                                          setDraft(
                                                                              structuredClone(
                                                                                  candidate.plan,
                                                                              ),
                                                                          );
                                                                          setCandidateJob(
                                                                              candidate.jobId,
                                                                          );
                                                                          setIncomingLayout(
                                                                              candidate.layout
                                                                                  ? {
                                                                                        layout: candidate.layout,
                                                                                        jobId: candidate.jobId,
                                                                                    }
                                                                                  : null,
                                                                          );
                                                                          action.setMessage(
                                                                              '후보를 편집에 가져왔습니다. 저장하기 전까지 현재 계획은 유지됩니다.',
                                                                          );
                                                                      },
                                                                  );
                                                              }
                                                            : undefined
                                                    }
                                                />
                                                <button
                                                    className="btn"
                                                    onClick={() => {
                                                        client.forgetJob('generate');
                                                        setJob(undefined);
                                                    }}
                                                >
                                                    생성 결과 닫기
                                                </button>
                                            </>
                                        )}
                                    </section>
                                    {/* 문서 입력: 처음에는 점선 상자, 올린 뒤에는 표와 맨 아래 '+ 문서 추가하기' 행 */}
                                    <section className={styles.docs} aria-label="문서 입력">
                                        {fileInput}
                                        {/* 표는 늘 3행이다. 빈 행은 점선 '+ 문서 추가하기' 행이고, 문서가 하나도 없으면
                            표와 추출 버튼 전체를 점선 '+ 문서 업로드' 상자로 덮는다 */}
                                        <div className={styles.docHeader}>
                                            <span className={styles.refTitle}>문서</span>
                                            <div className={styles.docActions}>
                                                {confirmingDelete ? (
                                                    <span className={styles.confirm}>
                                                        올린 문서를 모두 지울까요?
                                                        <button
                                                            type="button"
                                                            className={styles.deleteAll}
                                                            disabled={uploadLocked}
                                                            onClick={deleteDocuments}
                                                        >
                                                            모두 삭제
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className={styles.cancelDelete}
                                                            onClick={() =>
                                                                setConfirmingDelete(false)
                                                            }
                                                        >
                                                            취소
                                                        </button>
                                                    </span>
                                                ) : (
                                                    <span className={styles.docTools}>
                                                        {lastExtraction && (
                                                            <span className={styles.aiTime}>
                                                                최근 추출{' '}
                                                                {uploadedLabel(lastExtraction.at)}
                                                            </span>
                                                        )}
                                                        {/* 전체 삭제 왼쪽: AI 문서 추출 */}
                                                        <button
                                                            type="button"
                                                            className={styles.aiButton}
                                                            disabled={uploadLocked}
                                                            onClick={() => {
                                                                void action.run(
                                                                    () =>
                                                                        client.generate(
                                                                            'extract',
                                                                            data.event.revision,
                                                                        ),
                                                                    setExtraction,
                                                                );
                                                            }}
                                                        >
                                                            <Icon name="sparkle" />
                                                            문서 추출
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className={styles.deleteAll}
                                                            disabled={uploadLocked}
                                                            onClick={() =>
                                                                setConfirmingDelete(true)
                                                            }
                                                        >
                                                            전체 삭제
                                                        </button>
                                                    </span>
                                                )}
                                            </div>
                                        </div>
                                        <div className={styles.docArea}>
                                            <div
                                                className={styles.docRow}
                                                inert={documents.length === 0}
                                                aria-hidden={documents.length === 0 || undefined}
                                            >
                                                <table
                                                    className={styles.docTable}
                                                    aria-label="올린 문서"
                                                >
                                                    <tbody>
                                                        {documents.map((doc) => (
                                                            <tr key={doc.id}>
                                                                <td className={styles.docName}>
                                                                    {doc.name}
                                                                </td>
                                                                <td className={styles.docTime}>
                                                                    {uploadedLabel(doc.uploadedAt)}
                                                                </td>
                                                            </tr>
                                                        ))}
                                                        {Array.from(
                                                            {
                                                                length: Math.max(
                                                                    0,
                                                                    MAX_DOCUMENTS -
                                                                        documents.length,
                                                                ),
                                                            },
                                                            (_, i) => (
                                                                <tr key={`empty-${i}`}>
                                                                    <td
                                                                        colSpan={2}
                                                                        className={styles.addCell}
                                                                    >
                                                                        <label
                                                                            htmlFor={fileInputId}
                                                                            className={
                                                                                styles.addRow
                                                                            }
                                                                            {...dropProps}
                                                                        >
                                                                            {uploading
                                                                                ? '문서를 올리고 있습니다'
                                                                                : '+ 문서 추가하기'}
                                                                        </label>
                                                                    </td>
                                                                </tr>
                                                            ),
                                                        )}
                                                    </tbody>
                                                </table>
                                            </div>
                                            {documents.length === 0 && (
                                                <label
                                                    htmlFor={fileInputId}
                                                    className={styles.dropzone}
                                                    {...dropProps}
                                                >
                                                    <span className={styles.dropTitle}>
                                                        {uploading
                                                            ? '문서를 올리고 있습니다'
                                                            : '+ 문서 업로드'}
                                                    </span>
                                                    <span className={styles.dropHint}>
                                                        PDF/TXT/MD 최대 {MAX_DOCUMENTS}개, 파일당
                                                        20MiB / 합계 30MiB
                                                    </span>
                                                </label>
                                            )}
                                        </div>
                                        {uploadError && (
                                            <Dialog
                                                title="문서를 올리지 못했습니다"
                                                onClose={() => setUploadError(null)}
                                            >
                                                <div className="stack">
                                                    <p role="alert">{uploadError.message}</p>
                                                    <div className={styles.actions}>
                                                        {uploadError.retry && (
                                                            <button
                                                                type="button"
                                                                className="btn sm primary"
                                                                onClick={() => uploadFiles([])}
                                                            >
                                                                같은 요청 다시 확인
                                                            </button>
                                                        )}
                                                        <button
                                                            type="button"
                                                            className="btn sm"
                                                            onClick={() => setUploadError(null)}
                                                        >
                                                            {uploadError.retry ? '나중에' : '확인'}
                                                        </button>
                                                    </div>
                                                </div>
                                            </Dialog>
                                        )}
                                        {extraction && (
                                            <>
                                                <ExtractionPanel
                                                    client={client}
                                                    id={extraction}
                                                    onSucceeded={recordExtraction}
                                                    onUse={({ basics: extracted, conditions }) => {
                                                        // 공연 기본정보는 공연 관리의 기본정보 칸에, 그 밖의 값은 계획 생성 조건에
                                                        // 채운다. 어느 쪽도 저장하지 않는다. 총괄이 확인하고 각 저장 버튼을 누른다.
                                                        // 이 화면의 기본정보 편집과 조건 편집은 서로 잠그므로(같은 revision을 바꿈)
                                                        // 기본정보는 여기 채우지 않는다.
                                                        const basicCount =
                                                            Object.keys(extracted).length;
                                                        // 이미 적어 둔 조건은 덮어쓰지 않는다(빈 칸만 채운다)
                                                        const kept = Object.keys(conditions).filter(
                                                            (k) => !!fields[k]?.trim(),
                                                        ).length;
                                                        const conditionCount =
                                                            Object.keys(conditions).length - kept;
                                                        const toManage =
                                                            basicCount > 0 && !!onExtractBasics;
                                                        if (toManage) onExtractBasics(extracted);
                                                        if (conditionCount) {
                                                            setFields((old) => ({
                                                                ...conditions,
                                                                ...Object.fromEntries(
                                                                    Object.entries(old).filter(
                                                                        ([, v]) => v.trim(),
                                                                    ),
                                                                ),
                                                            }));
                                                            setConditionsDirty(true);
                                                        }
                                                        // 구역이 하나도 없는 초안이면 문서의 구역 이름을 계획 구역으로도 채운다(#401 운영 확인).
                                                        // 계획 생성은 초안 구역에 업무를 놓으므로, 조건에만 있으면 "구역 없음"으로 멈춘다.
                                                        const zoneNames = zonesFromCondition(
                                                            conditions.zones,
                                                        );
                                                        const zonesAdded =
                                                            draft && draft.zones.length === 0
                                                                ? zoneNames.length
                                                                : 0;
                                                        if (draft && zonesAdded)
                                                            setDraft({
                                                                ...draft,
                                                                zones: zoneNames.map((name) => ({
                                                                    tempId: `tmp-${crypto.randomUUID()}`,
                                                                    name,
                                                                    description: null,
                                                                })),
                                                            });
                                                        action.setMessage(
                                                            [
                                                                zonesAdded > 0 &&
                                                                    `구역 ${zonesAdded}개를 계획 초안에 채웠습니다. 초안을 저장한 뒤 계획을 생성하세요.`,
                                                                toManage &&
                                                                    `공연 기본정보 ${basicCount}개를 공연 관리의 기본정보 칸에 채웠습니다.`,
                                                                basicCount > 0 &&
                                                                    !toManage &&
                                                                    `공연 기본정보 ${basicCount}개는 공연 관리 화면의 기본정보에서 입력해주세요.`,
                                                                conditionCount > 0 &&
                                                                    `계획 생성 조건 ${conditionCount}개를 채웠습니다.`,
                                                                kept > 0 &&
                                                                    `이미 적어 둔 조건 ${kept}개는 그대로 두었습니다.`,
                                                                '확인한 뒤 저장해야 반영됩니다.',
                                                            ]
                                                                .filter(Boolean)
                                                                .join(' '),
                                                        );
                                                    }}
                                                />
                                                <button
                                                    className="btn"
                                                    onClick={() => {
                                                        client.forgetJob('extract');
                                                        setExtraction(undefined);
                                                    }}
                                                >
                                                    추출 결과 닫기
                                                </button>
                                            </>
                                        )}
                                    </section>
                                </div>
                                <ReferenceFiles
                                    storageKey={jobMemoryKey(client.scope)}
                                    eventId={client.scope.eventId}
                                    locked={action.busy}
                                    zones={(draft ?? data.plan).zones.map((z) => z.name)}
                                    onSheet={setSheetRows}
                                />
                            </div>
                            <div className={styles.planMain}>
                                {draft && (
                                    <DraftEditor
                                        barSlot={barSlot}
                                        title={data.event.name}
                                        owner={client.session.snapshot().account?.displayName}
                                        value={draft}
                                        change={(v) => {
                                            setDraft(v);
                                            setSaveNote(null);
                                            setSaveTried(false);
                                        }}
                                        disabled={
                                            action.busy ||
                                            conditionsDirty ||
                                            Object.keys(basics).length > 0 ||
                                            client.pending('save')
                                        }
                                        note={
                                            (saveNote || (saveTried && action.error)) && (
                                                <p className="small" role="alert">
                                                    {saveNote ?? action.error}
                                                </p>
                                            )
                                        }
                                        actions={
                                            <>
                                                {/* 고친 것을 버리고 서버의 지금 계획을 다시 불러온다 */}
                                                {editing && (
                                                    <button
                                                        type="button"
                                                        className={report.barIcon}
                                                        aria-label="편집 취소"
                                                        title="편집 취소(저장한 계획으로 되돌리기)"
                                                        disabled={action.busy}
                                                        onClick={() => {
                                                            void reload();
                                                        }}
                                                    >
                                                        <Icon name="back" />
                                                    </button>
                                                )}
                                                {/* 고친 것이 있을 때만 누를 수 있다 */}
                                                <button
                                                    type="button"
                                                    className={report.barIcon}
                                                    aria-label="저장하기"
                                                    title="저장하기"
                                                    data-primary={dirty ? '' : undefined}
                                                    // 저장하지 않은 조건이 있어도 초안은 저장한다. 둘 다 막으면 문서 추출 채우기
                                                    // (구역+조건)가 어느 쪽도 저장하지 못한다(#401 운영). 조건은 초안 저장 뒤 저장한다.
                                                    disabled={
                                                        action.busy ||
                                                        !dirty ||
                                                        Object.keys(basics).length > 0 ||
                                                        client.pending('save')
                                                    }
                                                    onClick={() => {
                                                        const next = tidyDraft(draft);
                                                        const problem = draftProblem(next);
                                                        setSaveNote(problem);
                                                        setSaveTried(!problem);
                                                        // 고칠 곳이 있으면 보내지 않는다(서버에 갈 수 없는 형식이다)
                                                        if (problem) return;
                                                        void action.run(
                                                            async () => {
                                                                const saved = await client.save(
                                                                    next,
                                                                    data.event.revision,
                                                                );
                                                                try {
                                                                    return {
                                                                        saved,
                                                                        current:
                                                                            await client.read(),
                                                                    };
                                                                } catch (error) {
                                                                    if (!client.scope.isCurrent())
                                                                        throw error;
                                                                    return { saved, current: null };
                                                                }
                                                            },
                                                            (v) => {
                                                                setData(
                                                                    v.current ?? {
                                                                        ...data,
                                                                        event: {
                                                                            ...data.event,
                                                                            revision:
                                                                                v.saved.plan
                                                                                    .eventRevision,
                                                                        },
                                                                        plan: v.saved.plan,
                                                                        draft: null,
                                                                    },
                                                                );
                                                                setDraft(v.current?.draft ?? null);
                                                                setBaseline(
                                                                    v.current?.draft ?? null,
                                                                );
                                                                setIdMappings(v.saved.idMappings);
                                                                if (candidateJob)
                                                                    setAppliedJob(candidateJob);
                                                                setCandidateJob(null);
                                                                action.setMessage(
                                                                    v.current
                                                                        ? '초안을 저장하고 현재 계획을 다시 확인했습니다.'
                                                                        : '초안 저장은 처리됐습니다. 최신 상세 조회를 완료하지 못했으므로 현재 계획을 다시 조회해주세요.',
                                                                );
                                                            },
                                                            // 서버가 거절한 칸과 사유를 항목 이름으로 알린다
                                                            (failure) =>
                                                                explainDraftRejection(
                                                                    failure.fields,
                                                                    next,
                                                                ),
                                                        );
                                                    }}
                                                >
                                                    <Icon name="save" />
                                                </button>
                                            </>
                                        }
                                    />
                                )}
                            </div>
                            {aside && <div className={styles.planAside}>{aside}</div>}
                        </div>
                    </div>
                    {editable &&
                        (draftPanelSlot ? createPortal(draftPanel, draftPanelSlot) : draftPanel)}
                </>
            )}
            {!embedded &&
                event.myParticipation.eventRole === 'CHIEF' &&
                data.event.state === 'RECRUITING' &&
                event.state === 'RECRUITING' && (
                    <button
                        className="btn primary"
                        disabled={action.busy}
                        onClick={() => {
                            void action.run(
                                () => client.start(data.event.revision),
                                (e) => {
                                    setEvent(e);
                                    action.setMessage(
                                        '공연 시작을 처리했습니다. 현재 상태를 다시 조회해주세요.',
                                    );
                                },
                            );
                        }}
                    >
                        공연 시작
                    </button>
                )}
        </Frame>
    );
}

/** 두 초안의 내용이 같은지. 편집으로 만든 사본은 속성 순서가 같으므로 직렬화해 비교한다. */
function sameDraft(a: Draft | null, b: Draft | null) {
    if (a === b) return true;
    if (!a || !b) return false;
    return JSON.stringify(a) === JSON.stringify(b);
}

/** 이 화면에서 올려 두는 공연 문서 수의 상한(서버 등록 한도와 같다) */
const MAX_DOCUMENTS = 3;

/** 올린 시각을 'YY.MM.DD HH:mm'(이 기기 시간대)으로 */
function uploadedLabel(iso: string) {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const pad = (n: number) => String(n).padStart(2, '0');
    // 'YY.MM.DD HH:mm'(예: 26.11.12 13:11)
    return `${pad(d.getFullYear() % 100)}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
