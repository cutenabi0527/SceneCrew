import { ContractViolationError } from '../../shared/api/contract';
import { checkV9Shape, parseV9 } from '../../shared/api/v9';

// 고정 카메라 안전 지도(#309, W05 총괄 전용)의 응답 검사. v9 계약 묶음의 스키마를 그대로 쓴다.
// 관측은 추정값이다. 낡음·시계 불명·매핑 무효는 현재 정상/0명으로 바꾸지 않고 그대로 표시한다.

export type Freshness = 'UNKNOWN' | 'FRESH' | 'STALE' | 'INVALID_MAPPING';
export type RiskLevel =
    'UNASSESSED' | 'WITHIN_CONFIRMED_RULE' | 'ATTENTION' | 'EXCEEDS_CONFIRMED_RULE';

export interface Point {
    x: number;
    y: number;
}

export interface Estimate {
    zoneId: string;
    estimatedCount: number | null;
    densityPerM2: number | null;
    qualityReasons: string[];
    riskLevel: RiskLevel;
    ruleRef: unknown;
}

export interface Observation {
    id: string;
    eventId: string;
    cameraId: string;
    capturedAt: string | null;
    receivedAt: string;
    state: 'RECEIVED' | 'PROCESSING' | 'READY' | 'NEEDS_REVIEW' | 'FAILED' | 'EXPIRED';
    freshness: Freshness;
    estimates: Estimate[];
    modelVersion: string | null;
    processedAt: string | null;
    [key: string]: unknown;
}

export interface SafetyMap {
    eventId: string;
    eventRevision: string;
    layout: {
        id: string;
        width: number;
        height: number;
        zonePolygons: { zoneId: string; points: Point[] }[];
        [key: string]: unknown;
    } | null;
    profiles: {
        zoneId: string;
        effectiveAreaM2: number | null;
        minimumCrewRule: unknown;
        riskRule: unknown;
        [key: string]: unknown;
    }[];
    observations: Observation[];
}

export interface Camera {
    id: string;
    eventId: string;
    name: string;
    /** 연결 식별자. 현장 노트북 webcam.py의 SCENECREW_CAMERA_SOURCE와 같아야 이 카메라로 들어온다.
     *  웹으로 연결하는 카메라(#393, V9-D58)는 없을 수 있다(null). */
    sourceBindingRef: string | null;
    enabled: boolean;
    revision: string;
    /** UNLINKED: 웹 연결 카메라를 아직 아무도 연결하지 않음(등록 안됨, #393) */
    connectionState: 'UNLINKED' | 'DISABLED' | 'CONNECTING' | 'CONNECTED' | 'OFFLINE' | 'ERROR';
    /** 입력 종류: BROWSER(웹 연결)·COLLECTOR(예비 수집 프로그램)·null(아직 입력 없음) */
    inputKind: 'BROWSER' | 'COLLECTOR' | null;
    /** 연결 담당 업무 */
    linkTaskId: string | null;
    /** 지금 웹으로 연결해 둔 사람(Member) */
    linkMemberId: string | null;
    linkedAt: string | null;
    lastCapturedAt: string | null;
    lastReceivedAt: string | null;
    lastProcessedAt: string | null;
    latestObservationId: string | null;
    freshness: Freshness;
    coverage: { zoneId: string; scope: 'FULL' | 'PARTIAL' | 'UNDETERMINED'; roi?: Point[] }[];
    [key: string]: unknown;
}

export interface Page<T> {
    items: T[];
    nextCursor: string | null;
}

const sameEvent = (value: { eventId: string }, eventId: string, path: string) => {
    if (value.eventId !== eventId)
        throw new ContractViolationError(path, '현재 공연이어야 합니다.');
};

export function parseSafetyMap(data: unknown, eventId: string): SafetyMap {
    const map = parseV9<SafetyMap>('V9SafetyMap', data);
    sameEvent(map, eventId, 'safetyMap.eventId');
    map.observations.forEach((o) => sameEvent(o, eventId, 'safetyMap.observations'));
    return map;
}

export function parseObservationPage(data: unknown, eventId: string): Page<Observation> {
    const page = parseV9<Page<Observation>>('V9SafetyObservationPage', data);
    page.items.forEach((o) => sameEvent(o, eventId, 'observations.eventId'));
    return page;
}

export function parseObservation(data: unknown, eventId: string, id: string): Observation {
    const o = parseV9<Observation>('V9SafetyObservation', data);
    sameEvent(o, eventId, 'observation.eventId');
    if (o.id !== id)
        throw new ContractViolationError('observation.id', '요청한 관측이어야 합니다.');
    return o;
}

// 카메라 응답은 계약에 없는 칸을 받는다(#386·#389와 같은 방식). BE가 칸을 먼저 더해도 카메라 화면이 깨지지 않게 한다.
// 웹 연결(#393)의 등록 안됨(UNLINKED)·연결 식별자 없음(null)·새 칸은 계약 묶음에 들어 있어 그대로 검사한다.
export function parseCameraPage(data: unknown, eventId: string): Page<Camera> {
    const page = checkV9Shape<Page<Camera>>('V9SafetyCameraPage', data);
    page.items.forEach((c) => sameEvent(c, eventId, 'cameras.eventId'));
    return page;
}

export function parseCamera(data: unknown, eventId: string, id: string): Camera {
    const c = checkV9Shape<Camera>('V9SafetyCamera', data);
    sameEvent(c, eventId, 'camera.eventId');
    if (c.id !== id) throw new ContractViolationError('camera.id', '요청한 카메라여야 합니다.');
    return c;
}

/** 연결한 지 이만큼 지나도 첫 사진이 없으면 "첫 사진 없음"(BE는 CONNECTING을 끊김으로 바꾸지 않는다) */
export const FIRST_FRAME_WAIT_MS = 15_000;

/** 연결 중(CONNECTING)인데 연결 시각에서 15초가 지나도록 사진이 오지 않았다 */
export function noFirstFrame(
    c: Pick<Camera, 'connectionState' | 'linkedAt' | 'lastReceivedAt'>,
    now = Date.now(),
) {
    return (
        c.connectionState === 'CONNECTING' &&
        !c.lastReceivedAt &&
        !!c.linkedAt &&
        now - Date.parse(c.linkedAt) > FIRST_FRAME_WAIT_MS
    );
}

/** 구역별로 가장 최근에 받은 관측의 추정값을 고른다. 같은 구역의 여러 관측을 더하지 않는다. */
export function latestByZone(observations: Observation[]) {
    const out = new Map<string, { estimate: Estimate; observation: Observation }>();
    const time = (o: Observation) => Date.parse(o.capturedAt ?? o.receivedAt);
    for (const o of observations) {
        for (const e of o.estimates) {
            const prev = out.get(e.zoneId);
            if (!prev || time(o) > time(prev.observation))
                out.set(e.zoneId, { estimate: e, observation: o });
        }
    }
    return out;
}

export const RISK_LABEL: Record<RiskLevel, string> = {
    UNASSESSED: '기준 없음',
    WITHIN_CONFIRMED_RULE: '기준 이내',
    ATTENTION: '주의',
    EXCEEDS_CONFIRMED_RULE: '기준 초과',
};

export const RISK_ORDER: RiskLevel[] = [
    'EXCEEDS_CONFIRMED_RULE',
    'ATTENTION',
    'UNASSESSED',
    'WITHIN_CONFIRMED_RULE',
];

export const FRESHNESS_LABEL: Record<Freshness, string> = {
    UNKNOWN: '관측 시각 미상',
    FRESH: '최신',
    STALE: '오래된 관측',
    INVALID_MAPPING: '구역 매핑 확인 필요',
};

export const CONNECTION_LABEL: Record<Camera['connectionState'], string> = {
    UNLINKED: '등록 안됨',
    DISABLED: '사용 안 함',
    CONNECTING: '연결 중',
    CONNECTED: '연결됨',
    OFFLINE: '연결 끊김',
    ERROR: '오류',
};

/** 시연 웹캠 노트북의 기본 연결 식별자(BE #349 안내, camera/README.md) */
export const DEMO_CAMERA_SOURCE = 'webcam-1';

/**
 * 연결 식별자 형식(V9-D43, BE 검사와 같다): 1~100자 영문·숫자·`. _ : -`. 주소·계정 모양은 넣지 않는다.
 * 틀리면 고칠 말을 돌려준다.
 */
export function bindingProblem(value: string): string | null {
    const v = value.trim();
    if (!v) return null;
    if (v.includes('://') || v.includes('@'))
        return '연결 식별자에는 주소나 계정을 넣지 않습니다. 노트북 설정의 이름만 적어 주세요(예: webcam-1).';
    if (!/^[A-Za-z0-9._:-]{1,100}$/.test(v))
        return '연결 식별자는 영문·숫자와 . _ : - 로 100자 이하입니다(예: webcam-1).';
    return null;
}

/** 카메라 연결 상태에 따라 총괄이 할 일 */
export function connectionHint(camera: Camera): string | null {
    const source = camera.sourceBindingRef;
    switch (camera.connectionState) {
        case 'CONNECTING':
            return source === null
                ? '웹 연결을 시작했지만 아직 사진이 들어오지 않았습니다. 연결한 노트북의 브라우저 탭이 열려 있는지 확인해 주세요.'
                : `아직 사진이 들어오지 않았습니다. 현장 노트북에서 SCENECREW_CAMERA_SOURCE=${source}로 webcam.py를 실행하면 연결됨으로 바뀝니다.`;
        case 'OFFLINE':
            return source === null
                ? '15초 넘게 사진이 오지 않습니다. 연결한 노트북의 브라우저 탭이 닫히거나 잠들지 않았는지 확인해 주세요.'
                : `15초 넘게 사진이 오지 않습니다. 현장 노트북의 webcam.py가 켜져 있는지, 연결 식별자가 ${source}인지 확인해 주세요.`;
        case 'ERROR':
            return '카메라 입력에 오류가 있습니다. 현장 노트북의 webcam.py 로그를 확인해 주세요.';
        case 'DISABLED':
            return '사용 안 함으로 둔 카메라입니다. 사진을 받지 않습니다.';
        case 'UNLINKED':
            return '아직 연결하지 않은 카메라입니다. 연결 담당자가 노트북 웹의 내 연결에서 연결하면 사진이 들어옵니다.';
        default:
            return null;
    }
}

// 안전 인력 지원(#361, V9-D57) ------------------------------------------------

/** 지원 후보 조회 조건. 066 supportSelection에도 같은 값을 보낸다. */
export interface SupportQuery {
    observationId: string;
    sourceZoneId: string;
    targetZoneId: string;
    workGroupId: string;
    startAt: string;
    endAt: string;
}

export interface SupportCandidate {
    memberId: string;
    participationRevision: string;
    /** 지원 시간에 미루거나 넘길 원래 업무. BE가 항상 보낸다. */
    sourceTaskId: string;
    eligibilityReasons: string[];
}

export interface SupportCandidates {
    eventRevision: string;
    candidates: SupportCandidate[];
}

/** 066 supportSelection에 담을 수 있는 최대 인원 */
export const SUPPORT_MAX_PICK = 25;

export function parseSupportCandidates(data: unknown): SupportCandidates {
    const page = parseV9<SupportCandidates>('V9SafetySupportCandidatePage', data);
    const seen = new Set<string>();
    page.candidates.forEach((c, i) => {
        if (seen.has(c.memberId.toLowerCase()))
            throw new ContractViolationError(
                `candidates[${i}].memberId`,
                '같은 인원이 두 번 나왔습니다.',
            );
        seen.add(c.memberId.toLowerCase());
    });
    return page;
}

/** 066 supportSelection 본문. 고른 후보는 조회 응답 값 그대로 보낸다. */
export function supportSelection(query: SupportQuery, picked: SupportCandidate[]) {
    return {
        ...query,
        candidates: picked.map((c) => ({
            memberId: c.memberId,
            participationRevision: c.participationRevision,
            sourceTaskId: c.sourceTaskId,
        })),
    };
}

/**
 * 후보 자격 사유. BE는 문구가 아니라 코드를 보낸다(BE #361). 모르는 값은 받은 그대로 보인다.
 */
const ELIGIBILITY_TEXT: Record<string, string> = {
    CURRENT_PLACEMENT: '지금 원래 구역에 배치되어 있음',
    AVAILABLE: '지원 시간에 맡은 업무를 미루고 움직일 수 있음',
    MINIMUM_CREW_SATISFIED: '보내도 원래 구역 최소 인원이 유지됨',
};

export function eligibilityText(reason: string) {
    return ELIGIBILITY_TEXT[reason] ?? reason;
}

/** BE는 지금 시각을 포함한 지원 구간만 받는다(startAt <= now < endAt) */
export function windowProblem(startAt: string, endAt: string, now = Date.now()) {
    if (Date.parse(startAt) >= Date.parse(endAt))
        return '지원 종료 시각은 시작 시각보다 늦어야 합니다.';
    if (Date.parse(startAt) > now) return '지원 시작은 지금이거나 지금보다 앞이어야 합니다.';
    if (Date.parse(endAt) <= now) return '지원 종료 시각이 이미 지났습니다.';
    return null;
}

/**
 * 409 INVALID_STATE의 details.reason으로 화면이 할 일을 고른다(V9-D57, BE #361).
 *  reduce: 고른 후보를 남겨 인원을 줄이거나 중복을 정리하게 한다.
 *  rule: 최소 기준·지원 시간·구역을 확인하게 한다(후보는 남긴다).
 *  reselect: 후보를 버리고 최신 관측·후보를 다시 조회하게 한다.
 * VERSION_CONFLICT는 reason 없이 따로 다시 조회한다.
 */
export function supportAction(reason: string | undefined): 'reduce' | 'rule' | 'reselect' | null {
    if (!reason) return null;
    switch (reason) {
        case 'SUPPORT_MINIMUM_CREW':
        case 'SUPPORT_CANDIDATE_DUPLICATE':
            return 'reduce';
        case 'SUPPORT_CREW_RULE_UNAVAILABLE':
        case 'SUPPORT_WINDOW_OR_ZONE':
        case 'SUPPORT_WINDOW_NOT_CURRENT':
            return 'rule';
        case 'SUPPORT_CANDIDATE_CHANGED':
        case 'SUPPORT_TEST_OBSERVATION':
        case 'SUPPORT_OPERATIONS_CHANGED':
        case 'SUPPORT_ALREADY_IN_PROGRESS':
            return 'reselect';
    }
    return reason.startsWith('SUPPORT_OBSERVATION_') ? 'reselect' : null;
}
