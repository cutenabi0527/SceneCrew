import { useState, type CSSProperties } from 'react';
import { useData, useEvent } from '../../domain/hooks';
import { EVENT_DATE } from '../../domain/seed';
import { nameOf } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { useLeaveProtection } from '../../shared/navigation/leave-guard-context';
import { demoDraft, planFromDraft } from './demo-plan';
import { DraftEditor } from './Editor';
import { draftProblem, tidyDraft } from './draft-check';
import { PlainFrame } from './frame';
import type { Draft } from './contract';
import { ReferenceFiles } from './ReferenceFiles';
import { useAvailableHeight } from './fit-height';
import { sameZoneNames, syncZones } from './zone-sync';
import { useFloorZoneNames, FLOOR_RATIO } from '../safety/shared-floor-plan';
import { Icon } from '../../shared/ui/Icon';
import styles from './workspace.module.css';
import report from './report.module.css';

/**
 * 데모 공연의 '계획 수정'. 실서버 계획 입력 화면(Workspace)과 같은 구성으로 보이고, 저장은 데모 데이터에 한다.
 * 문서 입력과 AI 계획 생성은 서버가 있어야 하므로 데모에서는 자리만 보이고 잠근다.
 */
export function DemoPlanWorkspace() {
    const ev = useEvent();
    const d = useData();
    const dispatch = useDispatch();
    const { me } = useDemoState();
    const [baseline, setBaseline] = useState<Draft>(() => demoDraft(ev, d, EVENT_DATE));
    const [draft, setDraft] = useState<Draft>(baseline);
    // 구역은 평면도에 올린 구역을 그대로 따른다
    const floorZones = useFloorZoneNames();
    // 왼쪽 칸 너비 = 평면도 너비 + 구역 표 너비. 문서|AI 조건 줄과 장비 줄도 이 너비를 쓴다
    // 평면도 칸은 5177×2748 비율이다
    const floorRatio = FLOOR_RATIO;
    // 계획 수정 칸들이 한 화면(탭 막대 아래부터 창 아래까지)에 꼭 맞게 높이를 잰다
    const fitRef = useAvailableHeight();
    // 렌더 중에 맞춘다(이펙트를 거치면 한 번 어긋난 화면이 보인다). 맞춘 뒤에는 같아서 다시 바꾸지 않는다
    if (floorZones && !sameZoneNames(draft, floorZones))
        setDraft(syncZones(draft, floorZones, () => `tmp-${crypto.randomUUID()}`));
    const [saveNote, setSaveNote] = useState<string | null>(null);
    // 썼다가 지워 원래와 같아지면 바뀐 것이 없는 것으로 본다
    const dirty = JSON.stringify(draft) !== JSON.stringify(baseline);
    useLeaveProtection(dirty, 'unsaved');
    return (
        <PlainFrame title={`${ev.name} 공연 계획`}>
            <div className={styles.planWrap}>
                {/* 운영 화면 계획 수정 탭과 같은 배치(왼쪽 칸 1/3 | 계획 초안) */}
                <div className={styles.planSplit} ref={fitRef} data-embedded="">
                    <div
                        className={styles.planSide}
                        style={{ '--floor-ratio': floorRatio } as CSSProperties}
                    >
                        <div className={styles.sideTop}>
                            <section className={styles.aiConditions} aria-label="AI 조건 입력">
                                <div className={styles.aiHead}>
                                    <h2 className="h3">AI 조건 입력</h2>
                                    <div className={styles.aiHeadActions}>
                                        <button type="button" className="btn sm" disabled>
                                            저장
                                        </button>
                                        <button
                                            type="button"
                                            className={`btn sm primary ${styles.aiGenerate}`}
                                            disabled
                                        >
                                            <Icon name="sparkle" />
                                            생성
                                        </button>
                                    </div>
                                </div>
                                <fieldset className={styles.plain} disabled>
                                    <p className="xs muted">
                                        데모 공연에서는 AI 계획 생성을 쓸 수 없습니다.
                                    </p>
                                </fieldset>
                            </section>
                            <section className={styles.docs} aria-label="문서 입력">
                                {/* 실제 화면과 같은 자리: 3행 문서 표 + 오른쪽 추출 버튼을 점선 업로드 상자가 덮는다 */}
                                <div className={styles.docHeader}>
                                    <span className={styles.refTitle}>문서</span>
                                    <div className={styles.docActions}>
                                        <span className={styles.docTools}>
                                            <button
                                                type="button"
                                                className={styles.aiButton}
                                                disabled
                                            >
                                                <Icon name="sparkle" />
                                                문서 추출
                                            </button>
                                        </span>
                                    </div>
                                </div>
                                <div className={styles.docArea}>
                                    <div className={styles.docRow} inert aria-hidden>
                                        <table className={styles.docTable} aria-label="올린 문서">
                                            <tbody>
                                                {[0, 1, 2].map((i) => (
                                                    <tr key={i}>
                                                        <td colSpan={2} className={styles.addCell}>
                                                            <span className={styles.addRow}>
                                                                + 문서 추가하기
                                                            </span>
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                    <label className={styles.dropzone} data-disabled>
                                        <input
                                            type="file"
                                            className="sr-only"
                                            aria-label="공연 문서"
                                            accept=".pdf,.txt,.md"
                                            multiple
                                            disabled
                                        />
                                        <span className={styles.dropTitle}>+ 문서 업로드</span>
                                        <span className={styles.dropHint}>
                                            데모 공연에서는 문서를 올릴 수 없습니다
                                        </span>
                                    </label>
                                </div>
                            </section>
                        </div>
                        <ReferenceFiles
                            storageKey="demo-plan"
                            zones={draft.zones.map((z) => z.name)}
                        />
                    </div>
                    <div className={styles.planMain}>
                        <DraftEditor
                            title={ev.name}
                            owner={nameOf(d, me ?? 'm1')}
                            value={draft}
                            change={(v) => {
                                setDraft(v);
                                setSaveNote(null);
                            }}
                            disabled={false}
                            note={
                                saveNote && (
                                    <p className="small" role="alert">
                                        {saveNote}
                                    </p>
                                )
                            }
                            actions={
                                <>
                                    {/* 데모 계획은 이 브라우저에서만 바뀐다 */}
                                    {dirty && (
                                        <button
                                            type="button"
                                            className={report.barIcon}
                                            aria-label="편집 취소"
                                            title="편집 취소(저장한 계획으로 되돌리기)"
                                            onClick={() => {
                                                setDraft(baseline);
                                                setSaveNote(null);
                                            }}
                                        >
                                            <Icon name="back" />
                                        </button>
                                    )}
                                    <button
                                        type="button"
                                        className={report.barIcon}
                                        aria-label="저장하기"
                                        title="저장하기"
                                        data-primary={dirty ? '' : undefined}
                                        disabled={!dirty}
                                        onClick={() => {
                                            const next = tidyDraft(draft);
                                            const problem = draftProblem(next, {
                                                relations: false,
                                            });
                                            setSaveNote(problem);
                                            if (problem) return;
                                            dispatch({
                                                type: 'replacePlan',
                                                plan: planFromDraft(next, EVENT_DATE),
                                            });
                                            setDraft(next);
                                            setBaseline(next);
                                        }}
                                    >
                                        <Icon name="save" />
                                    </button>
                                </>
                            }
                        />
                    </div>
                </div>
            </div>
        </PlainFrame>
    );
}
