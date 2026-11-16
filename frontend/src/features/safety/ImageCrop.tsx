import { useEffect, useRef, useState, type CSSProperties, type PointerEvent } from 'react';
import { Segmented } from '../../shared/ui/atoms';
import { paddedSize } from './floor-plan-image';
import styles from './floorplan.module.css';

type Rect = [x: number, y: number, w: number, h: number];
type Mode = 'frame' | 'free';
const MIN = 5;
/** 여백 색: 작업 칸 배경(--surface-2)과 같아 미리 보기와 결과가 똑같이 보인다 */
const MARGIN = '#e9ece4';
const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

/** 이미지 % 좌표에서 실제 픽셀 비율(가로/세로)이 ratio이면서 사진 전체를 담는 가장 작은 가운데 상자 */
function fit(imgW: number, imgH: number, ratio: number): Rect {
    const imgRatio = imgW / imgH;
    const [w, h] =
        imgRatio > ratio ? [100, (imgRatio / ratio) * 100] : [(ratio / imgRatio) * 100, 100];
    return [(100 - w) / 2, (100 - h) / 2, w, h];
}

/** 사진 둘레 작업 공간(사진 % 단위): 평면도 비율 칸에 사진을 꽉 맞추고 남는 여백 */
function roomOf(imgW: number, imgH: number, ratio: number) {
    const [, , w, h] = fit(imgW, imgH, ratio);
    return { mx: (w - 100) / 2, my: (h - 100) / 2 };
}

/**
 * 평면도 이미지 자르기: "평면도 비율"은 안전 화면 평면도 틀과 같은 가로세로 비율로만,
 * "자유"는 아무 사각형으로 자른다. 상자 가운데를 끌어 옮기고 오른쪽 아래 모서리로 크기를 바꾼다.
 * 작업 칸은 평면도 틀 비율이고 사진은 칸의 가로나 세로에 꽉 맞춘다. 남는 쪽은 여백이며 상자는 여백까지 잡을 수 있다.
 * 상자는 사진과 조금이라도 겹쳐야 한다. 결과는 어느 방식이든 평면도 틀 비율이고, 모자란 쪽은 여백 색으로 채운다.
 * 잘라도 픽셀은 늘리거나 찌그러뜨리지 않는다(원본 비율 그대로 잘라낸다).
 */
export function ImageCrop({
    file,
    frameRatio,
    onDone,
    onCancel,
}: {
    file: File;
    /** 평면도 틀의 가로/세로 */
    frameRatio: number;
    /** content: 결과 이미지에서 사진이 차지하는 영역(% 좌표). 나머지는 여백이다 */
    onDone: (cropped: File, content: Rect) => void;
    onCancel: () => void;
}) {
    const [src, setSrc] = useState<string>('');
    const [size, setSize] = useState<{ w: number; h: number } | null>(null);
    const [mode, setMode] = useState<Mode>('frame');
    const [rect, setRect] = useState<Rect>([0, 0, 100, 100]);
    const [error, setError] = useState('');
    const box = useRef<HTMLDivElement>(null);
    const cropBox = useRef<HTMLDivElement>(null);
    const stageRef = useRef<HTMLDivElement>(null);
    const drag = useRef<{
        mode: 'move' | 'size' | 'pan';
        px: number;
        py: number;
        start: Rect;
        startView: { z: number; tx: number; ty: number };
    } | null>(null);
    // 보기 확대: 화면 위치 = 작업 칸 위치 × z + t(작업 칸 %). 자를 상자는 화면에서 같은 크기로 두고
    // 사진만 확대/축소한다(확대하면 자를 영역이 그만큼 좁아진다)
    const [view, setView] = useState({ z: 1, tx: 0, ty: 0 });
    // 두 손가락 확대/축소: 손가락 위치와 지난 간격
    const touches = useRef(new Map<number, { x: number; y: number }>());
    const pinch = useRef<number | null>(null);

    useEffect(() => {
        // 정리된(버려진) 실행의 늦은 결과는 무시한다. 주소를 먼저 해제하면 읽기 실패가 나기 때문이다
        let alive = true;
        const url = URL.createObjectURL(file);
        const img = new Image();
        img.onload = () => {
            if (!alive) return;
            setSrc(url);
            setSize({ w: img.naturalWidth, h: img.naturalHeight });
            setRect(fit(img.naturalWidth, img.naturalHeight, frameRatio));
            setView({ z: 1, tx: 0, ty: 0 });
        };
        img.onerror = () => {
            if (alive) setError('이미지를 읽지 못했습니다. 다른 파일을 올려주세요.');
        };
        img.src = url;
        return () => {
            alive = false;
            URL.revokeObjectURL(url);
        };
    }, [file, frameRatio]);

    const changeMode = (m: Mode) => {
        setMode(m);
        if (m === 'frame' && size) {
            setRect(fit(size.w, size.h, frameRatio));
            setView({ z: 1, tx: 0, ty: 0 });
        }
    };

    /**
     * 사진을 f배 확대/축소한다. 자를 상자는 가운데를 그대로 두고 사진 기준 크기만 1/f로 바꿔, 화면에서는
     * 상자 크기·자리가 그대로이고 사진만 커지거나 작아진다. 상자가 상한·하한에 닿으면 더 바꾸지 않는다.
     */
    const zoomBy = (f: number) => {
        if (!size || !Number.isFinite(f) || f <= 0) return;
        const { mx, my } = roomOf(size.w, size.h, frameRatio);
        const spanX = 100 + 2 * mx;
        const spanY = 100 + 2 * my;
        const [x, y, w, h] = rect;
        const lo = Math.max(MIN / w, MIN / h);
        const hi = Math.min(spanX / w, spanY / h);
        const k = clamp(1 / f, lo, hi);
        if (Math.abs(k - 1) < 1e-4) return;
        const nw = w * k;
        const nh = h * k;
        const cx = x + w / 2;
        const cy = y + h / 2;
        const nx = clamp(cx - nw / 2, -mx, 100 + mx - nw);
        const ny = clamp(cy - nh / 2, -my, 100 + my - nh);
        const z = view.z / k;
        // 상자는 화면에서 같은 자리·같은 크기로 둔다(사진만 커지거나 작아진다)
        const left = ((mx + x) / spanX) * 100 * view.z + view.tx;
        const top = ((my + y) / spanY) * 100 * view.z + view.ty;
        setRect([nx, ny, nw, nh]);
        setView({
            z,
            tx: left - ((mx + nx) / spanX) * 100 * z,
            ty: top - ((my + ny) / spanY) * 100 * z,
        });
    };
    const zoomRef = useRef(zoomBy);
    useEffect(() => {
        zoomRef.current = zoomBy;
    });

    // Ctrl + 마우스 휠(노트북 터치패드 두 손가락 오므리기 포함): 브라우저 화면 확대 대신 사진을 확대/축소한다.
    // React의 휠 처리는 기본 동작을 막을 수 없어 직접 단다.
    const hasSize = !!size;
    useEffect(() => {
        const el = cropBox.current;
        if (!el) return;
        const onWheel = (e: WheelEvent) => {
            if (!e.ctrlKey) return;
            e.preventDefault();
            zoomRef.current(Math.exp(-e.deltaY * 0.0025));
        };
        el.addEventListener('wheel', onWheel, { passive: false });
        return () => el.removeEventListener('wheel', onWheel);
    }, [hasSize]);

    const gap = () => {
        const [a, b] = [...touches.current.values()];
        return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 0;
    };
    const onDown = (e: PointerEvent<HTMLElement>, m: 'move' | 'size') => {
        e.stopPropagation();
        if (e.pointerType === 'touch') {
            touches.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (touches.current.size >= 2) {
                // 두 손가락: 옮기기를 멈추고 확대/축소로 바꾼다
                drag.current = null;
                pinch.current = gap();
                (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
                return;
            }
        }
        drag.current = { mode: m, px: e.clientX, py: e.clientY, start: rect, startView: view };
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    };
    /** 자를 상자 바깥을 누른 채 끌면 사진을 옮긴다(두 손가락이면 확대/축소) */
    const onStageDown = (e: PointerEvent<HTMLElement>) => {
        if (e.pointerType === 'touch') {
            touches.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (touches.current.size >= 2) {
                drag.current = null;
                pinch.current = gap();
                (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
                return;
            }
        }
        drag.current = {
            mode: 'pan',
            px: e.clientX,
            py: e.clientY,
            start: rect,
            startView: view,
        };
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    };
    const onMove = (e: PointerEvent<HTMLElement>) => {
        if (touches.current.has(e.pointerId)) {
            touches.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (pinch.current !== null && touches.current.size >= 2) {
                const now = gap();
                if (pinch.current > 0 && now > 0) zoomBy(now / pinch.current);
                pinch.current = now;
                return;
            }
        }
        const g = drag.current;
        const b = box.current?.getBoundingClientRect();
        const st = stageRef.current?.getBoundingClientRect();
        if (!g || !b || !st || !size) return;
        // 사진 % 단위 이동량(확대한 사진 크기 기준)
        const dx = ((e.clientX - g.px) / b.width) * 100;
        const dy = ((e.clientY - g.py) / b.height) * 100;
        const [x, y, w, h] = g.start;
        const { mx, my } = roomOf(size.w, size.h, frameRatio);
        const spanX = 100 + 2 * mx;
        const spanY = 100 + 2 * my;
        const v = g.startView;
        // 화면(작업 칸) 안쪽 경계를 사진 % 좌표로: 자를 상자는 화면 밖으로 나가지 않는다
        const sx0 = (-v.tx / v.z / 100) * spanX - mx;
        const sx1 = ((100 - v.tx) / v.z / 100) * spanX - mx;
        const sy0 = (-v.ty / v.z / 100) * spanY - my;
        const sy1 = ((100 - v.ty) / v.z / 100) * spanY - my;
        const within = (val: number, lo: number, hi: number) =>
            lo <= hi ? clamp(val, lo, hi) : val;
        if (g.mode === 'pan') {
            // 상자는 화면에 그대로 두고 사진을 손가락 방향으로 옮긴다: 사진 기준 상자 자리가 반대로 움직인다
            const nx = clamp(x - dx, Math.max(-mx, MIN - w), Math.min(100 + mx - w, 100 - MIN));
            const ny = clamp(y - dy, Math.max(-my, MIN - h), Math.min(100 + my - h, 100 - MIN));
            setRect([nx, ny, w, h]);
            setView({
                z: v.z,
                tx: v.tx + ((x - nx) / spanX) * 100 * v.z,
                ty: v.ty + ((y - ny) / spanY) * 100 * v.z,
            });
            return;
        }
        if (g.mode === 'move') {
            setRect([
                within(
                    clamp(x + dx, Math.max(-mx, MIN - w), Math.min(100 + mx - w, 100 - MIN)),
                    sx0,
                    sx1 - w,
                ),
                within(
                    clamp(y + dy, Math.max(-my, MIN - h), Math.min(100 + my - h, 100 - MIN)),
                    sy0,
                    sy1 - h,
                ),
                w,
                h,
            ]);
            return;
        }
        if (mode === 'free') {
            setRect([
                x,
                y,
                clamp(w + dx, Math.max(MIN, MIN - x), Math.min(100 + mx, sx1) - x),
                clamp(h + dy, Math.max(MIN, MIN - y), Math.min(100 + my, sy1) - y),
            ]);
            return;
        }
        // 평면도 비율: 가로를 바꾸면 세로가 비율대로 따라온다(작업 공간·화면 밖으로 나가지 않게)
        const k = size.w / (size.h * frameRatio); // h% = w% * k
        const maxW = Math.min(Math.min(100 + mx, sx1) - x, (Math.min(100 + my, sy1) - y) / k);
        const minW = Math.max(MIN, MIN - x, (MIN - y) / k);
        const nw = clamp(w + dx, minW, maxW);
        setRect([x, y, nw, nw * k]);
    };
    const onUp = (e: PointerEvent<HTMLElement>) => {
        touches.current.delete(e.pointerId);
        if (touches.current.size < 2) pinch.current = null;
        drag.current = null;
    };

    /** target(사진 % 좌표)을 잘라 가운데에 두고 평면도 틀 비율까지 여백을 채운다 */
    const apply = (target: Rect) => {
        if (!size || !src) return;
        const img = new Image();
        img.onload = () => {
            const [x, y, w, h] = target;
            const sx = Math.round((x / 100) * size.w);
            const sy = Math.round((y / 100) * size.h);
            const sw = Math.max(1, Math.round((w / 100) * size.w));
            const sh = Math.max(1, Math.round((h / 100) * size.h));
            const out = paddedSize(sw, sh, frameRatio);
            const canvas = document.createElement('canvas');
            canvas.width = Math.round(out.w);
            canvas.height = Math.round(out.h);
            const ox = Math.round((canvas.width - sw) / 2);
            const oy = Math.round((canvas.height - sh) / 2);
            const ctx = canvas.getContext('2d')!;
            ctx.fillStyle = MARGIN;
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            // 상자 안의 사진만 그린다. 나머지는 위의 바탕색이 여백이 된다
            ctx.beginPath();
            ctx.rect(ox, oy, sw, sh);
            ctx.clip();
            ctx.drawImage(img, ox - sx, oy - sy);
            canvas.toBlob((blob) => {
                if (!blob) {
                    setError('이미지를 자르지 못했습니다.');
                    return;
                }
                const name = file.name.replace(/\.(png|jpe?g)$/i, '') + '-crop.png';
                // 결과에서 실제 사진이 남은 영역: 사진 자리와 상자의 겹침
                const left = Math.max(ox, ox - sx);
                const top = Math.max(oy, oy - sy);
                const right = Math.min(ox + sw, ox - sx + size.w);
                const bottom = Math.min(oy + sh, oy - sy + size.h);
                onDone(new File([blob], name, { type: 'image/png' }), [
                    (left / canvas.width) * 100,
                    (top / canvas.height) * 100,
                    ((right - left) / canvas.width) * 100,
                    ((bottom - top) / canvas.height) * 100,
                ]);
            }, 'image/png');
        };
        img.src = src;
    };

    if (error)
        return (
            <div className="stack">
                <p className="small urgent">{error}</p>
                <button type="button" className="btn" onClick={onCancel}>
                    돌아가기
                </button>
            </div>
        );
    if (!size) return <p className="small muted">이미지를 읽는 중…</p>;
    const { mx, my } = roomOf(size.w, size.h, frameRatio);
    const spanX = 100 + 2 * mx;
    const spanY = 100 + 2 * my;
    /** 사진 % 좌표의 상자를 (확대한) 화면 위 작업 공간 % 위치로 */
    const inStage = ([x, y, w, h]: Rect): CSSProperties => ({
        left: `${((mx + x) / spanX) * 100 * view.z + view.tx}%`,
        top: `${((my + y) / spanY) * 100 * view.z + view.ty}%`,
        width: `${(w / spanX) * 100 * view.z}%`,
        height: `${(h / spanY) * 100 * view.z}%`,
    });
    // 결과 이미지: 상자를 평면도 틀 비율까지 넓힌 영역(사진 % 좌표)
    const out = paddedSize((rect[2] / 100) * size.w, (rect[3] / 100) * size.h, frameRatio);
    const outW = (out.w / size.w) * 100;
    const outH = (out.h / size.h) * 100;
    const result: Rect = [
        rect[0] - (outW - rect[2]) / 2,
        rect[1] - (outH - rect[3]) / 2,
        outW,
        outH,
    ];
    const stageRatio = (spanX * size.w) / (spanY * size.h);
    return (
        <div className="stack">
            <div className="row between">
                <Segmented<Mode>
                    label="자르기 방식"
                    value={mode}
                    onChange={changeMode}
                    items={[
                        ['frame', '평면도 비율'],
                        ['free', '자유'],
                    ]}
                />
                <span className="xs muted">
                    결과 이미지 {Math.round(out.w)}×{Math.round(out.h)}px
                </span>
            </div>
            <div className={styles.cropFrame}>
                <div
                    ref={stageRef}
                    className={styles.cropStage}
                    onPointerDown={onStageDown}
                    style={{
                        aspectRatio: `${stageRatio}`,
                        width: `min(100%, calc(56dvh * ${stageRatio}))`,
                    }}
                    onPointerMove={onMove}
                    onPointerUp={onUp}
                    onPointerCancel={onUp}
                >
                    <div
                        ref={box}
                        className={styles.cropImage}
                        style={{ ...inStage([0, 0, 100, 100]), backgroundImage: `url(${src})` }}
                    />
                    {mode === 'free' && (
                        <div className={styles.cropResult} style={inStage(result)} />
                    )}
                    <div
                        ref={cropBox}
                        className={styles.cropBox}
                        title="Ctrl + 마우스 휠 또는 두 손가락으로 사진 확대/축소"
                        style={inStage(rect)}
                        onPointerDown={(e) => onDown(e, 'move')}
                    >
                        <i
                            className={styles.handle}
                            aria-label="자를 영역 크기 바꾸기"
                            onPointerDown={(e) => onDown(e, 'size')}
                        />
                    </div>
                </div>
            </div>
            <div className="row" style={{ justifyContent: 'flex-end' }}>
                <button type="button" className="btn ghost" onClick={onCancel}>
                    취소
                </button>
                <button type="button" className="btn" onClick={() => apply([0, 0, 100, 100])}>
                    자르지 않고 쓰기
                </button>
                <button type="button" className="btn primary" onClick={() => apply(rect)}>
                    잘라서 쓰기
                </button>
            </div>
        </div>
    );
}
