import type { CSSProperties, Ref } from 'react';
import styles from './landing.module.css';

const WORD = 'SceneCrew'.split('');
/** 글자를 부채꼴로 펼치는 값: 아래로 내리는 양(px), 기울기(deg), 세로 늘림 */
const FAN = [
    [80, -14, 1],
    [45, -10.5, 1.08],
    [20, -7, 1.14],
    [5, -3.5, 1.17],
    [0, 0, 1.19],
    [5, 3.5, 1.17],
    [20, 7, 1.14],
    [45, 10.5, 1.08],
    [80, 14, 1],
];
/** 글자마다 다른 빛 맺힘 위치(%): 가로, 세로 */
const GLINT = [
    [34, 15],
    [40, 22],
    [30, 18],
    [62, 26],
    [36, 20],
    [34, 13],
    [58, 30],
    [64, 22],
    [24, 14],
];
const SPARKS = [
    [9, 34, 0.2],
    [27, 8, 1.1],
    [48, 52, 0.6],
    [66, 14, 1.7],
    [90, 70, 2.2],
];
/**
 * 로그인 화면 배경의 입체 워드마크(WEB·APP 공용). 장식이라 읽기 도구에는 숨긴다.
 * compact는 폭이 좁은 APP용으로 글자 크기와 부채꼴 굽힘을 줄인다. ref는 마우스 기울임을 받는 요소다.
 * nav는 상단 네비 왼쪽 로고 자리용으로, 같은 모양·움직임을 그대로 작게 줄여 제자리에 둔다.
 */
export function LoginWordmark({
    ref,
    compact,
    nav,
}: {
    ref?: Ref<HTMLDivElement>;
    compact?: boolean;
    nav?: boolean;
}) {
    return (
        <div
            aria-hidden="true"
            className={`${styles.wordWrap} ${compact ? styles.compact : ''} ${nav ? styles.navMark : ''}`}
        >
            <div className={styles.wm}>
                <div className={styles.wm3d}>
                    <div className={styles.wmFloat}>
                        <div ref={ref} className={styles.wmTilt}>
                            {WORD.map((ch, k) => (
                                <span
                                    key={k}
                                    className={styles.ch}
                                    style={
                                        {
                                            transform: `translateY(calc(${FAN[k][0]}px * var(--wm-arc, 1))) rotate(${FAN[k][1]}deg) scaleY(${FAN[k][2]})`,
                                            animationDelay: `${k * 0.05}s`,
                                            '--hx': `${GLINT[k][0]}%`,
                                            '--hy': `${GLINT[k][1]}%`,
                                        } as CSSProperties
                                    }
                                >
                                    {ch}
                                </span>
                            ))}
                            <svg className={styles.holoDefs} aria-hidden="true">
                                <defs>
                                    <linearGradient id="landing-holo" x1="0" y1="0" x2="1" y2="1">
                                        <stop offset="0" stopColor="#ffffff" />
                                        <stop offset="0.3" stopColor="#ffd9f6" />
                                        <stop offset="0.5" stopColor="#ffffff" />
                                        <stop offset="0.7" stopColor="#c9f4ff" />
                                        <stop offset="0.88" stopColor="#e4d8ff" />
                                        <stop offset="1" stopColor="#ffffff" />
                                    </linearGradient>
                                </defs>
                            </svg>
                            {SPARKS.map(([left, top, delay]) => (
                                <svg
                                    key={left}
                                    className={styles.spark}
                                    style={{
                                        left: `${left}%`,
                                        top: `${top}%`,
                                        animationDelay: `${delay}s`,
                                    }}
                                    viewBox="0 0 24 24"
                                >
                                    <path
                                        d="M12 0L13.6 10.4 24 12 13.6 13.6 12 24 10.4 13.6 0 12 10.4 10.4Z"
                                        fill="url(#landing-holo)"
                                    />
                                </svg>
                            ))}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
