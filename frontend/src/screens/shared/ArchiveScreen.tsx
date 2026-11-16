import { useState } from 'react';
import { useEvent, useSession, useViewer } from '../../domain/hooks';
import { ArchiveTimeline, ReportSummary } from '../../features/archive/ArchiveParts';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { LiveClosingRoute } from '../../features/event-close/LiveClosing';
import { ShellFrame } from '../../features/plan-workflow/frame';
import { useLive } from '../../live/context';
import { Empty, Note } from '../../shared/ui/atoms';
import styles from './shared.module.css';

/**
 * A01 종료 현황 · 일회 요약(총괄 WEB 전용, P07·IA05).
 * 왼쪽 최소 운영 이력 + 오른쪽 현재 사실 집계와 요약 생성. 요약은 실서버에서만 AI가 한 번 만들고(#269·#310),
 * 서버에 보관하지 않아 복사·로컬 저장만 한다. PDF·스냅샷·과거 버전·같은 결과 재출력은 없다.
 * APP과 팀원에게는 이 화면이 없다(종료 뒤에는 기존 화면을 읽기 전용으로 본다).
 */
export function ArchiveScreen() {
    const v = useViewer();
    const ev = useEvent();
    const dispatch = useDispatch();
    const [confirmEnd, setConfirmEnd] = useState(false);
    const { live } = useDemoState();
    const { eventId } = useSession();
    const { reload } = useLive();

    if (!v.isChief) return <Empty icon="lock">종료 요약은 총괄만 볼 수 있습니다</Empty>;
    // 실서버(#384): 종료(031)와 일회 종료 요약(080)은 서버로 한다. 운영 이력은 서버 API가 없어 숨기고(V9-D49),
    // 현재 사실 집계(종료 현황)만 운영 화면 값으로 보인다. 종료하면 운영 화면을 다시 읽어 읽기 전용이 된다.
    if (live && eventId)
        return (
            <div className={styles.webArchive}>
                <section className="stack">
                    <b>종료 현황</b>
                    <ReportSummary />
                </section>
                <section className={`stack ${styles.archiveSide}`}>
                    <LiveClosingRoute
                        eventId={eventId}
                        frame={ShellFrame}
                        embedded
                        onChanged={() => void reload().catch(() => undefined)}
                    />
                </section>
            </div>
        );
    const ended = ev.state === 'ENDED';

    return (
        <div className={styles.webArchive}>
            <section className="stack">
                <b>운영 이력</b>
                <ArchiveTimeline />
            </section>
            {/* 왼쪽 세로줄로 운영 이력과 나눈다 */}
            <section className={`stack ${styles.archiveSide}`}>
                <b className={styles.archiveSideTitle}>종료 현황</b>
                <ReportSummary />
                {!ended ? (
                    <>
                        <Note>
                            종료 요약은 행사를 종료한 뒤 만들 수 있습니다. 종료하면 업무/보고/채팅
                            쓰기가 닫히고 읽기만 할 수 있습니다.
                        </Note>
                        {confirmEnd ? (
                            <div className="row">
                                <button
                                    type="button"
                                    className="btn primary"
                                    onClick={() => dispatch({ type: 'endEvent' })}
                                >
                                    종료 확정
                                </button>
                                <button
                                    type="button"
                                    className="btn ghost"
                                    onClick={() => setConfirmEnd(false)}
                                >
                                    취소
                                </button>
                            </div>
                        ) : (
                            <button
                                type="button"
                                className="btn"
                                onClick={() => setConfirmEnd(true)}
                            >
                                행사 종료
                            </button>
                        )}
                    </>
                ) : (
                    // 데모 화면은 요약을 만들지 않는다(#310). 화면이 사실을 모아 만든 가짜 요약이 실제 AI 요약처럼
                    // 보이지 않게 하려는 것이다. 실서버에서는 위의 LiveClosingRoute가 종료 요약(080)을 서버로 받는다.
                    <Note>
                        종료 요약은 실서버에 연결한 공연에서 AI가 한 번 만들어 줍니다. 데모
                        화면에서는 요약을 만들지 않습니다. 요약은 서버에 보관하지 않으므로,
                        실서버에서 받은 뒤 복사하거나 파일로 저장하세요.
                    </Note>
                )}
            </section>
        </div>
    );
}
