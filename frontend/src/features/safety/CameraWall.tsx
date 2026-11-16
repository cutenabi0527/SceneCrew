import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useData, useEvent } from '../../domain/hooks';
import {
    cameraStates,
    cameraStreamable as canStream,
    cameraViewable,
} from '../../domain/selectors';
import type { SafetyCamera } from '../../domain/types';
import { useLiveSafety } from '../../live/safety-link';
import type { SafetyReader } from '../safety-map/client';
import { useSnapshot } from '../safety-map/useSnapshot';
import { Chip, Empty } from '../../shared/ui/atoms';
import { Icon } from '../../shared/ui/Icon';
import { displayTime } from '../plan-workflow/time';
import styles from './camera.module.css';

/**
 * 카메라 한 대의 현재 미리보기 칸. 일시 세션이며 녹화·다운로드·과거 재생은 없다.
 * 영상 캡처 시각과 집계 기준 시각을 따로 적는다(영상이 보여도 분석은 늦을 수 있다).
 */
export function CameraFeed({
    camera: c,
    large = false,
    compact = false,
    available = true,
    onOpen,
}: {
    camera: SafetyCamera;
    large?: boolean;
    /** 아래 설명을 카메라 이름만 남긴다(상태 칩을 따로 보여주는 좁은 자리) */
    compact?: boolean;
    /** false면 운영 중이 아닌 행사라 연결돼 있어도 영상을 보여주지 않는다 */
    available?: boolean;
    /** 있으면 화면을 눌러 연다 */
    onOpen?: () => void;
}) {
    const d = useData();
    // 실서버 총괄이면 서버의 현재 프레임을 받아 보인다(#372). 데모는 예시 화면이다
    const link = useLiveSafety();
    const obs = d.observations.find((o) => o.cameraId === c.id);
    const zones = c.zones
        .map((z) => d.safetyZones.find((x) => x.id === z)?.name)
        .filter(Boolean)
        .join(', ');
    const screen = (
        <span className={styles.screen} role="img" aria-label={`${c.name} 현재 미리보기`}>
            {available && canStream(c) && link ? (
                <LiveFrame reader={link.reader} camera={c} />
            ) : available && canStream(c) ? (
                <>
                    <span className={styles.live}>
                        <i aria-hidden="true" /> 현재
                    </span>
                    <Icon name="target" />
                    <span>{c.name}</span>
                    <span className={styles.meta}>
                        캡처 {c.frameAt ?? '-'} / 집계 {obs?.at ?? '-'}
                    </span>
                </>
            ) : (
                <span className={styles.off}>
                    {!available
                        ? '운영 중이 아님'
                        : c.active === false
                          ? '비활성 카메라'
                          : c.unlinked
                            ? '등록 안됨(아직 아무도 연결하지 않음)'
                            : c.noFirstFrame
                              ? '첫 사진 없음(연결한 노트북을 확인하세요)'
                              : c.link === 'NONE'
                                ? '카메라 없음'
                                : c.link === 'CONNECTING'
                                  ? '연결 중'
                                  : '연결 끊김'}
                    <small>현재 영상을 볼 수 없습니다. 현장 확인으로 판단하세요.</small>
                </span>
            )}
        </span>
    );
    return (
        <figure className={`${styles.feed} ${large ? styles.large : ''}`}>
            {onOpen ? (
                <button
                    type="button"
                    className={styles.open}
                    aria-label={`${c.name} 화면 새 창으로 열기`}
                    onClick={onOpen}
                >
                    {screen}
                </button>
            ) : (
                screen
            )}
            <figcaption className={styles.caption}>
                <b className="small">{c.name}</b>
                {!compact && (
                    <>
                        <span className="row">
                            {cameraStates(c, obs).map((st) => (
                                <Chip key={st.label} kind={st.kind}>
                                    {st.label}
                                </Chip>
                            ))}
                        </span>
                        <span className="xs muted">관측 구역 {zones || '미연결'}</span>
                    </>
                )}
            </figcaption>
        </figure>
    );
}

/** 안전 화면 카메라 탭의 전체 분할 화면. 운영 중일 때만 영상이 보인다(일시 세션·미녹화) */
export function CameraSplit() {
    const d = useData();
    const ev = useEvent();
    if (!d.cameras.length) return <Empty>등록된 카메라가 없습니다</Empty>;
    return (
        <section
            className={styles.split}
            data-count={d.cameras.length}
            aria-label="전체 카메라 화면"
        >
            {d.cameras.map((c) => (
                <CameraFeed
                    key={c.id}
                    camera={c}
                    large
                    compact
                    available={cameraViewable(ev.state)}
                />
            ))}
        </section>
    );
}

/**
 * 모든 카메라 화면(W05·총괄): 크롬 새 창(/cameras)으로 열린다. 왼쪽은 고른 카메라들의 분할 화면,
 * 오른쪽은 모든 카메라 목록. 목록을 누르면 분할 화면에 넣고 빼며, "새 창"은 그 카메라 한 대를 다른 창으로 연다.
 * 모두 일시 세션이고 녹화하지 않는다. 창을 닫으면 세션이 끝난다.
 * 주소에 ?zone=구역이 있으면 처음에 그 구역을 비추는 카메라만 분할 화면에 넣는다.
 */
export function CameraWall() {
    const d = useData();
    const [params] = useSearchParams();
    const zone = d.safetyZones.find((z) => z.id === params.get('zone'));
    const [shown, setShown] = useState<string[]>(() =>
        d.cameras.filter((c) => !zone || c.zones.includes(zone.id)).map((c) => c.id),
    );
    const cams = d.cameras.filter((c) => shown.includes(c.id));
    const toggle = (id: string) =>
        setShown((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
    const openWindow = (id: string) =>
        window.open(`/camera/${encodeURIComponent(id)}`, `camera-${id}`, 'width=960,height=640');
    return (
        <div className={styles.page}>
            <header className="row between nw">
                <span className="row">
                    <b id="camera-wall-title" className="h3">
                        {zone ? `${zone.name} 카메라 화면` : '카메라 화면'}
                    </b>
                    <span className="xs muted">분할 {cams.length}대</span>
                </span>
                <button type="button" className="btn sm" onClick={() => window.close()}>
                    창 닫기
                </button>
            </header>
            <div className={styles.body}>
                <section
                    className={styles.grid}
                    data-count={Math.min(cams.length, 4)}
                    aria-label="분할 화면"
                >
                    {cams.length ? (
                        cams.map((c) => <CameraFeed key={c.id} camera={c} />)
                    ) : (
                        <p className={styles.empty}>오른쪽 목록에서 볼 카메라를 고르세요.</p>
                    )}
                </section>
                <aside className={styles.list} aria-label="모든 카메라">
                    <b className="small">모든 카메라 {d.cameras.length}</b>
                    <ul>
                        {d.cameras.map((c) => {
                            const on = shown.includes(c.id);
                            return (
                                <li key={c.id}>
                                    <button
                                        type="button"
                                        className={styles.pick}
                                        aria-pressed={on}
                                        onClick={() => toggle(c.id)}
                                    >
                                        <b className="small">{c.name}</b>
                                        <span className="xs muted">
                                            {cameraStates(c)
                                                .map((st) => st.label)
                                                .join(' / ')}
                                        </span>
                                        <span className="xs">
                                            {on ? '분할 화면에서 빼기' : '분할 화면에 넣기'}
                                        </span>
                                    </button>
                                    <button
                                        type="button"
                                        className="btn sm"
                                        disabled={!canStream(c)}
                                        onClick={() => openWindow(c.id)}
                                    >
                                        새 창
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                </aside>
            </div>
        </div>
    );
}

/**
 * 실서버 현재 프레임(#372). 화면이 보이는 동안만 2초마다 다시 받고, 녹화·저장하지 않는다.
 * 받을 수 있는 프레임이 없으면 이전 이미지를 남기지 않고 이유를 적는다.
 */
function LiveFrame({ reader, camera: c }: { reader: SafetyReader; camera: SafetyCamera }) {
    const { frame, status } = useSnapshot(reader, c.id);
    if (!frame)
        return (
            <span className={styles.off}>
                {c.name}
                <small role="status">{status}</small>
            </span>
        );
    return (
        <>
            <img className={styles.frame} src={frame.url} alt={`${c.name} 현재 프레임`} />
            <span className={styles.live}>
                <i aria-hidden="true" /> 현재
            </span>
            <span className={styles.meta}>
                촬영 {frame.capturedAt ? displayTime(frame.capturedAt) : '시각 미상'}
                {frame.freshness === 'FRESH' ? '' : ' / 오래된 프레임'}
            </span>
        </>
    );
}
