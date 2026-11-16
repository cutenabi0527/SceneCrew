import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

/** 가로 시간표의 간격 단계(분당 px). 간격은 항상 일정하고, 시간이 늘면 그만큼 가로로 스크롤한다 */
export const TIME_ZOOM_PX = [2, 3, 4, 6, 8, 10, 14, 18, 24, 32];
/** Ctrl+휠로 간격을 한 단계 바꾸는 휠 이동량(마우스 휠 한 칸은 약 100) */
const WHEEL_STEP = 50;

type Axis = 'x' | 'y';
type Anchor = {
    /** 시간축 시작에서 기준 시각까지의 분 */ minutes: number;
    /** 기준 시각을 둘 화면 위치 */ at: number;
};

/** 시각 눈금 목록: 간격이 좁으면 글자가 겹치지 않게 30분마다, 넓으면 10분마다 적는다 */
export function axisSlots(t0: number, t1: number, px: number) {
    const step = px >= 6 ? 10 : 30;
    return Array.from({ length: Math.floor((t1 - t0) / step) + 1 }, (_, k) => t0 + k * step);
}

function scrollOwner(el: HTMLElement, axis: Axis) {
    for (let p = el.parentElement; p; p = p.parentElement) {
        const style = getComputedStyle(p);
        const overflow = axis === 'x' ? style.overflowX : style.overflowY;
        if (overflow === 'auto' || overflow === 'scroll') return p;
    }
    return (document.scrollingElement ?? document.documentElement) as HTMLElement;
}

const originAt = (origin: HTMLElement, axis: Axis) => {
    const r = origin.getBoundingClientRect();
    return axis === 'x' ? r.left : r.top;
};

/** 시간 칸 중 지금 화면에 보이는 구간. 가로는 왼쪽 고정 열 뒤를 뺀다 */
function visibleRange(origin: HTMLElement, owner: HTMLElement, axis: Axis) {
    const o = origin.getBoundingClientRect();
    if (axis === 'x') {
        return { start: o.left + owner.scrollLeft, end: owner.getBoundingClientRect().right };
    }
    const page = owner === document.scrollingElement;
    const r = page ? { top: 0, bottom: window.innerHeight } : owner.getBoundingClientRect();
    return { start: Math.max(r.top, o.top), end: Math.min(r.bottom, o.bottom) };
}

/**
 * 웹 시간표 공통 확대/축소: −/+ 버튼과 Ctrl+마우스 휠로 분당 px을 바꾼다.
 * 휠은 커서가 가리키는 시각을, 버튼은 보이는 구간의 가운데 시각을 화면의 같은 자리에 둔다.
 * bindRoot는 휠을 받는 시간표 전체, bindOrigin는 시간축 시작 지점에 놓인 요소(눈금 칸)에 단다.
 */
export function useTimeZoom({
    levels = TIME_ZOOM_PX,
    initial,
    axis = 'x',
    t0,
    centerMin,
}: {
    levels?: number[];
    /** 처음 분당 px(levels 중 하나) */
    initial: number;
    axis?: Axis;
    t0: number;
    /** 처음과 시간축이 바뀔 때 가운데에 둘 시각 */
    centerMin?: number;
}) {
    const [index, setIndex] = useState(() => Math.max(levels.indexOf(initial), 0));
    const [root, bindRoot] = useState<HTMLElement | null>(null);
    const [origin, bindOrigin] = useState<HTMLElement | null>(null);
    const px = levels[index];
    const anchor = useRef<Anchor | null>(null);
    const wheelSum = useRef(0);

    useLayoutEffect(() => {
        if (!origin) return;
        const owner = scrollOwner(origin, axis);
        let target = anchor.current;
        anchor.current = null;
        if (!target && centerMin !== undefined) {
            const { start, end } = visibleRange(origin, owner, axis);
            target = { minutes: centerMin - t0, at: (start + end) / 2 };
        }
        if (!target) return;
        const move = originAt(origin, axis) + target.minutes * px - target.at;
        if (axis === 'x') owner.scrollLeft += move;
        else owner.scrollTop += move;
    }, [px, t0, origin, axis, centerMin]);

    const change = useCallback(
        (next: number, pos?: number) => {
            const clamped = Math.min(Math.max(next, 0), levels.length - 1);
            if (clamped === index) return;
            if (origin) {
                const { start, end } = visibleRange(origin, scrollOwner(origin, axis), axis);
                const at = Math.min(Math.max(pos ?? (start + end) / 2, start), end);
                anchor.current = { minutes: (at - originAt(origin, axis)) / px, at };
            }
            setIndex(clamped);
        },
        [index, levels, origin, axis, px],
    );

    useEffect(() => {
        if (!root) return;
        const onWheel = (e: WheelEvent) => {
            if (!e.ctrlKey && !e.metaKey) return;
            // 브라우저 화면 확대를 막는다
            e.preventDefault();
            // 터치패드는 조금씩 들어오므로 모아서 한 단계로 친다
            const same = Math.sign(e.deltaY) === Math.sign(wheelSum.current);
            wheelSum.current = same ? wheelSum.current + e.deltaY : e.deltaY;
            if (Math.abs(wheelSum.current) < WHEEL_STEP) return;
            wheelSum.current = 0;
            change(index + (e.deltaY < 0 ? 1 : -1), axis === 'x' ? e.clientX : e.clientY);
        };
        root.addEventListener('wheel', onWheel, { passive: false });
        return () => root.removeEventListener('wheel', onWheel);
    }, [root, change, index, axis]);

    return {
        px,
        bindRoot,
        bindOrigin,
        canZoomOut: index > 0,
        canZoomIn: index < levels.length - 1,
        zoomOut: () => change(index - 1),
        zoomIn: () => change(index + 1),
    };
}

export type TimeZoom = ReturnType<typeof useTimeZoom>;
