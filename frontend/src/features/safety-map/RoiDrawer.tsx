import { useRef, useState, type PointerEvent } from 'react';
import type { SafetyReader } from './client';
import type { Point } from './contract';
import { useSnapshot } from './useSnapshot';

// 보정 그리기(#397, V9-D58): 서버의 현재 화면(snapshot) 위에서 구역마다 화면 속 영역을 끌어 그린다.
// 끌어 만든 사각형을 0~1 좌표 네 점으로 저장한다. 현재 화면이 없으면 그릴 수 없고 좌표 입력만 남는다.

export interface RoiShape {
    key: string;
    label: string;
    points: Point[] | null;
    active: boolean;
}

const clamp = (v: number) => Math.min(1, Math.max(0, Math.round(v * 1000) / 1000));

/** 두 점으로 사각형 네 점(왼쪽 위부터 시계 방향) */
function rectPoints(a: Point, b: Point): Point[] {
    const x1 = Math.min(a.x, b.x);
    const x2 = Math.max(a.x, b.x);
    const y1 = Math.min(a.y, b.y);
    const y2 = Math.max(a.y, b.y);
    return [
        { x: x1, y: y1 },
        { x: x2, y: y1 },
        { x: x2, y: y2 },
        { x: x1, y: y2 },
    ];
}

/** 너무 작은 사각형(실수로 누른 것)은 쓰지 않는다 */
const MIN_SIZE = 0.02;

export function RoiDrawer({
    reader,
    cameraId,
    cameraName,
    shapes,
    onDraw,
}: {
    reader: SafetyReader;
    cameraId: string;
    cameraName: string;
    shapes: RoiShape[];
    /** 지금 고른(active) 관측 구역의 새 영역 */
    onDraw: (points: Point[]) => void;
}) {
    const { frame, status } = useSnapshot(reader, cameraId);
    const box = useRef<SVGSVGElement>(null);
    const [start, setStart] = useState<Point | null>(null);
    const [draft, setDraft] = useState<Point[] | null>(null);
    const active = shapes.find((s) => s.active);

    const at = (e: PointerEvent): Point => {
        const r = box.current!.getBoundingClientRect();
        return {
            x: clamp((e.clientX - r.left) / r.width),
            y: clamp((e.clientY - r.top) / r.height),
        };
    };
    const down = (e: PointerEvent<SVGSVGElement>) => {
        if (!active) return;
        e.currentTarget.setPointerCapture?.(e.pointerId);
        const p = at(e);
        setStart(p);
        setDraft(rectPoints(p, p));
    };
    const move = (e: PointerEvent) => {
        if (start) setDraft(rectPoints(start, at(e)));
    };
    const up = (e: PointerEvent) => {
        if (!start) return;
        const points = rectPoints(start, at(e));
        setStart(null);
        setDraft(null);
        if (points[1].x - points[0].x >= MIN_SIZE && points[2].y - points[1].y >= MIN_SIZE)
            onDraw(points);
    };
    const poly = (points: Point[]) => points.map((p) => `${p.x},${p.y}`).join(' ');

    if (!frame)
        return (
            <p className="small" role="status">
                {status ||
                    '현재 화면이 없습니다. 담당자가 연결해 사진이 들어오면 화면 위에 구역을 그릴 수 있습니다.'}{' '}
                그 전에는 아래 좌표로 적을 수 있습니다.
            </p>
        );
    return (
        <div className="stack s6">
            <p className="small">
                {active
                    ? `${active.label}: 현재 화면에서 이 구역이 보이는 곳을 끌어 사각형으로 그리세요.`
                    : '그릴 관측 구역을 고르세요.'}
            </p>
            <div style={{ position: 'relative', maxWidth: 640, lineHeight: 0 }}>
                <img
                    src={frame.url}
                    alt={`${cameraName} 현재 프레임(보정용)`}
                    style={{ width: '100%', borderRadius: 8, userSelect: 'none' }}
                    draggable={false}
                />
                <svg
                    ref={box}
                    role="img"
                    aria-label={`${cameraName} 현재 화면 위 구역 그리기`}
                    viewBox="0 0 1 1"
                    preserveAspectRatio="none"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        cursor: active ? 'crosshair' : 'default',
                        touchAction: 'none',
                    }}
                    onPointerDown={down}
                    onPointerMove={move}
                    onPointerUp={up}
                    onPointerCancel={() => {
                        setStart(null);
                        setDraft(null);
                    }}
                >
                    {shapes.map(
                        (s) =>
                            s.points && (
                                <g key={s.key}>
                                    <polygon
                                        points={poly(s.points)}
                                        fill={
                                            s.active
                                                ? 'rgba(37,99,235,0.25)'
                                                : 'rgba(255,255,255,0.15)'
                                        }
                                        stroke={s.active ? '#2563eb' : '#ffffff'}
                                        strokeWidth={s.active ? 2 : 1}
                                        vectorEffect="non-scaling-stroke"
                                    >
                                        <title>{s.label}</title>
                                    </polygon>
                                </g>
                            ),
                    )}
                    {draft && (
                        <polygon
                            points={poly(draft)}
                            fill="rgba(37,99,235,0.2)"
                            stroke="#2563eb"
                            strokeDasharray="4 3"
                            strokeWidth={2}
                            vectorEffect="non-scaling-stroke"
                        />
                    )}
                </svg>
            </div>
        </div>
    );
}
