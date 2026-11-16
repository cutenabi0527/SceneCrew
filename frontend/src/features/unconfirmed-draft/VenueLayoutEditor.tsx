import { useRef, useState, type PointerEvent } from 'react';
import {
    clampRect,
    labelChip,
    labelSize,
    rectOf,
    type DraftLayout,
    type Rect,
    type ZoneRef,
} from './layout';
import type { Background } from './useBackground';

// 미확정 평면도 편집(#372, V9-D45). 도형을 끌어 옮기고 오른쪽 아래 모서리로 크기를 바꾼다. 같은 값을 숫자(%)로도 고칠 수 있다.
// V9-D62: PDF 평면도에서 읽은 경우 그 쪽 그림(background)을 바탕에 깔고 도형을 반투명하게 겹친다. 좌표는 그 그림 기준 0~1이다.
// 그림 안의 평면도 영역(crop)이 있으면 그 영역만 크게 보인다(viewBox). 저장하는 좌표는 쪽 전체 기준 그대로다.

type Drag = { zoneId: string; mode: 'move' | 'size'; px: number; py: number; start: Rect };

const pct = (v: number) => Math.round(v * 1000) / 10;

export function VenueLayoutEditor({
    layout,
    zones,
    disabled,
    change,
    background = null,
}: {
    layout: DraftLayout;
    zones: ZoneRef[];
    disabled: boolean;
    change: (zoneId: string, rect: Rect) => void;
    /** PDF 평면도 쪽 그림과 그 안의 평면도 영역(V9-D62). 없으면 회색 바탕에 도형만 그린다. */
    background?: Background | null;
}) {
    const svg = useRef<SVGSVGElement>(null);
    const drag = useRef<Drag | null>(null);
    const [selected, setSelected] = useState<string | null>(null);
    const nameOf = (zoneId: string) =>
        zones.find((z) => z.key.toLowerCase() === zoneId.toLowerCase())?.name ?? '계획에 없는 구역';
    const ratio = layout.height / layout.width;
    // 보일 영역(쪽 전체 기준 0~1). 평면도 영역이 있으면 그 영역만 크게 보인다
    const view = background?.crop ?? { x: 0, y: 0, width: 1, height: 1 };
    const shapes = layout.zonePolygons.map((p) => ({ zoneId: p.zoneId, rect: rectOf(p.points) }));
    const current = shapes.find((s) => s.zoneId === selected);

    const norm = (e: PointerEvent) => {
        const box = svg.current!.getBoundingClientRect();
        return {
            x: view.x + ((e.clientX - box.left) / box.width) * view.width,
            y: view.y + ((e.clientY - box.top) / box.height) * view.height,
        };
    };
    const down = (e: PointerEvent, zoneId: string, rect: Rect, mode: Drag['mode']) => {
        if (disabled) return;
        e.stopPropagation();
        (e.currentTarget as Element).setPointerCapture?.(e.pointerId);
        const p = norm(e);
        drag.current = { zoneId, mode, px: p.x, py: p.y, start: rect };
        setSelected(zoneId);
    };
    const move = (e: PointerEvent) => {
        const d = drag.current;
        if (!d) return;
        const p = norm(e);
        const dx = p.x - d.px;
        const dy = p.y - d.py;
        change(
            d.zoneId,
            clampRect(
                d.mode === 'move'
                    ? { ...d.start, x: d.start.x + dx, y: d.start.y + dy }
                    : { ...d.start, width: d.start.width + dx, height: d.start.height + dy },
            ),
        );
    };
    const up = () => {
        drag.current = null;
    };

    return (
        <div className="stack">
            <svg
                ref={svg}
                role="img"
                aria-label="평면도 도형"
                viewBox={`${view.x} ${view.y * ratio} ${view.width} ${view.height * ratio}`}
                preserveAspectRatio="none"
                style={{
                    width: '100%',
                    aspectRatio: `${layout.width * view.width} / ${layout.height * view.height}`,
                    background: 'var(--surface-2, #f3f4f6)',
                    border: '1px solid var(--line-2, #d1d5db)',
                    borderRadius: 8,
                    touchAction: 'none',
                }}
                onPointerMove={move}
                onPointerUp={up}
                onPointerLeave={up}
            >
                {background && (
                    <image
                        href={background.src}
                        x={0}
                        y={0}
                        width={1}
                        height={ratio}
                        preserveAspectRatio="none"
                        aria-label="PDF 평면도"
                        pointerEvents="none"
                    />
                )}
                {shapes.map(({ zoneId, rect }) => {
                    const on = zoneId === selected;
                    return (
                        <g key={zoneId} data-zone={zoneId}>
                            <rect
                                x={rect.x}
                                y={rect.y * ratio}
                                width={rect.width}
                                height={rect.height * ratio}
                                rx={0.008}
                                fill={on ? 'rgba(37,99,235,0.28)' : 'rgba(37,99,235,0.14)'}
                                stroke={on ? '#1d4ed8' : '#2563eb'}
                                strokeWidth={0.004}
                                style={{ cursor: disabled ? 'default' : 'move' }}
                                onPointerDown={(e) => down(e, zoneId, rect, 'move')}
                            />
                            {background ? (
                                <ZoneChip
                                    name={nameOf(zoneId)}
                                    rect={rect}
                                    ratio={ratio}
                                    on={on}
                                    scale={view.width}
                                />
                            ) : (
                                <text
                                    x={rect.x + rect.width / 2}
                                    y={(rect.y + rect.height / 2) * ratio}
                                    fontSize={labelSize(
                                        nameOf(zoneId),
                                        rect.width,
                                        rect.height * ratio,
                                    )}
                                    textAnchor="middle"
                                    dominantBaseline="middle"
                                    pointerEvents="none"
                                >
                                    {nameOf(zoneId)}
                                </text>
                            )}
                            {on && !disabled && (
                                <rect
                                    aria-hidden
                                    x={rect.x + rect.width - 0.02}
                                    y={(rect.y + rect.height) * ratio - 0.02}
                                    width={0.02}
                                    height={0.02}
                                    fill="#1d4ed8"
                                    style={{ cursor: 'nwse-resize' }}
                                    onPointerDown={(e) => down(e, zoneId, rect, 'size')}
                                />
                            )}
                        </g>
                    );
                })}
            </svg>
            <label className="field">
                <span>고칠 구역</span>
                <select
                    aria-label="고칠 구역"
                    value={selected ?? ''}
                    disabled={disabled}
                    onChange={(e) => setSelected(e.target.value || null)}
                >
                    <option value="">선택해주세요</option>
                    {shapes.map((s) => (
                        <option key={s.zoneId} value={s.zoneId}>
                            {nameOf(s.zoneId)}
                        </option>
                    ))}
                </select>
            </label>
            {current && (
                <div className="row" aria-label={`${nameOf(current.zoneId)} 위치와 크기`}>
                    {(
                        [
                            ['x', '왼쪽(%)'],
                            ['y', '위쪽(%)'],
                            ['width', '너비(%)'],
                            ['height', '높이(%)'],
                        ] as const
                    ).map(([key, label]) => (
                        <label key={key} className="field">
                            <span>{label}</span>
                            <input
                                type="number"
                                aria-label={label}
                                min={0}
                                max={100}
                                step={0.1}
                                disabled={disabled}
                                value={pct(current.rect[key])}
                                onChange={(e) => {
                                    const v = Number(e.target.value);
                                    if (Number.isNaN(v)) return;
                                    change(
                                        current.zoneId,
                                        clampRect({ ...current.rect, [key]: v / 100 }),
                                    );
                                }}
                            />
                        </label>
                    ))}
                </div>
            )}
        </div>
    );
}

/** 배경 그림이 있을 때 구역 이름표. PDF에 적힌 이름과 겹치지 않게 도형 위쪽에 흰 이름표로 둔다(V9-D62). */
function ZoneChip({
    name,
    rect,
    ratio,
    on,
    scale,
}: {
    name: string;
    rect: Rect;
    ratio: number;
    on: boolean;
    /** 보이는 영역의 너비(확대해 보일 때 이름표 글자도 같은 비율로 줄여 화면 크기를 유지한다) */
    scale: number;
}) {
    const c = labelChip(name, rect, ratio, 0.018 * scale);
    return (
        <g pointerEvents="none">
            <rect
                x={c.x}
                y={c.y}
                width={c.width}
                height={c.height}
                rx={c.height / 3}
                fill="rgba(255,255,255,0.94)"
                stroke={on ? '#1d4ed8' : '#2563eb'}
                strokeWidth={0.002}
            />
            <text
                x={c.textX}
                y={c.textY}
                fontSize={c.fontSize}
                dominantBaseline="middle"
                fill="#1d4ed8"
                fontWeight={600}
            >
                {name}
            </text>
        </g>
    );
}
