import { useCallback, useRef } from 'react';

/** 화면 아래 여백(본문 아래 패딩 + 여유) */
const BOTTOM_GAP = 48;
/** 이보다 낮게는 줄이지 않는다(작은 창에서는 화면 스크롤로 본다) */
const MIN_HEIGHT = 520;

/**
 * 요소가 화면 위에서 시작하는 자리부터 창 아래까지의 높이를 CSS 변수 --avail(px)로 넣는다.
 * 계획 수정 화면이 탭 막대·상태 줄 아래에서 시작해도 한 화면에 꼭 맞게 칸 높이를 나눈다.
 * 요소가 나중에 그려져도(불러오기 뒤) 붙는 때에 재도록 콜백 ref로 돌려준다.
 * bottomGap은 창 아래에 남길 여백이다(본문 아래 여백을 끌어 쓰는 화면은 0).
 */
export function useAvailableHeight(bottomGap = BOTTOM_GAP) {
    const cleanup = useRef<(() => void) | null>(null);
    return useCallback(
        (el: HTMLElement | null) => {
            cleanup.current?.();
            cleanup.current = null;
            if (!el) return;
            const update = () => {
                const top = el.getBoundingClientRect().top + window.scrollY;
                const avail = Math.max(MIN_HEIGHT, window.innerHeight - top - bottomGap);
                el.style.setProperty('--avail', `${Math.round(avail)}px`);
            };
            update();
            window.addEventListener('resize', update);
            // 숨긴 탭에서 처음 그려졌다가 보이게 되면(크기가 0에서 바뀌면) 그때 다시 잰다.
            // 같은 값이면 높이가 바뀌지 않아 다시 불리지 않는다
            const seen = new ResizeObserver(() => {
                if (el.offsetParent !== null) update();
            });
            seen.observe(el);
            cleanup.current = () => {
                window.removeEventListener('resize', update);
                seen.disconnect();
            };
        },
        [bottomGap],
    );
}
