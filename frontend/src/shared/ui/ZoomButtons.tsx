import type { TimeZoom } from './useTimeZoom';
import styles from './ZoomButtons.module.css';

/** 시간표 위 오른쪽의 시간 간격 −/+ 버튼 */
export function ZoomButtons({ zoom }: { zoom: TimeZoom }) {
    return (
        <div className={styles.zoom} role="group" aria-label="시간 간격">
            <button
                type="button"
                className="btn sm"
                aria-label="시간 간격 줄이기"
                disabled={!zoom.canZoomOut}
                onClick={zoom.zoomOut}
            >
                −
            </button>
            <button
                type="button"
                className="btn sm"
                aria-label="시간 간격 늘리기"
                disabled={!zoom.canZoomIn}
                onClick={zoom.zoomIn}
            >
                +
            </button>
        </div>
    );
}
