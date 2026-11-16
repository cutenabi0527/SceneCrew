import { useEffect, useRef, useState } from 'react';
import styles from './task.module.css';

/** 날짜 칸 한 개 높이(px). 끌기·휠 한 칸 이동 거리이기도 하다. */
const CELL = 64;
/** 휠 누적량이 이만큼 쌓이면 하루 이동한다(트랙패드의 작은 값도 모아서 처리) */
const WHEEL_STEP = 60;
const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

const parse = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d));
};
const toIso = (t: Date) => t.toISOString().slice(0, 10);
const ymd = (y: number, m: number, d: number) => toIso(new Date(Date.UTC(y, m - 1, d)));
const daysIn = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const dateLabel = (iso: string) => {
    const t = parse(iso);
    return `${t.getUTCFullYear()}년 ${t.getUTCMonth() + 1}월 ${t.getUTCDate()}일 ${WEEKDAY[t.getUTCDay()]}요일`;
};

type Props = {
    value: string;
    onChange: (iso: string) => void;
    marked: Set<string>;
    today: string;
};

/**
 * 날짜 고르기: 업무가 있는 날(marked)만 고를 수 있다.
 * 위 제목 "YYYY년 / M월 D일"의 월 숫자를 누르면 1~12월(업무 있는 달만), 일 숫자를 누르면 그달 달력(업무 있는 날만)에서 고른다.
 * 아래 세로 띠에는 업무 있는 날만 늘어놓고, 위아래로 끌거나 마우스 휠·↑/↓(←/→) 키로 한 칸씩 넘기거나 칸을 눌러 고른다.
 * 고른 날짜가 늘 가운데(검은 칸)에 온다. 오늘은 날짜 밑 점으로 표시한다.
 */
export function DateStrip({ value, onChange, marked, today }: Props) {
    const dates = [...marked].sort();
    const idx = Math.max(dates.indexOf(value), 0);
    const at = (i: number) => dates[Math.min(Math.max(i, 0), dates.length - 1)];

    const ref = useRef<HTMLDivElement>(null);
    const [drag, setDrag] = useState<{ x: number; from: number; dx: number } | null>(null);
    const step = (n: number) => {
        const next = at(idx + n);
        if (next && next !== value) onChange(next);
    };

    // React의 onWheel은 passive라 페이지 스크롤을 막지 못해 직접 등록한다
    const latest = useRef(step);
    useEffect(() => {
        latest.current = step;
    });
    useEffect(() => {
        const el = ref.current;
        if (!el) return;
        let acc = 0;
        const onWheel = (e: WheelEvent) => {
            e.preventDefault();
            acc += Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
            if (Math.abs(acc) < WHEEL_STEP) return;
            latest.current(Math.sign(acc));
            acc = 0;
        };
        el.addEventListener('wheel', onWheel, { passive: false });
        return () => el.removeEventListener('wheel', onWheel);
    }, []);

    const offset = drag ? drag.dx : 0;

    return (
        <div className={styles.stripWrap}>
            <DateTitle value={value} onChange={onChange} marked={marked} today={today} />
            <div
                ref={ref}
                className={styles.strip}
                role="slider"
                tabIndex={0}
                aria-label="날짜 선택"
                aria-orientation="vertical"
                aria-valuemin={0}
                aria-valuemax={dates.length - 1}
                aria-valuenow={idx}
                aria-valuetext={dateLabel(value)}
                data-dragging={drag ? 'true' : undefined}
                onKeyDown={(e) => {
                    if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') step(-1);
                    else if (e.key === 'ArrowDown' || e.key === 'ArrowRight') step(1);
                    else return;
                    e.preventDefault();
                }}
                onPointerDown={(e) => setDrag({ x: e.clientY, from: idx, dx: 0 })}
                onPointerMove={(e) => {
                    if (!drag) return;
                    const dx = e.clientY - drag.x;
                    // 조금 움직이면 클릭으로 두고, 넘어서면 끌기로 보고 포인터를 붙잡는다
                    if (Math.abs(dx) > 4 && !e.currentTarget.hasPointerCapture(e.pointerId)) {
                        e.currentTarget.setPointerCapture(e.pointerId);
                    }
                    const next = Math.min(
                        Math.max(drag.from - Math.round(dx / CELL), 0),
                        dates.length - 1,
                    );
                    if (dates[next] !== value) onChange(dates[next]);
                    setDrag({ ...drag, dx: dx + (next - drag.from) * CELL });
                }}
                onPointerUp={() => setDrag(null)}
                onPointerCancel={() => setDrag(null)}
            >
                <div
                    className={styles.stripTrack}
                    style={{
                        transform: `translateY(calc(50% - ${(idx + 0.5) * CELL - offset}px))`,
                    }}
                >
                    {dates.map((iso) => {
                        const t = parse(iso);
                        return (
                            <button
                                key={iso}
                                type="button"
                                tabIndex={-1}
                                className={styles.stripCell}
                                aria-pressed={iso === value}
                                aria-label={`${dateLabel(iso)}${iso === today ? ', 오늘' : ''}`}
                                onClick={() => onChange(iso)}
                            >
                                <span className={styles.stripTile}>
                                    <span className={styles.stripWeek}>
                                        {WEEKDAY[t.getUTCDay()]}
                                    </span>
                                    <b className={styles.stripDay}>{t.getUTCDate()}</b>
                                    <span
                                        className={styles.stripDot}
                                        data-on={iso === today ? 'true' : undefined}
                                    />
                                </span>
                            </button>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}

/** 제목 "YYYY년 M월 D일". 월·일 숫자가 각각 월 고르기·달력 팝업을 연다. 바깥 클릭이나 Esc로 닫는다. */
function DateTitle({ value, onChange, marked, today }: Props) {
    const t = parse(value);
    const y = t.getUTCFullYear();
    const m = t.getUTCMonth() + 1;
    const d = t.getUTCDate();
    const [open, setOpen] = useState<'month' | 'day' | null>(null);
    const [pickYear, setPickYear] = useState(y);
    const box = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        const onDown = (e: PointerEvent) => {
            if (!box.current?.contains(e.target as Node)) setOpen(null);
        };
        const onKey = (e: KeyboardEvent) => {
            if (e.key === 'Escape') setOpen(null);
        };
        document.addEventListener('pointerdown', onDown);
        document.addEventListener('keydown', onKey);
        return () => {
            document.removeEventListener('pointerdown', onDown);
            document.removeEventListener('keydown', onKey);
        };
    }, [open]);

    const firstIn = (yy: number, mm: number) => {
        const prefix = `${yy}-${String(mm).padStart(2, '0')}-`;
        return [...marked].sort().find((iso) => iso.startsWith(prefix));
    };
    const pick = (iso: string) => {
        onChange(iso);
        setOpen(null);
    };
    // 달력: 1일 앞은 빈칸으로 요일을 맞춘다
    const lead = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
    const cells = [
        ...Array.from({ length: lead }, () => null),
        ...Array.from({ length: daysIn(y, m) }, (_, k) => ymd(y, m, k + 1)),
    ];

    return (
        <div ref={box} className={styles.stripHead}>
            <p className={styles.stripTitle} aria-live="polite">
                <span className={styles.stripYear}>{y}년</span>
                <button
                    type="button"
                    className={styles.titlePick}
                    aria-haspopup="dialog"
                    aria-expanded={open === 'month'}
                    aria-label={`${m}월, 월 고르기`}
                    onClick={() => {
                        setPickYear(y);
                        setOpen(open === 'month' ? null : 'month');
                    }}
                >
                    {m}
                </button>
                월{' '}
                <button
                    type="button"
                    className={styles.titlePick}
                    aria-haspopup="dialog"
                    aria-expanded={open === 'day'}
                    aria-label={`${d}일, 달력에서 고르기`}
                    onClick={() => setOpen(open === 'day' ? null : 'day')}
                >
                    {d}
                </button>
                일
            </p>
            {open === 'month' && (
                <div className={styles.picker} role="dialog" aria-label="월 고르기">
                    <div className={styles.pickerHead}>
                        <button
                            type="button"
                            className={styles.pickNav}
                            aria-label="이전 해"
                            onClick={() => setPickYear(pickYear - 1)}
                        >
                            ‹
                        </button>
                        <b>{pickYear}년</b>
                        <button
                            type="button"
                            className={styles.pickNav}
                            aria-label="다음 해"
                            onClick={() => setPickYear(pickYear + 1)}
                        >
                            ›
                        </button>
                    </div>
                    <div className={styles.monthGrid}>
                        {Array.from({ length: 12 }, (_, k) => k + 1).map((mm) => (
                            <button
                                key={mm}
                                type="button"
                                className={styles.pickCell}
                                aria-pressed={pickYear === y && mm === m}
                                // 업무 있는 달만 고를 수 있고, 고르면 그달 첫 업무일로 간다
                                disabled={!firstIn(pickYear, mm)}
                                onClick={() => pick(firstIn(pickYear, mm) as string)}
                            >
                                {mm}월
                            </button>
                        ))}
                    </div>
                </div>
            )}
            {open === 'day' && (
                <div className={styles.picker} role="dialog" aria-label={`${y}년 ${m}월 달력`}>
                    <div className={styles.pickerHead}>
                        <b>
                            {y}년 {m}월
                        </b>
                    </div>
                    <div className={styles.calGrid}>
                        {WEEKDAY.map((w) => (
                            <span key={w} className={styles.calWeek}>
                                {w}
                            </span>
                        ))}
                        {cells.map((iso, k) =>
                            iso ? (
                                <button
                                    key={iso}
                                    type="button"
                                    className={styles.pickCell}
                                    aria-pressed={iso === value}
                                    aria-label={`${dateLabel(iso)}${iso === today ? ', 오늘' : ''}${marked.has(iso) ? ', 업무 있음' : ''}`}
                                    data-task={marked.has(iso) ? 'true' : undefined}
                                    disabled={!marked.has(iso)}
                                    onClick={() => pick(iso)}
                                >
                                    {parse(iso).getUTCDate()}
                                    <span
                                        className={styles.stripDot}
                                        data-on={iso === today ? 'true' : undefined}
                                    />
                                </button>
                            ) : (
                                <span key={`b${k}`} />
                            ),
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
