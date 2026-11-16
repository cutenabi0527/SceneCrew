import { Fragment, type CSSProperties, type Ref } from 'react';
import { useData, useEvent, useSession } from '../../domain/hooks';
import { useFloorView } from './shared-floor-plan';
import {
    OBS_FRESH_LABEL,
    OBS_QUALITY_LABEL,
    ZONE_STATE_LABEL,
    zoneReading,
} from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import { useLiveFloorPlan } from '../../live/safety-link';
import type { SafetyStep, Task } from '../../domain/types';
import { Chip, type ChipKind } from '../../shared/ui/atoms';
import styles from './safety.module.css';

/** 점 1개가 나타내는 추정 인원(집계 범례). 개인 좌표가 아니다. */
export const PER_DOT = 2;

const STATE_CHIP: Record<keyof typeof ZONE_STATE_LABEL, ChipKind> = {
    OVER: 'bad',
    NEAR: 'warn',
    OK: 'ok',
    CHECK: '',
};

export function ZoneStateChip({ zoneId }: { zoneId: string }) {
    const d = useData();
    const r = zoneReading(d, zoneId);
    return <Chip kind={STATE_CHIP[r.state]}>{ZONE_STATE_LABEL[r.state]}</Chip>;
}

/** 같은 입력이면 같은 자리에 점을 찍는다(새로 그릴 때마다 흔들리지 않게) */
function dots(seed: string, n: number) {
    let h = [...seed].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7);
    const rnd = () => {
        h = (h * 1103515245 + 12345) >>> 0;
        return (h % 1000) / 1000;
    };
    return Array.from({ length: n }, () => [8 + rnd() * 84, 18 + rnd() * 70]);
}

/**
 * 안전 평면도(W05): 확인된 평면도 위에 구역별 추정 점·추정 인원·기준 대비 상태를 보인다.
 * 미관측·낡음·실패 구역은 점을 찍지 않고 확인 필요로 표시한다.
 */
export function FloorMap({
    selected,
    onSelect,
    frameRef,
}: {
    selected?: string;
    onSelect?: (zoneId: string) => void;
    /** 평면도 화면(고정 크기 틀). 이미지를 자를 때 이 비율을 쓴다 */
    frameRef?: Ref<HTMLDivElement>;
}) {
    const d = useData();
    // V9-D62: 실서버는 발행 때 저장한 PDF 평면도 그림을 먼저 쓴다. 없으면 계획 수정에서 올린 평면도 그림
    // (이 브라우저)·데모 그림을 쓰고, 구역 상자는 계획 수정에서 맞춘 위치를 따른다
    const live = useLiveFloorPlan();
    const floor = useFloorView();
    const zones = floor.zones;
    if (!zones.length && !live && !floor.plan)
        return (
            <p className="small">
                확인된 평면도/구역이 없습니다. 구역과 카메라 보정을 먼저 확인해주세요.
            </p>
        );
    const plan = live ?? floor.plan;
    // 평면도 화면은 노트북 한 화면에 들어오는 고정 크기 틀이고, 그 안에서 이미지는 원본 비율 그대로
    // 틀에 맞게 줄인다(세로가 넘치면 세로에 맞춘다). 구역 위치는 이미지 기준 %다.
    // V9-D62: 그림 안의 평면도 영역(crop)이 있으면 그 영역만 크게 보인다. 구역 위치는 그림 전체 기준 %라 영역 기준으로 옮긴다
    const view = plan?.crop ?? { x: 0, y: 0, width: 1, height: 1 };
    const ratio = plan ? (plan.width * view.width) / (plan.height * view.height) : 4 / 3;
    const place = ([x, y, w, h]: number[]) => ({
        left: `${(x - view.x * 100) / view.width}%`,
        top: `${(y - view.y * 100) / view.height}%`,
        width: `${w / view.width}%`,
        height: `${h / view.height}%`,
    });
    const backdrop = plan
        ? {
              backgroundImage: `url(${plan.src})`,
              backgroundSize: `${100 / view.width}% ${100 / view.height}%`,
              backgroundPosition: `${view.width < 1 ? (view.x / (1 - view.width)) * 100 : 0}% ${
                  view.height < 1 ? (view.y / (1 - view.height)) * 100 : 0
              }%`,
          }
        : undefined;
    return (
        <div ref={frameRef} className={styles.frame} style={{ '--ar': ratio } as CSSProperties}>
            <div
                className={styles.map}
                role="group"
                aria-label="안전 평면도"
                data-plan={plan ? true : undefined}
                style={backdrop}
            >
                {zones.map((z) => {
                    const r = zoneReading(d, z.id);
                    const box = place(z.rect);
                    const n = r.usable ? Math.round((r.obs?.count ?? 0) / PER_DOT) : 0;
                    const reading = r.usable
                        ? `추정 ${r.obs?.count}명`
                        : r.obs?.count != null
                          ? `마지막 ${r.obs.count}명 / ${OBS_FRESH_LABEL[r.obs.fresh]}`
                          : '미관측';
                    return (
                        <Fragment key={z.id}>
                            <button
                                type="button"
                                className={styles.zone}
                                data-state={r.state}
                                data-stage={z.id === 'z-stage' || undefined}
                                aria-pressed={selected === z.id}
                                style={box}
                                onClick={() => onSelect?.(z.id)}
                            >
                                {dots(z.id, n).map(([dx, dy], k) => (
                                    <i
                                        key={k}
                                        className={styles.dot}
                                        style={{ left: `${dx}%`, top: `${dy}%` }}
                                        aria-hidden="true"
                                    />
                                ))}
                                {/* 배경 그림이 있으면 PDF의 구역 이름과 겹치지 않게 칸 안 글자는 화면에서만 숨기고 바깥 이름표로 보인다 */}
                                <span className={plan ? 'sr-only' : styles.zoneLabel}>
                                    <b>{z.name}</b>
                                    <span>{reading}</span>
                                </span>
                            </button>
                            {plan && (
                                <span
                                    className={styles.zoneChip}
                                    data-inside={parseFloat(box.top) < 8 || undefined}
                                    style={{ left: box.left, top: box.top }}
                                    aria-hidden="true"
                                >
                                    <b>{z.name}</b> <span>{reading}</span>
                                </span>
                            )}
                        </Fragment>
                    );
                })}
                <span className={styles.legend}>
                    <i className={styles.dot} aria-hidden="true" /> 점 1개 = 추정 {PER_DOT}명(개인
                    위치가 아닙니다)
                </span>
            </div>
        </div>
    );
}

/** 구역 관측 상세: 추정 인원·실면적·명/㎡·기준 대비·관측 시각/품질/최신성을 각각 표시한다 */
export function ZoneFacts({ zoneId }: { zoneId: string }) {
    const d = useData();
    const r = zoneReading(d, zoneId);
    if (!r.zone) return null;
    const cam = d.cameras.find((c) => c.id === r.obs?.cameraId);
    return (
        <dl className="kv">
            <dt>추정 인원</dt>
            <dd>{r.obs?.count != null ? `${r.obs.count}명` : '미관측'}</dd>
            <dt>실면적</dt>
            <dd>{r.zone.area != null ? `${r.zone.area}㎡` : '확인 필요'}</dd>
            <dt>밀집도</dt>
            <dd>{r.density != null ? `${r.density.toFixed(2)}명/㎡` : '확인 필요'}</dd>
            <dt>기준 대비</dt>
            <dd className="row">
                <ZoneStateChip zoneId={zoneId} />
                {r.zone.limit != null && <span className="xs muted">기준 {r.zone.limit}명/㎡</span>}
            </dd>
            <dt>관측 시각</dt>
            <dd className="mono">{r.obs?.at ?? '시각 불명'}</dd>
            <dt>품질</dt>
            <dd>
                {r.obs
                    ? `${OBS_QUALITY_LABEL[r.obs.quality]} / ${OBS_FRESH_LABEL[r.obs.fresh]}`
                    : '미관측'}
            </dd>
            <dt>카메라</dt>
            <dd>{cam?.name ?? '없음'}</dd>
            <dt>최소 유지</dt>
            <dd>
                {r.zone.minStaff}명{r.zone.essential ? ` / ${r.zone.essential}` : ''}
            </dd>
        </dl>
    );
}

/**
 * 업무 상세의 안전 수행(M05): 담당 구역의 최소 집계·시각·품질만 보이고(전체 지도·카메라 없음),
 * 도착과 현장 확인을 따로 보고한다.
 */
export function TaskSafetySection({ task: t }: { task: Task }) {
    const d = useData();
    const { me } = useSession();
    const ev = useEvent();
    const dispatch = useDispatch();
    if (!t.safety) return null;
    const s = t.safety;
    const r = zoneReading(d, s.zoneId);
    // 종료(ENDED) 뒤에는 읽기만 한다: 체크·시작/완료·수행 기록을 막는다
    const mine =
        ev.state === 'LIVE' && t.assignee === me && t.status !== 'done' && t.status !== 'canceled';
    const steps: [SafetyStep, string][] = [
        ['arrive', '도착'],
        ['check', '현장 확인'],
    ];
    const next = steps.find(([k]) => !s.records[k])?.[0];
    return (
        <div className="stack s6">
            <dl className="kv">
                <dt>담당 구역</dt>
                <dd>{r.zone?.name}</dd>
                <dt>추정 인원</dt>
                <dd>
                    {r.usable ? `${r.obs?.count}명` : '확인 필요'}{' '}
                    <ZoneStateChip zoneId={s.zoneId} />
                </dd>
                <dt>관측</dt>
                <dd className="xs">
                    {r.obs?.at ?? '시각 불명'} /{' '}
                    {r.obs
                        ? `${OBS_QUALITY_LABEL[r.obs.quality]} / ${OBS_FRESH_LABEL[r.obs.fresh]}`
                        : '미관측'}
                </dd>
                {s.keep && (
                    <>
                        <dt>유지 조건</dt>
                        <dd>{s.keep}</dd>
                    </>
                )}
            </dl>
            <ol className={styles.steps}>
                {steps.map(([k, label]) => (
                    <li key={k} data-done={!!s.records[k]}>
                        <span className="grow">
                            <b>{label}</b>{' '}
                            <span className="xs muted">
                                {s.records[k] ? `${s.records[k]} 보고` : '보고 전'}
                            </span>
                        </span>
                        {mine && k === next && (
                            <button
                                type="button"
                                className="btn sm primary"
                                onClick={() =>
                                    dispatch({ type: 'safetyRecord', taskId: t.id, step: k })
                                }
                            >
                                {label} 보고
                            </button>
                        )}
                    </li>
                ))}
            </ol>
            <p className="xs muted">
                관측 수치는 참고용 추정입니다. 이동/통제 지시는 총괄 승인 뒤에만 따릅니다.
            </p>
        </div>
    );
}
