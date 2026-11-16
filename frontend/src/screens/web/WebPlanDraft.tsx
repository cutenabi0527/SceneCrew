import { lazy, Suspense, useState } from 'react';
import { useData, useSession, useViewer } from '../../domain/hooks';
import { useDemoState } from '../../domain/store-context';
import { EventManageRoute } from '../../features/event-admin/EventManage';
import { PlainFrame, ReportFrame } from '../../features/plan-workflow/frame';
import { QuickPdfPanel } from '../../features/pdf-quick/QuickPdfPanel';
import { useLive } from '../../live/context';
import { Empty, Tabs } from '../../shared/ui/atoms';
import { useUrlState } from '../../shared/navigation/url-state';
import styles from './web.module.css';

const PlanWorkspace = lazy(() =>
    import('../../features/plan-workflow/Workspace').then((m) => ({ default: m.PlanWorkspace })),
);

/**
 * 공연 계획 화면의 '계획 수정' 탭(#362). 지금 계획이 채워진 계획 입력 화면을 탭 밑에 그대로 열고,
 * 맨 끝 저장하기로 저장한다. 저장하면 운영 화면 값을 서버에서 다시 읽는다.
 */
export function PlanEditPanel({ eventId }: { eventId: string }) {
    const { reload } = useLive();
    return (
        <Suspense fallback={<p role="status">계획 화면을 열고 있습니다.</p>}>
            <PlanWorkspace
                eventId={eventId}
                frame={PlainFrame}
                embedded
                onChanged={() => void reload().catch(() => undefined)}
            />
        </Suspense>
    );
}

type DraftTab = 'pdf' | 'info' | 'upload' | 'draft';
/** 'direct'는 예전 '직접 입력' 탭 주소(V9-D65). 이제 공연/행사 정보 등록 탭으로 연다 */
const DRAFT_TABS: readonly (DraftTab | 'direct')[] = ['pdf', 'info', 'upload', 'draft', 'direct'];

/**
 * W01-DRAFT 계획 입력·초안·공개(#362). 실제 공연(실서버 모드)에서 총괄이 쓰는 등록 화면이다.
 * 위의 세 탭으로 아래 화면을 바꾼다.
 * - PDF로 만들기(V9-D65, #410): PDF 한 번으로 공개 직전까지 자동 진행한다.
 * - 공연/행사 정보 등록: 공연 관리 카드(기본정보·임시 저장·공개). 내용이 길면 이 칸 안에서만 스크롤된다.
 * - 상세 정보 업로드: 조건 입력·문서 제출·추출 확인·AI 계획 생성·초안 편집(계획 수정 탭과 같은 화면). 창 아래까지 채운다.
 * - 미확정 초안(#372·#395): AI 계획 생성 결과의 평면도 배치·장비 후보를 확인·저장한다. 공개 때 이 초안으로
 *   카메라·장비 칸을 만든다. 계획 화면의 상태(검토로 가져온 후보·저장한 id 대응)를 써서 계획 화면이 이 탭 자리에 그린다.
 * 정보 등록·상세 정보 화면은 한 번 열면 탭을 바꿔도 그대로 두어(숨기기만) 입력 중인 내용이 사라지지 않는다.
 * PDF 탭은 보일 때만 그린다(다른 탭과 같은 공연 revision을 동시에 바꾸지 않게).
 * 고른 탭은 주소(?tab=)에 두어 새로고침해도 같은 탭으로 연다. 주소에 탭이 없으면 업무가 아직 없을 때 PDF다.
 */
export function WebPlanDraft() {
    const { live } = useDemoState();
    const { eventId } = useSession();
    const v = useViewer();
    const { reload } = useLive();
    // 문서 추출로 채울 기본정보 후보. 상세 정보 탭이 넘기고 공연 관리의 기본정보 칸이 받는다.
    const [prefill, setPrefill] = useState<Record<string, string> | null>(null);
    // 두 부분은 같은 공연 revision을 바꾼다. 한쪽이 저장·공개하면 다른 쪽이 최신 revision을 바로 다시 읽게 한다.
    // (주기 읽기·창 복귀 조회에 맡기면 타이밍에 따라 옛 revision으로 공개해 충돌한다, #268)
    const [planRefresh, setPlanRefresh] = useState(0);
    const [manageRefresh, setManageRefresh] = useState(0);
    const [planEditing, setPlanEditing] = useState(false);
    const d = useData();
    // 기본 탭은 처음 연 때 한 번만 정한다. PDF 탭이 업무를 저장해도 다른 탭으로 넘어가지 않는다(#410 리뷰)
    const [fallback] = useState<DraftTab>(() => (d.tasks.length ? 'info' : 'pdf'));
    const [asked, setTab] = useUrlState<DraftTab | 'direct'>('tab', fallback, DRAFT_TABS);
    const tab: DraftTab = asked === 'direct' ? 'info' : asked;
    // 상세 정보 화면은 처음 열 때 그린다(보이는 자리에서 높이를 재야 한다). 그 뒤로는 숨기기만 한다
    // 상세 정보·미확정 초안 화면은 계획 화면이 그리므로, 둘 중 하나를 처음 열 때 계획 화면을 그린다(높이를 재야 한다).
    // 그 뒤로는 숨기기만 한다
    const [uploadSeen, setUploadSeen] = useState(tab === 'upload' || tab === 'draft');
    // 상세 정보 탭의 계획 초안 편집·저장 버튼을 탭 줄 오른쪽에 그릴 자리
    const [barSlot, setBarSlot] = useState<HTMLDivElement | null>(null);
    // 정보 등록 탭의 형광펜·새로고침을 탭 줄 오른쪽에 그릴 자리
    const [toolsSlot, setToolsSlot] = useState<HTMLDivElement | null>(null);
    if ((tab === 'upload' || tab === 'draft') && !uploadSeen) setUploadSeen(true);
    // 미확정 초안 패널을 그릴 자리(미확정 초안 탭 화면)
    const [draftSlot, setDraftSlot] = useState<HTMLDivElement | null>(null);
    if (!live || !eventId)
        return (
            <Empty icon="task">
                데모 행사에는 서버 초안이 없습니다. 실제 계정의 공연에서 열어 주세요.
            </Empty>
        );
    if (!v.isChief) return <Empty icon="task">계획 입력과 공개는 총괄만 할 수 있습니다.</Empty>;
    const refresh = () => void reload().catch(() => undefined);
    return (
        <div className={styles.draftPage}>
            <div className={styles.draftTabs}>
                <Tabs<DraftTab>
                    label="등록 화면"
                    value={tab}
                    onChange={setTab}
                    items={[
                        ['pdf', 'PDF로 만들기'],
                        ['info', '공연/행사 정보 등록'],
                        ['upload', '상세 정보 업로드'],
                        ['draft', '미확정 초안'],
                    ]}
                />
                <div ref={setToolsSlot} className={styles.draftBar} hidden={tab !== 'info'} />
                <div ref={setBarSlot} className={styles.draftBar} hidden={tab !== 'upload'} />
            </div>
            {tab === 'pdf' && (
                <div className={styles.draftPdf}>
                    <QuickPdfPanel eventId={eventId} onChanged={refresh} />
                </div>
            )}
            <div className={styles.draftInfo} hidden={tab !== 'info'}>
                <EventManageRoute
                    eventId={eventId}
                    frame={ReportFrame}
                    embedded
                    onChanged={() => {
                        setPlanRefresh((n) => n + 1);
                        refresh();
                    }}
                    prefill={prefill}
                    refresh={manageRefresh}
                    planEditing={planEditing}
                    toolsSlot={toolsSlot}
                />
            </div>
            <div ref={setDraftSlot} className={styles.draftPdf} hidden={tab !== 'draft'} />
            {uploadSeen && (
                <div className={styles.draftUpload} hidden={tab !== 'upload'}>
                    <Suspense fallback={<p role="status">계획 화면을 열고 있습니다.</p>}>
                        <PlanWorkspace
                            eventId={eventId}
                            frame={PlainFrame}
                            embedded
                            onExtractBasics={(basics) => setPrefill({ ...basics })}
                            refresh={planRefresh}
                            onEditingChange={setPlanEditing}
                            onChanged={() => {
                                setManageRefresh((n) => n + 1);
                                refresh();
                            }}
                            fitGap={0}
                            barSlot={barSlot}
                            draftPanelSlot={draftSlot}
                        />
                    </Suspense>
                </div>
            )}
        </div>
    );
}
