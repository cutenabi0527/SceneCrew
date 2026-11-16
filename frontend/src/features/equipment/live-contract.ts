import {
    ContractViolationError,
    asArray,
    asBigIntString,
    asRecord,
    asString,
    asUtcTimestamp,
    asUuid,
} from '../../shared/api/contract';
import { checkV9Shape } from '../../shared/api/v9';

// 장비 현황 조회 API(EXT01·EXT03·EXT05)의 응답 검사.
// 기준: #327 v9 계약(docs/api-contract/drafts/v9-327, develop 병합본). EXT01·03·05는 이전 초안 v9-320과 같다.
// 먼저 v9 스키마로 필수·형식·enum을 검사하고(계약에 없는 필드는 허용), 화면에 필요한 필드만 읽는다.
// 미확인·미지원 값을 정상/0으로 바꾸지 않는다(BE13).

export const TECHNICAL_STATES = [
    'UNKNOWN',
    'CHECK_REQUIRED',
    'VERIFIED_USABLE',
    'UNUSABLE',
] as const;
export type TechnicalState = (typeof TECHNICAL_STATES)[number];

export const SOURCE_KINDS = [
    'MANUAL_CONFIRMATION',
    'REHEARSAL_MEASUREMENT',
    'AUTOMATIC_MEASUREMENT',
    'SIMULATION_ASSUMPTION',
] as const;
export type SourceKind = (typeof SOURCE_KINDS)[number];

export const RESERVATION_STATUSES = ['PLANNED', 'ACTIVE', 'RELEASED', 'CANCELED'] as const;
export type ReservationStatus = (typeof RESERVATION_STATUSES)[number];

export type Evidence = {
    sourceKind: SourceKind;
    sourceRef: string;
    observedAt: string | null;
    recordedAt: string;
    confirmedByMemberId: string | null;
    confirmed: boolean;
};

export type LiveAsset = {
    id: string;
    eventId: string;
    name: string;
    model: string | null;
    technicalState: TechnicalState;
    stateEvidence: Evidence;
    componentAssetIds: string[];
    ports: { key: string; kind: string }[];
    verifiedCompatibility: Compatibility[];
    storageZoneId: string | null;
    holderMemberId: string | null;
    revision: string;
    /** 자동 관측(#394·#398, V9-D59). 자동 관측 종류가 있는 장비에만 있다. 모르는 값은 null 그대로 둔다. */
    autoObservation?: AutoObservation;
};

export const AUTO_STATES = [
    'UNLINKED',
    'CODE_PENDING',
    'CONNECTED',
    'PROGRAM_OFFLINE',
    'RECEIVER_NOT_RESPONDING',
] as const;
export type AutoState = (typeof AUTO_STATES)[number];

export type AutoObservation = {
    kind: string;
    inputKind: 'WEB' | 'CONFIG' | null;
    state: AutoState;
    transmitterOn: boolean | null;
    batteryBars: number | null;
    rfLevel: number | null;
    audioLevel: number | null;
    lastReceivedAt: string | null;
};

/** 확인된 호환 조합. 복구 대안 계산의 근거이며 화면은 아직 표시하지 않는다. */
export type Compatibility = {
    assetIds: string[];
    portKeys: string[];
    componentAssetIds: string[];
    evidence: Evidence;
};

export type LiveReservation = {
    id: string;
    eventId: string;
    assetIds: string[];
    programId: string | null;
    taskId: string | null;
    startAt: string;
    endAt: string;
    status: ReservationStatus;
    revision: string;
};

export type LiveConnection = {
    id: string;
    fromAssetId: string;
    fromPort: string;
    toAssetId: string;
    toPort: string;
    confirmedAt: string;
    active: boolean;
};

export type LiveRequirement = {
    id: string;
    programId: string | null;
    taskIds: string[];
    readyBy: string;
    quantity: number;
    scheduleMode: 'FIXED' | 'CHANGE_ALLOWED';
};

export type LiveDetail = {
    asset: LiveAsset;
    connections: LiveConnection[];
    requirements: LiveRequirement[];
    reservations: LiveReservation[];
    eventRevision: string;
};

export type Page<T> = { items: T[]; nextCursor: string | null };

function oneOf<T extends string>(value: unknown, allowed: readonly T[], path: string): T {
    const text = asString(value, path);
    if (!(allowed as readonly string[]).includes(text)) {
        throw new ContractViolationError(path, `정해진 값이 아닙니다: ${text}`);
    }
    return text as T;
}
const nonEmpty = (value: unknown, path: string) => {
    const text = asString(value, path);
    if (!text) throw new ContractViolationError(path, '빈 문자열일 수 없습니다.');
    return text;
};
const nullable = <T>(value: unknown, path: string, parse: (v: unknown, p: string) => T) => {
    if (value === undefined) throw new ContractViolationError(path, '필수 필드입니다(null 허용).');
    return value === null ? null : parse(value, path);
};
const uuids = (value: unknown, path: string) =>
    asArray(value, path).map((v, i) => asUuid(v, `${path}[${i}]`));
const bool = (value: unknown, path: string) => {
    if (typeof value !== 'boolean') throw new ContractViolationError(path, 'boolean이어야 합니다.');
    return value;
};

export function parseEvidence(value: unknown, path: string): Evidence {
    const r = asRecord(value, path);
    return {
        sourceKind: oneOf(r.sourceKind, SOURCE_KINDS, `${path}.sourceKind`),
        sourceRef: nonEmpty(r.sourceRef, `${path}.sourceRef`),
        observedAt: nullable(r.observedAt, `${path}.observedAt`, asUtcTimestamp),
        recordedAt: asUtcTimestamp(r.recordedAt, `${path}.recordedAt`),
        confirmedByMemberId: nullable(r.confirmedByMemberId, `${path}.confirmedByMemberId`, asUuid),
        confirmed: bool(r.confirmed, `${path}.confirmed`),
    };
}

export function parseAsset(value: unknown, path = 'asset'): LiveAsset {
    const r = asRecord(value, path);
    return {
        id: asUuid(r.id, `${path}.id`),
        eventId: asUuid(r.eventId, `${path}.eventId`),
        name: nonEmpty(r.name, `${path}.name`),
        model: nullable(r.model, `${path}.model`, nonEmpty),
        technicalState: oneOf(r.technicalState, TECHNICAL_STATES, `${path}.technicalState`),
        stateEvidence: parseEvidence(r.stateEvidence, `${path}.stateEvidence`),
        componentAssetIds: uuids(r.componentAssetIds, `${path}.componentAssetIds`),
        ports: asArray(r.ports, `${path}.ports`).map((p, i) => {
            const port = asRecord(p, `${path}.ports[${i}]`);
            return {
                key: nonEmpty(port.key, `${path}.ports[${i}].key`),
                kind: nonEmpty(port.kind, `${path}.ports[${i}].kind`),
            };
        }),
        verifiedCompatibility: asArray(
            r.verifiedCompatibility,
            `${path}.verifiedCompatibility`,
        ).map((c, i) => parseCompatibility(c, `${path}.verifiedCompatibility[${i}]`)),
        storageZoneId: nullable(r.storageZoneId, `${path}.storageZoneId`, asUuid),
        holderMemberId: nullable(r.holderMemberId, `${path}.holderMemberId`, asUuid),
        revision: asBigIntString(r.revision, `${path}.revision`),
        ...(r.autoObservation === undefined || r.autoObservation === null
            ? {}
            : {
                  autoObservation: parseAutoObservation(
                      r.autoObservation,
                      `${path}.autoObservation`,
                  ),
              }),
    };
}

const count = (value: unknown, path: string) => {
    if (typeof value !== 'number' || !Number.isFinite(value))
        throw new ContractViolationError(path, '숫자여야 합니다.');
    return value;
};

/** 자동 관측 값. 모르는 칸(null)은 0·정상으로 바꾸지 않는다(BE13·V9-D60). */
export function parseAutoObservation(value: unknown, path = 'autoObservation'): AutoObservation {
    const r = asRecord(value, path);
    return {
        kind: nonEmpty(r.kind, `${path}.kind`),
        inputKind: nullable(r.inputKind, `${path}.inputKind`, (v, p) =>
            oneOf(v, ['WEB', 'CONFIG'] as const, p),
        ),
        state: oneOf(r.state, AUTO_STATES, `${path}.state`),
        transmitterOn: nullable(r.transmitterOn, `${path}.transmitterOn`, bool),
        batteryBars: nullable(r.batteryBars, `${path}.batteryBars`, count),
        rfLevel: nullable(r.rfLevel, `${path}.rfLevel`, count),
        audioLevel: nullable(r.audioLevel, `${path}.audioLevel`, count),
        lastReceivedAt: nullable(r.lastReceivedAt, `${path}.lastReceivedAt`, asUtcTimestamp),
    };
}

export const AUTO_STATE_LABEL: Record<AutoState, string> = {
    UNLINKED: '등록 안됨',
    CODE_PENDING: '코드 대기',
    CONNECTED: '연결됨',
    PROGRAM_OFFLINE: '프로그램 끊김',
    RECEIVER_NOT_RESPONDING: '수신기 응답 없음',
};

function parseCompatibility(value: unknown, path: string): Compatibility {
    const r = asRecord(value, path);
    const assetIds = uuids(r.assetIds, `${path}.assetIds`);
    if (assetIds.length === 0) {
        throw new ContractViolationError(`${path}.assetIds`, '장비가 1개 이상이어야 합니다.');
    }
    return {
        assetIds,
        portKeys: asArray(r.portKeys, `${path}.portKeys`).map((k, i) =>
            nonEmpty(k, `${path}.portKeys[${i}]`),
        ),
        componentAssetIds: uuids(r.componentAssetIds, `${path}.componentAssetIds`),
        evidence: parseEvidence(r.evidence, `${path}.evidence`),
    };
}

export function parseReservation(value: unknown, path = 'reservation'): LiveReservation {
    const r = asRecord(value, path);
    const startAt = asUtcTimestamp(r.startAt, `${path}.startAt`);
    const endAt = asUtcTimestamp(r.endAt, `${path}.endAt`);
    // 점유는 startAt < endAt 반개구간이다(BE13)
    if (Date.parse(startAt) >= Date.parse(endAt)) {
        throw new ContractViolationError(`${path}.endAt`, 'startAt보다 뒤여야 합니다.');
    }
    return {
        id: asUuid(r.id, `${path}.id`),
        eventId: asUuid(r.eventId, `${path}.eventId`),
        assetIds: uuids(r.assetIds, `${path}.assetIds`),
        programId: nullable(r.programId, `${path}.programId`, asUuid),
        taskId: nullable(r.taskId, `${path}.taskId`, asUuid),
        startAt,
        endAt,
        status: oneOf(r.status, RESERVATION_STATUSES, `${path}.status`),
        revision: asBigIntString(r.revision, `${path}.revision`),
    };
}

function parseConnection(value: unknown, path: string): LiveConnection {
    const r = asRecord(value, path);
    return {
        id: asUuid(r.id, `${path}.id`),
        fromAssetId: asUuid(r.fromAssetId, `${path}.fromAssetId`),
        fromPort: nonEmpty(r.fromPort, `${path}.fromPort`),
        toAssetId: asUuid(r.toAssetId, `${path}.toAssetId`),
        toPort: nonEmpty(r.toPort, `${path}.toPort`),
        confirmedAt: asUtcTimestamp(r.confirmedAt, `${path}.confirmedAt`),
        active: bool(r.active, `${path}.active`),
    };
}

function parseRequirement(value: unknown, path: string): LiveRequirement {
    const r = asRecord(value, path);
    const items = asArray(r.requirements, `${path}.requirements`);
    const quantity = items.reduce<number>((sum, item, i) => {
        const q = asRecord(item, `${path}.requirements[${i}]`).quantity;
        if (!Number.isInteger(q) || (q as number) < 1) {
            throw new ContractViolationError(
                `${path}.requirements[${i}].quantity`,
                '1 이상 정수여야 합니다.',
            );
        }
        return sum + (q as number);
    }, 0);
    const policy = asRecord(r.schedulePolicy, `${path}.schedulePolicy`);
    return {
        id: asUuid(r.id, `${path}.id`),
        programId: nullable(r.programId, `${path}.programId`, asUuid),
        taskIds: uuids(r.taskIds, `${path}.taskIds`),
        readyBy: asUtcTimestamp(r.readyBy, `${path}.readyBy`),
        quantity,
        scheduleMode: oneOf(
            policy.mode,
            ['FIXED', 'CHANGE_ALLOWED'] as const,
            `${path}.schedulePolicy.mode`,
        ),
    };
}

/** 목록 응답의 nextCursor: 필수 속성. 다음은 비어 있지 않은 문자열, 끝은 JSON null(T-03-B). */
function parsePage<T>(value: unknown, path: string, item: (v: unknown, p: string) => T): Page<T> {
    const r = asRecord(value, path);
    if (!('nextCursor' in r))
        throw new ContractViolationError(`${path}.nextCursor`, '필수 필드입니다.');
    const cursor = r.nextCursor;
    if (cursor !== null && (typeof cursor !== 'string' || !cursor || cursor === 'null')) {
        throw new ContractViolationError(
            `${path}.nextCursor`,
            '비어 있지 않은 문자열 또는 null이어야 합니다.',
        );
    }
    return {
        items: asArray(r.items, `${path}.items`).map((v, i) => item(v, `${path}.items[${i}]`)),
        nextCursor: cursor as string | null,
    };
}

export const parseAssetPage = (value: unknown) => {
    checkV9Shape('V9EquipmentAssetPage', value);
    return parsePage(value, 'EquipmentAssetPage', parseAsset);
};
export const parseReservationPage = (value: unknown) => {
    checkV9Shape('V9EquipmentReservationPage', value);
    return parsePage(value, 'EquipmentReservationPage', parseReservation);
};

export function parseDetail(value: unknown): LiveDetail {
    checkV9Shape('V9EquipmentDetail', value);
    const r = asRecord(value, 'EquipmentDetail');
    return {
        asset: parseAsset(r.asset, 'EquipmentDetail.asset'),
        connections: asArray(r.connections, 'EquipmentDetail.connections').map((v, i) =>
            parseConnection(v, `EquipmentDetail.connections[${i}]`),
        ),
        requirements: asArray(r.requirements, 'EquipmentDetail.requirements').map((v, i) =>
            parseRequirement(v, `EquipmentDetail.requirements[${i}]`),
        ),
        reservations: asArray(r.reservations, 'EquipmentDetail.reservations').map((v, i) =>
            parseReservation(v, `EquipmentDetail.reservations[${i}]`),
        ),
        eventRevision: asBigIntString(r.eventRevision, 'EquipmentDetail.eventRevision'),
    };
}

export const TECH_LABEL: Record<TechnicalState, string> = {
    VERIFIED_USABLE: '사용 가능 확인',
    CHECK_REQUIRED: '점검 필요',
    UNUSABLE: '사용 불가',
    UNKNOWN: '미확인',
};
export const SOURCE_LABEL: Record<SourceKind, string> = {
    MANUAL_CONFIRMATION: '담당자 확인',
    REHEARSAL_MEASUREMENT: '리허설 측정',
    AUTOMATIC_MEASUREMENT: '자동 계측',
    SIMULATION_ASSUMPTION: '시뮬레이션 가정',
};
export const RESERVATION_LABEL: Record<ReservationStatus, string> = {
    PLANNED: '예정',
    ACTIVE: '사용 중',
    RELEASED: '반납',
    CANCELED: '취소',
};
