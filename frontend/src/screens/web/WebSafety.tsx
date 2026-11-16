import { useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useData, useEvent, useViewer } from '../../domain/hooks';
import {
    ago,
    cameraStates,
    cameraUsable,
    cameraViewable,
    nameOf,
    supportCandidates,
} from '../../domain/selectors';
import { newId, useDemoState, useDispatch } from '../../domain/store-context';
import { CameraLinkDesk } from '../../features/camera-link/client';
import { CameraFeed, CameraSplit } from '../../features/safety/CameraWall';
import { FloorPlanEditor } from '../../features/safety/FloorPlanEditor';
import { useFloorView } from '../../features/safety/shared-floor-plan';
import { DesignFloorEditor } from '../../features/safety-map/DesignFloorEditor';
import { FloorMap, ZoneFacts, ZoneStateChip } from '../../features/safety/SafetyParts';
import { CameraSettings as LiveCameraSettings } from '../../features/safety-map/SafetySettings';
import { SupportPanel } from '../../features/safety-map/SupportPanel';
import { useLive } from '../../live/context';
import { supportTargets, useLiveSafety, type LiveSafetyLink } from '../../live/safety-link';
import { Chip, Empty, Note, Segmented } from '../../shared/ui/atoms';
import styles from './ops.module.css';

type SafetyView = 'status' | 'cameras';

/** 카메라 분할 화면을 브라우저 새 창으로 연다(같은 창이면 주소를 바꿔 앞으로 가져온다). 구역을 주면 그 구역 카메라만 */
function openCameraWindow(zoneId?: string) {
    const url = zoneId ? `/cameras?zone=${encodeURIComponent(zoneId)}` : '/cameras';
    window.open(url, 'scenecrew-cameras', 'width=1280,height=820')?.focus();
}

/**
 * W05 안전(총괄 WEB): 고정 카메라 관측의 평면도·구역별 추정 점/인원·밀집도·관측 시각/품질과
 * 현재 미리보기(일시 세션·미녹화), 안전 지원 후보를 한 화면에 둔다.
 * 관측만으로 이슈·이동·해결을 자동 적용하지 않고, 지원은 변경안(X01) 승인 뒤에만 전달된다.
 * 값은 v9 목표 계약(EXT10~26)의 예시 데이터다.
 */
export function WebSafety() {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const navigate = useNavigate();
    const dispatch = useDispatch();
    const link = useLiveSafety();
    const [params, setParams] = useSearchParams();
    // 오른쪽 위 토글로 바꾸는 화면. 새로고침·뒤로 가기에도 유지되게 주소에 둔다
    const view: SafetyView = params.get('view') === 'cameras' ? 'cameras' : 'status';
    const changeView = (next: SafetyView) =>
        setParams((prev) => {
            const nextParams = new URLSearchParams(prev);
            if (next === 'cameras') nextParams.set('view', 'cameras');
            else nextParams.delete('view');
            return nextParams;
        });
    // 주소의 구역이 없으면(지웠거나 처음 열 때) 남아 있는 첫 구역을 고른다
    const wanted = params.get('zone');
    const zoneId =
        d.safetyZones.find((z) => z.id === wanted)?.id ??
        d.safetyZones.find((z) => z.id === 'z-a')?.id ??
        d.safetyZones[0]?.id ??
        '';
    // 평면도 올리기·구역 상자 편집 팝업
    const [planEdit, setPlanEdit] = useState(false);
    // 평면도 틀(고정 크기)의 가로세로 비율: "평면도 비율"로 자를 때 쓴다
    const frame = useRef<HTMLDivElement>(null);
    const [frameRatio, setFrameRatio] = useState(4 / 3);
    const openPlanEdit = () => {
        const r = frame.current?.getBoundingClientRect();
        if (r && r.height > 0) setFrameRatio(r.width / r.height);
        setPlanEdit(true);
    };
    const zone = d.safetyZones.find((z) => z.id === zoneId);
    const floorPlan = useFloorView().plan;
    const zoneCams = d.cameras.filter((c) => c.zones.includes(zoneId));
    // 영상은 총괄·참여 모집 또는 운영 중 행사·연결된 활성 카메라일 때만 보인다(권한 회수·종료·비활성 시 닫힘, C09)
    const live = cameraViewable(ev.state);

    if (!v.isChief) return <Empty icon="lock">안전 화면은 총괄만 볼 수 있습니다</Empty>;

    // 안전 현황 / 카메라 전환. 안전 현황에서는 위 여백 없이 평면도 줄 오른쪽에 둔다
    const viewSwitch = (
        <Segmented
            label="안전 화면 전환"
            value={view}
            items={[
                ['status', '안전 현황'],
                ['cameras', '카메라'],
            ]}
            onChange={changeView}
        />
    );
    // 화면 제목은 디자인상 숨기지만 스크린 리더가 화면을 구분할 수 있게 남긴다
    const title = <h1 className="sr-only">안전</h1>;
    const header = (
        <>
            {title}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
                {viewSwitch}
            </div>
        </>
    );

    if (view === 'cameras')
        return (
            <div className="stack">
                {header}
                <CameraView />
            </div>
        );

    if (!zone)
        return (
            <div className="stack">
                {header}
                {/* 구역이 아직 없어도 올려 둔 평면도 그림은 보인다 */}
                {floorPlan && <FloorMap frameRef={frame} />}
                <Empty>평면도에 구역이 없습니다. 평면도를 올리거나 구역 상자를 추가하세요.</Empty>
                <button
                    type="button"
                    className="btn"
                    disabled={ev.state === 'ENDED'}
                    onClick={openPlanEdit}
                >
                    평면도 편집
                </button>
                {planEdit && (
                    <PlanEditor frameRatio={frameRatio} onClose={() => setPlanEdit(false)} />
                )}
            </div>
        );

    const cands = supportCandidates(d, zoneId);
    const issues = d.issues.filter((i) => i.zone === zone.name && i.status !== 'CLOSED');
    const pending = d.changes.find(
        (c) =>
            c.status === 'READY' &&
            c.source === '안전 / 지원 후보' &&
            c.title.startsWith(zone.name),
    );

    return (
        <div className="stack">
            {title}

            <div className={styles.safety}>
                <section className="stack s6">
                    <div className="row between">
                        <b className="small">평면도</b>
                        <span className="row">
                            {viewSwitch}
                            <button
                                type="button"
                                className="btn sm"
                                disabled={ev.state === 'ENDED'}
                                onClick={openPlanEdit}
                            >
                                평면도 편집
                            </button>
                        </span>
                    </div>
                    <FloorMap
                        selected={zoneId}
                        onSelect={(id) => setParams({ zone: id })}
                        frameRef={frame}
                    />
                </section>

                <aside className={styles.side} aria-label={`${zone.name} 상세`}>
                    <div className="row between">
                        <b>{zone.name}</b>
                        <ZoneStateChip zoneId={zoneId} />
                    </div>
                    <ZoneFacts zoneId={zoneId} />

                    {/* 이 구역을 비추는 카메라를 세로로 모두 보여준다. 일시 세션이며 영상과 집계 시각을 함께 둔다 */}
                    {zoneCams.length ? (
                        <section className="stack s6" aria-label="이 구역 카메라">
                            <span className="lbl">이 구역 카메라 {zoneCams.length}</span>
                            {zoneCams.map((c) => (
                                <CameraFeed
                                    key={c.id}
                                    camera={c}
                                    large
                                    compact
                                    available={live}
                                    onOpen={live ? () => openCameraWindow(zoneId) : undefined}
                                />
                            ))}
                        </section>
                    ) : (
                        <Note kind="warn">
                            이 구역을 비추는 카메라가 없습니다 / 현재 영상을 볼 수 없습니다. 현장
                            확인으로 판단하세요.
                        </Note>
                    )}

                    {issues.length > 0 && (
                        <div className="stack s6">
                            <span className="lbl">이 구역 보고</span>
                            {issues.map((i) => (
                                <button
                                    key={i.id}
                                    type="button"
                                    className="btn sm"
                                    onClick={() => navigate(`/e/response/${i.id}`)}
                                >
                                    {i.title} / {ago(i.at)} 전
                                </button>
                            ))}
                        </div>
                    )}
                </aside>
            </div>

            {/* 실서버: 배치도에 도형이 없는 구역을 구역 이름으로 그렸다(#372) */}
            {d.safetyDefaultLayout && (
                <Note>
                    평면도에 도형이 없는 구역은 구역 이름으로 기본 배치했습니다. 평면도 편집에서
                    실제 위치에 맞게 옮기고 저장하세요.
                </Note>
            )}

            {/* 안전 지원 후보. 실서버는 서버 규칙으로 고른 후보에서 골라 지원안을 만든다(#361·#372) */}
            {link ? (
                <LiveSupport key={zoneId} link={link} zoneId={zoneId} />
            ) : (
                <section className="stack" aria-label="안전 지원 후보">
                    <b>{zone.name} 지원 후보</b>
                    {pending && (
                        <Note kind="warn">
                            이 구역에 확정 대기 중인 지원 변경안이 있어 새 변경안을 만들 수
                            없습니다. 먼저 확정하거나 폐기하세요: {pending.title}{' '}
                            <button
                                type="button"
                                className="linkbtn"
                                onClick={() => navigate(`/e/changes/${pending.id}`)}
                            >
                                변경안 보기
                            </button>
                        </Note>
                    )}
                    {cands.length ? (
                        <div className={styles.candWrap}>
                            <table className={`${styles.crewTable} ${styles.candTable}`}>
                                <thead>
                                    <tr>
                                        <th>이름</th>
                                        <th>현재 위치</th>
                                        <th>이동 시간</th>
                                        <th>근거</th>
                                        <th>지원</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {cands.map((c) => (
                                        <tr key={c.member.id}>
                                            <td>
                                                <b>
                                                    {c.member.name}
                                                    {c.member.isReserve && ' (예비)'}
                                                </b>
                                            </td>
                                            <td>{c.from ?? '위치 미확인'}</td>
                                            <td>약 {c.eta}분</td>
                                            <td>{c.reason}</td>
                                            <td>
                                                <span className={styles.candAction}>
                                                    {/* 버튼이 있으면 지원할 수 있다는 뜻이라 지원 가능은 따로 표시하지 않는다 */}
                                                    {c.verdict !== 'OK' && (
                                                        <Chip
                                                            kind={
                                                                c.verdict === 'CONDITIONAL'
                                                                    ? 'warn'
                                                                    : 'bad'
                                                            }
                                                        >
                                                            {c.verdict === 'CONDITIONAL'
                                                                ? '조건부'
                                                                : '제외'}
                                                        </Chip>
                                                    )}
                                                    {c.verdict !== 'NO' && (
                                                        <button
                                                            type="button"
                                                            className="btn primary"
                                                            disabled={
                                                                !!pending || ev.state !== 'LIVE'
                                                            }
                                                            title={
                                                                pending
                                                                    ? '이 구역에 확정 대기 중인 지원 변경안이 있어 새로 만들 수 없습니다'
                                                                    : ev.state !== 'LIVE'
                                                                      ? '운영 중인 행사에서만 만들 수 있습니다'
                                                                      : undefined
                                                            }
                                                            onClick={() => {
                                                                const id = newId('C');
                                                                dispatch({
                                                                    type: 'safetyChange',
                                                                    zoneId,
                                                                    memberId: c.member.id,
                                                                    id,
                                                                });
                                                                navigate(`/e/changes/${id}`);
                                                            }}
                                                        >
                                                            지원 변경안
                                                        </button>
                                                    )}
                                                </span>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    ) : (
                        <Empty>
                            지원할 수 있는 후보가 없습니다. 필요한 조건을 확인하고 수동으로
                            대응하세요.
                        </Empty>
                    )}
                </section>
            )}

            {planEdit && <PlanEditor frameRatio={frameRatio} onClose={() => setPlanEdit(false)} />}
        </div>
    );
}

/** 평면도 편집 창. 실서버는 계획 구역의 도형 위치·크기만 고친다(#372) */
function PlanEditor(props: { frameRatio: number; onClose: () => void }) {
    return useDemoState().live ? <DesignFloorEditor {...props} /> : <FloorPlanEditor {...props} />;
}

/** 토글의 카메라 화면: 전체 분할 화면, 모든 카메라의 상태, 분할 새 창, 설정·보정 */
function CameraView() {
    const d = useData();
    const link = useLiveSafety();
    const ev = useEvent();
    return (
        <div className="stack">
            <CameraSplit />
            <div className="stack s6">
                <div className="row between">
                    <b className="small">카메라 상태</b>
                    <button
                        type="button"
                        className="btn sm"
                        disabled={!cameraViewable(ev.state)}
                        onClick={() => openCameraWindow()}
                    >
                        카메라 화면 새 창 ({d.cameras.length})
                    </button>
                </div>
                {/* 연결·분석 최신성은 따로 표시하고 자세한 시각은 마우스를 올리면 */}
                <div className={styles.camStrip} role="list" aria-label="카메라 상태">
                    {d.cameras.map((c) => {
                        const o = d.observations.find((x) => x.cameraId === c.id);
                        const states = cameraStates(c, o);
                        const worst = states.some((st) => st.kind === 'bad')
                            ? 'bad'
                            : states.some((st) => st.kind === 'warn' || st.kind === '')
                              ? 'warn'
                              : 'ok';
                        return (
                            <span
                                key={c.id}
                                role="listitem"
                                className={styles.camPill}
                                data-kind={worst}
                                title={[
                                    `${c.name}: ${states.map((st) => st.label).join(' / ')}`,
                                    `영상 ${c.frameAt ?? '-'} / 집계 ${o?.at ?? '-'}${c.calibrated ? ` / 보정 ${c.calibratedAt}` : ''}`,
                                    c.calibrationInvalid
                                        ? `${c.calibrationInvalid} / 재보정 전 추정은 확인 필요`
                                        : '',
                                    !cameraUsable(c) && c.zones.length
                                        ? '마지막 관측 이후 현재 상태는 현장 확인 필요'
                                        : '',
                                ]
                                    .filter(Boolean)
                                    .join(' / ')}
                            >
                                <i aria-hidden="true" />
                                <b>{c.name}</b> {states.map((st) => st.label).join(', ')}
                            </span>
                        );
                    })}
                </div>
            </div>
            {link ? <LiveCameraSettingsPanel link={link} /> : <CameraSettings />}
        </div>
    );
}

/** 실서버 안전 인력 지원(#361): 기능 화면의 지원 부품을 그대로 쓴다. 고른 구역을 처음 대상으로 둔다 */
function LiveSupport({ link, zoneId }: { link: LiveSafetyLink; zoneId: string }) {
    const { reload } = useLive();
    return (
        <SupportPanel
            reader={link.reader}
            responder={link.responder}
            targets={supportTargets(link, zoneId)}
            zones={link.zones}
            groups={link.groups}
            members={link.members}
            eventRevision={link.eventRevision}
            onRefresh={() => reload().catch(() => undefined)}
        />
    );
}

/**
 * 실서버 S01 카메라 설정(#372·#397): 기능 화면의 카메라 설정을 쓴다. 웹 연결 칸 추가·연결 담당 업무 지정,
 * 현재 화면 위 관측 구역 그리기, 칸마다 상태(등록 안됨·입력 종류·연결한 사람·보정 필요), 총괄의 연결·넘겨받기.
 * 보정 확인·이동/줌 표시는 서버 명령이 없어 실서버에서는 보이지 않는다.
 */
function LiveCameraSettingsPanel({ link }: { link: LiveSafetyLink }) {
    const { reload } = useLive();
    const d = useData();
    const desk = useMemo(
        () => new CameraLinkDesk(link.client.session, link.client.scope),
        [link.client],
    );
    const tasks = d.tasks
        .filter((t) => t.status !== 'canceled')
        .map((t) => ({ id: t.id, title: `${t.title} (${nameOf(d, t.assignee)})` }));
    const names = new Map(link.members.map((m) => [m.memberId, m.displayName]));
    return (
        <LiveCameraSettings
            commands={link.commands}
            map={link.map}
            cameras={link.cameras}
            zones={link.zones}
            eventRevision={link.eventRevision}
            onChanged={() => void reload().catch(() => undefined)}
            reader={link.reader}
            tasks={tasks}
            memberName={(id) => names.get(id) ?? '알 수 없는 사람'}
            desk={desk}
        />
    );
}

/**
 * S01 고정 카메라 설정·보정(총괄): 연결/재연결·구역 보정 확인·이동/줌 표시(보정 무효)·활성/비활성.
 * 연결·보정 직후 관측은 분석 대기이며, 신고자에게 사진/영상 촬영을 요구하지 않는다.
 */
function CameraSettings() {
    const d = useData();
    const ev = useEvent();
    const dispatch = useDispatch();
    const editable = ev.state !== 'ENDED';
    return (
        <section className={`${styles.recovery} stack`} aria-label="카메라 설정/보정">
            <b>카메라 설정/보정</b>
            <table className={styles.crewTable}>
                <thead>
                    <tr>
                        <th>카메라</th>
                        <th>상태</th>
                        <th>관측 구역</th>
                        <th>보정</th>
                        <th>조작</th>
                    </tr>
                </thead>
                <tbody>
                    {d.cameras.map((c) => {
                        const inactive = c.active === false;
                        return (
                            <tr key={c.id}>
                                <td>{c.name}</td>
                                <td>
                                    {cameraStates(c)
                                        .map((st) => st.label)
                                        .join(' / ')}
                                </td>
                                <td>
                                    {c.zones.length
                                        ? c.zones
                                              .map(
                                                  (z) =>
                                                      d.safetyZones.find((x) => x.id === z)?.name,
                                              )
                                              .join(', ')
                                        : '구역 미연결'}
                                </td>
                                <td>
                                    {c.calibrationInvalid
                                        ? `무효 / ${c.calibrationInvalid}`
                                        : c.calibrated
                                          ? `완료 ${c.calibratedAt}`
                                          : '보정 필요'}
                                </td>
                                <td>
                                    <span className="row">
                                        {c.link !== 'CONNECTED' && (
                                            <button
                                                type="button"
                                                className="btn sm"
                                                disabled={!editable || inactive}
                                                onClick={() =>
                                                    dispatch({
                                                        type: 'cameraLink',
                                                        cameraId: c.id,
                                                        link:
                                                            c.link === 'CONNECTING'
                                                                ? 'CONNECTED'
                                                                : 'CONNECTING',
                                                    })
                                                }
                                            >
                                                {c.link === 'CONNECTING' ? '연결 확인' : '연결'}
                                            </button>
                                        )}
                                        <button
                                            type="button"
                                            className="btn sm"
                                            disabled={
                                                !editable || inactive || c.link !== 'CONNECTED'
                                            }
                                            onClick={() =>
                                                dispatch({
                                                    type: 'cameraCalibrate',
                                                    cameraId: c.id,
                                                })
                                            }
                                        >
                                            보정 확인
                                        </button>
                                        {c.calibrated && !c.calibrationInvalid && (
                                            <button
                                                type="button"
                                                className="btn sm ghost"
                                                disabled={!editable}
                                                onClick={() =>
                                                    dispatch({
                                                        type: 'cameraInvalidate',
                                                        cameraId: c.id,
                                                        reason: '카메라 이동/줌 변경',
                                                    })
                                                }
                                            >
                                                이동/줌 변경 표시
                                            </button>
                                        )}
                                        <button
                                            type="button"
                                            className="btn sm ghost"
                                            disabled={!editable}
                                            onClick={() =>
                                                dispatch({
                                                    type: 'cameraActive',
                                                    cameraId: c.id,
                                                    active: inactive,
                                                })
                                            }
                                        >
                                            {inactive ? '활성' : '비활성'}
                                        </button>
                                    </span>
                                </td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </section>
    );
}
