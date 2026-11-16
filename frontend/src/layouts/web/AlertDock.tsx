import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { useData, usePhase, useViewer } from '../../domain/hooks';
import { NOW_MIN, ago, toMin } from '../../domain/selectors';
import { Icon } from '../../shared/ui/Icon';
import styles from './AlertDock.module.css';

/** 알림이 펼쳐져 있는 시간. 지나면 오른쪽 가장자리의 작은 아이콘 상자로 줄어든다 */
const OPEN_MS = 10_000;
/** 다음 공연 알림을 띄우는 시점(시작 n분 전부터) */
const NEXT_WARN_MIN = 10;

/**
 * 화면 오른쪽 가장자리에 붙는 공통 알림(WEB 전 화면 같은 자리).
 * - 문제 상황(장비 이상 보고): 총괄만. 펼친 알림의 메시지를 누르면 장비 운영의 알림 목록 팝업 → 복구 대안으로 간다.
 * - 다음 공연 10분 이내.
 * 새 알림은 오른쪽에서 밀려 나와 10초 뒤 아이콘 상자로 줄어든다. 눌러서 펼쳐도 10초 뒤 저절로 접힌다.
 */
export function AlertDock() {
    const d = useData();
    const v = useViewer();
    const { next } = usePhase();
    const navigate = useNavigate();

    const blocking = v.canManageEquipment
        ? d.issues.filter(
              (i) =>
                  i.status !== 'CLOSED' &&
                  (i.situation?.primary === 'EQUIPMENT_ANOMALY' ||
                      i.situation?.related.includes('EQUIPMENT_ANOMALY')),
          )
        : [];
    const soon = next ? toMin(next.start) - NOW_MIN : -1;
    const showNext = !!next && soon >= 0 && soon <= NEXT_WARN_MIN;
    if (!blocking.length && !showNext) return null;
    const latest = blocking[0];

    return (
        <div className={styles.dock} aria-label="알림">
            {latest && (
                // 문제 상황이 새로 생기면(목록이 바뀌면) 다시 펼친다
                <SlideAlert
                    key={blocking.map((i) => i.id).join(',')}
                    tone="bad"
                    icon={<b className={styles.bang}>!</b>}
                    count={blocking.length}
                    label={`문제 상황 알림 ${blocking.length}건 보기`}
                    onOpen={() => navigate('/e/equipment?alerts=1')}
                    title={
                        <b className={styles.title}>
                            문제 상황 {blocking.length}건{' '}
                            <span className={styles.titleSub}>{latest.title}</span>
                        </b>
                    }
                    sub={
                        <span className="xs">
                            {latest.zone} / {ago(latest.at)} 전 / 메시지를 눌러 복구 대안 확인
                        </span>
                    }
                />
            )}
            {showNext && next && (
                <SlideAlert
                    key={next.id}
                    tone="warn"
                    icon={<Icon name="calendar" />}
                    label={`다음 공연 ${next.name} ${soon}분 후`}
                    title={
                        <b className={styles.title}>
                            다음 공연 {soon}분 후{' '}
                            <span className={styles.titleSub}>{next.name}</span>
                        </b>
                    }
                    sub={
                        <span className="xs mono">
                            {next.start}–{next.end}
                        </span>
                    }
                />
            )}
        </div>
    );
}

/**
 * 접힘(아이콘 상자) ↔ 펼침(아이콘 + 내용). 상자를 누르면 펼치고, 펼친 상태에서 아이콘·배경을 누르거나
 * 알림 밖 아무 곳을 누르면 다시 접는다. onOpen이 있으면 알림 메시지(마우스를 올리면 밑줄)를 눌러 연다.
 */
function SlideAlert({
    tone,
    icon,
    count,
    label,
    title,
    sub,
    onOpen,
}: {
    tone: 'bad' | 'warn';
    icon: ReactNode;
    count?: number;
    label: string;
    title: ReactNode;
    sub: ReactNode;
    onOpen?: () => void;
}) {
    // 처음 나타날 때 펼쳐 두고, 눌러서 펼치거나 접을 수 있다
    const [open, setOpen] = useState(true);
    const ref = useRef<HTMLDivElement>(null);
    // 펼칠 때마다(처음 나타날 때·눌러서 펼칠 때) 10초 뒤 저절로 접는다
    useEffect(() => {
        if (!open) return;
        const t = window.setTimeout(() => setOpen(false), OPEN_MS);
        return () => window.clearTimeout(t);
    }, [open]);
    // 펼친 동안 알림 밖을 누르면 접는다
    useEffect(() => {
        if (!open) return;
        const onDown = (e: PointerEvent) => {
            if (!ref.current?.contains(e.target as Node)) {
                setOpen(false);
            }
        };
        document.addEventListener('pointerdown', onDown);
        return () => document.removeEventListener('pointerdown', onDown);
    }, [open]);
    const toggle = () => {
        setOpen((x) => !x);
    };

    return (
        <div
            ref={ref}
            className={styles.alert}
            data-tone={tone}
            data-open={open || undefined}
            onClick={toggle}
        >
            <button
                type="button"
                className={styles.icon}
                aria-label={open ? `${label} 접기` : label}
                aria-expanded={open}
                title={label}
                onClick={(e) => {
                    e.stopPropagation();
                    toggle();
                }}
            >
                {icon}
                {!open && count !== undefined && count > 1 && (
                    <em className={styles.count}>{count}</em>
                )}
            </button>
            <span className={styles.body} aria-hidden={!open}>
                {onOpen ? (
                    <button
                        type="button"
                        className={styles.msg}
                        tabIndex={open ? 0 : -1}
                        onClick={(e) => {
                            e.stopPropagation();
                            setOpen(false);
                            onOpen();
                        }}
                    >
                        {title}
                    </button>
                ) : (
                    title
                )}
                {sub}
            </span>
        </div>
    );
}
