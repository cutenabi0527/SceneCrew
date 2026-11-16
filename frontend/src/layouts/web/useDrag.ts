import {
    useEffect,
    useRef,
    useState,
    type PointerEvent as ReactPointerEvent,
    type RefObject,
} from 'react';

/** 왼쪽 위 좌표. w·h는 옮길 때의 상자 크기(창이 작아져도 화면 안에 다시 가두는 데 쓴다) */
export type Pos = { x: number; y: number; w?: number; h?: number };

/** 크기를 모르는 예전 기억 값에 쓰는 상자 크기(소통 버튼 크기) */
const FALLBACK_SIZE = 56;

/** 이만큼(px) 넘게 움직여야 끌기로 본다. 그보다 짧으면 평소 클릭으로 둔다 */
const THRESHOLD = 4;

function readPos(key: string): Pos | null {
    try {
        const raw = localStorage.getItem(key);
        if (!raw) return null;
        const p = JSON.parse(raw) as Pos;
        return Number.isFinite(p?.x) && Number.isFinite(p?.y) ? p : null;
    } catch {
        return null;
    }
}

/** 화면 밖으로 나가지 않게 왼쪽 위 좌표를 창(vw×vh) 안으로 가둔다 */
export function clampPos(
    p: Pos,
    w: number,
    h: number,
    vw = window.innerWidth,
    vh = window.innerHeight,
): Pos {
    return {
        x: Math.min(Math.max(0, p.x), Math.max(0, vw - w)),
        y: Math.min(Math.max(0, p.y), Math.max(0, vh - h)),
        w,
        h,
    };
}

/**
 * 고정 위치 요소를 손잡이로 끌어 옮긴다. 위치(왼쪽 위 좌표)는 이 브라우저에 기억한다(storageKey).
 * 아직 옮긴 적이 없으면 pos는 null이며, 그때는 CSS 기본 위치를 쓴다.
 * box를 주면 손잡이(머리줄 등)를 끌 때 그 상자 전체를 옮긴다. 손잡이 안의 다른 버튼에서 누르면 끌지 않는다.
 * moved()는 마지막 누름이 끌기였는지 알려 준다(끌고 놓을 때 클릭으로 처리하지 않게).
 */
export function useDrag(
    storageKey: string,
    box?: RefObject<HTMLElement | null>,
    /** 'y'면 위아래로만 옮긴다(가로 위치는 CSS 그대로) */
    axis: 'both' | 'y' = 'both',
) {
    const [pos, setPos] = useState<Pos | null>(() => readPos(storageKey));
    const last = useRef<Pos | null>(pos);
    const start = useRef<{
        px: number;
        py: number;
        x: number;
        y: number;
        w: number;
        h: number;
    } | null>(null);
    const dragged = useRef(false);
    // 창 크기. 창을 줄이면 기억한 위치가 화면 밖일 수 있어, 보일 때마다 창 안으로 다시 가둔다
    const [view, setView] = useState(() => ({ w: window.innerWidth, h: window.innerHeight }));
    useEffect(() => {
        const onResize = () => setView({ w: window.innerWidth, h: window.innerHeight });
        window.addEventListener('resize', onResize);
        return () => window.removeEventListener('resize', onResize);
    }, []);
    const shown = pos
        ? clampPos(pos, pos.w ?? FALLBACK_SIZE, pos.h ?? FALLBACK_SIZE, view.w, view.h)
        : null;

    const onPointerDown = (e: ReactPointerEvent<HTMLElement>) => {
        if (e.button !== 0) return;
        const hit = (e.target as Element).closest('button, a, input, select, textarea');
        if (hit && hit !== e.currentTarget) return;
        const el = box?.current ?? e.currentTarget;
        const r = el.getBoundingClientRect();
        start.current = {
            px: e.clientX,
            py: e.clientY,
            x: r.left,
            y: r.top,
            w: r.width,
            h: r.height,
        };
        dragged.current = false;
        e.currentTarget.setPointerCapture(e.pointerId);
    };

    const onPointerMove = (e: ReactPointerEvent<HTMLElement>) => {
        const s = start.current;
        if (!s) return;
        const dx = e.clientX - s.px;
        const dy = e.clientY - s.py;
        if (!dragged.current && Math.hypot(dx, dy) < THRESHOLD) return;
        dragged.current = true;
        const next = clampPos({ x: axis === 'y' ? s.x : s.x + dx, y: s.y + dy }, s.w, s.h);
        last.current = next;
        setPos(next);
    };

    const onPointerUp = (e: ReactPointerEvent<HTMLElement>) => {
        if (!start.current) return;
        start.current = null;
        if (e.currentTarget.hasPointerCapture(e.pointerId))
            e.currentTarget.releasePointerCapture(e.pointerId);
        if (!dragged.current || !last.current) return;
        try {
            localStorage.setItem(storageKey, JSON.stringify(last.current));
        } catch {
            // 기억하지 못해도 이번 위치는 그대로 쓴다.
        }
    };

    // 한 번 읽으면 지운다. 끈 다음 키보드로 누를 때까지 막지 않게 한다
    const moved = () => {
        const m = dragged.current;
        dragged.current = false;
        return m;
    };

    return { pos: shown, moved, handlers: { onPointerDown, onPointerMove, onPointerUp } };
}
