import { useData, useEvent, useSession } from '../../domain/hooks';
import { EQUIP_TECH_LABEL, RECOVERY_VERDICT_LABEL, nameOf } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import type {
    EquipStep,
    EquipTech,
    EquipmentAuto,
    EquipmentAutoState,
    RecoveryOption,
    Task,
} from '../../domain/types';
import { Chip, type ChipKind } from '../../shared/ui/atoms';
import styles from './equipment.module.css';

const TECH_CHIP: Record<EquipTech, ChipKind> = {
    OK: 'ok',
    FAULT: 'bad',
    CHECK: 'warn',
    UNKNOWN: '',
};

/** 기술 상태는 색만으로 판단하지 않도록 글자와 함께 보인다 */
export function TechChip({ tech }: { tech: EquipTech }) {
    return <Chip kind={TECH_CHIP[tech]}>{EQUIP_TECH_LABEL[tech]}</Chip>;
}

const AUTO_LABEL: Record<EquipmentAutoState, string> = {
    UNLINKED: '등록 안됨',
    CODE_PENDING: '코드 대기',
    CONNECTED: '연결됨',
    PROGRAM_OFFLINE: '프로그램 끊김',
    RECEIVER_NOT_RESPONDING: '수신기 응답 없음',
};
const AUTO_CHIP: Record<EquipmentAutoState, ChipKind> = {
    UNLINKED: '',
    CODE_PENDING: 'blue',
    CONNECTED: 'ok',
    PROGRAM_OFFLINE: 'warn',
    RECEIVER_NOT_RESPONDING: 'bad',
};

/** 자동 관측 상태(#398). 기술 상태와 따로 보인다. 기술 상태는 서버가 자동 관측으로 맞춘 값을 그대로 보인다(V9-D64). */
export function AutoChip({ auto }: { auto: EquipmentAuto }) {
    return <Chip kind={AUTO_CHIP[auto.state]}>자동 {AUTO_LABEL[auto.state]}</Chip>;
}

const unknown = '모름';
/** 자동 관측 값. 모르는 값(null)은 0·정상이 아니라 "모름"으로 보인다. 원시 눈금이라 단위를 붙이지 않는다. */
export function AutoDetail({ auto }: { auto: EquipmentAuto }) {
    const live = auto.state === 'CONNECTED';
    return (
        <dl className="kv" aria-label="자동 관측">
            <dt>자동 관측</dt>
            <dd>
                <AutoChip auto={auto} />
                {auto.inputKind && ` ${auto.inputKind === 'WEB' ? '웹 연결' : '예비 수집 경로'}`}
            </dd>
            <dt>송신기</dt>
            <dd>{auto.transmitterOn === null ? unknown : auto.transmitterOn ? '켜짐' : '꺼짐'}</dd>
            <dt>배터리</dt>
            <dd>{auto.batteryBars === null ? unknown : `${auto.batteryBars}칸`}</dd>
            <dt>RF</dt>
            <dd>{auto.rfLevel ?? unknown}</dd>
            <dt>오디오 신호</dt>
            <dd>{auto.audioLevel ?? unknown}</dd>
            <dt>마지막 수신</dt>
            <dd>
                {auto.lastReceivedAt ?? '받은 적 없음'}
                {!live && auto.lastReceivedAt && ' (지금 값이 아닐 수 있음)'}
            </dd>
        </dl>
    );
}

const VERDICT_CHIP: Record<RecoveryOption['verdict'], ChipKind> = {
    OK: 'ok',
    CONDITIONAL: 'warn',
    NO: 'bad',
    UNKNOWN: '',
};
export function VerdictChip({ verdict }: { verdict: RecoveryOption['verdict'] }) {
    return <Chip kind={VERDICT_CHIP[verdict]}>{RECOVERY_VERDICT_LABEL[verdict]}</Chip>;
}

/** 신호 경로: 이 장비에서 최종 출력까지. 첫 화면을 그래프로 채우지 않고 상세 탭에서만 보인다. */
export function SignalPath({ path }: { path: string[] }) {
    const d = useData();
    return (
        <ol className={styles.path}>
            {path.map((id) => {
                const a = d.equipment.find((x) => x.id === id);
                return (
                    <li key={id}>
                        <b>{a?.name ?? id}</b>
                        {a && <TechChip tech={a.tech} />}
                    </li>
                );
            })}
        </ol>
    );
}

const STEPS: [EquipStep, string][] = [
    ['pickup', '인수'],
    ['install', '설치'],
    ['retest', '재시험'],
];

/**
 * 업무 상세의 장비 수행(M05): 장비·인수 위치·도착 구역·이유와 인수/설치/재시험을 각각 기록한다.
 * 예약/배정은 소지·설치가 아니므로 담당자가 실제로 한 단계만 명시 기록한다.
 */
export function TaskEquipmentSection({ task: t }: { task: Task }) {
    const d = useData();
    const { me } = useSession();
    const ev = useEvent();
    const dispatch = useDispatch();
    if (!t.equipment) return null;
    const e = t.equipment;
    // 종료(ENDED) 뒤에는 읽기만 한다: 체크·시작/완료·수행 기록을 막는다
    const mine =
        ev.state === 'LIVE' && t.assignee === me && t.status !== 'done' && t.status !== 'canceled';
    const next = STEPS.find(([k]) => !e.records[k])?.[0];
    return (
        <div className="stack s6">
            <dl className="kv">
                <dt>장비</dt>
                <dd>
                    {e.assets
                        .map((id) => d.equipment.find((a) => a.id === id)?.name ?? id)
                        .join(', ')}
                </dd>
                <dt>인수 위치</dt>
                <dd>{e.pickup}</dd>
                <dt>도착 구역</dt>
                <dd>{e.dest}</dd>
                <dt>이유</dt>
                <dd>{e.reason}</dd>
            </dl>
            <ol className={styles.steps}>
                {STEPS.map(([k, label]) => (
                    <li key={k} data-done={!!e.records[k]}>
                        <span className="grow">
                            <b>{label}</b>{' '}
                            {e.records[k] ? (
                                <span className="xs muted">{e.records[k]} 기록</span>
                            ) : (
                                <span className="xs muted">기록 전</span>
                            )}
                        </span>
                        {mine && k === next && (
                            <button
                                type="button"
                                className="btn sm primary"
                                onClick={() =>
                                    dispatch({ type: 'equipmentRecord', taskId: t.id, step: k })
                                }
                            >
                                {label} 기록
                            </button>
                        )}
                    </li>
                ))}
            </ol>
            <p className="xs muted">
                예약/배정만으로 소지/설치가 기록되지 않습니다. 재시험 뒤 객석 출력 확인은 보고로
                남기세요. 담당 {nameOf(d, t.assignee)}
            </p>
        </div>
    );
}
