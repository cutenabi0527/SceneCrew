// 기획 v9.0 콘서트 대표 장면을 총괄/팀원·평면 그룹·최초 자동 배정·revision 정책에 맞춘 화면용 모델.
// BE 계약이 정해지면 api 응답 → 이 모델 변환으로 교체한다. 장비·안전·상황 유형은 v9 목표 계약(EXT01~26)의
// 화면용 축약이며 아직 서버 API가 없어 예시 데이터로만 채운다.

import type { ChangeAlternative, ChangeCoordination } from '../features/response/contract';

/** 사람 권한은 두 가지뿐이다(P02). 예비는 권한이 아니라 팀원의 상태다. */
export type Role = 'CHIEF' | 'WORKER';
/** 최초 AI 자동 배정 상태(IA03 B3). 참여 성공과 별개이며 '참여 실패'로 보이면 안 된다. */
export type AssignState = 'WAITING' | 'RUNNING' | 'DONE' | 'NO_CANDIDATE' | 'FAILED' | 'UNKNOWN';
export type TaskStatus = 'todo' | 'doing' | 'done' | 'canceled';
export type IssueStatus = 'RECEIVED' | 'RESPONDING' | 'RESOLUTION_REPORTED' | 'CLOSED';
export type ChangeStatus = 'DRAFT' | 'READY' | 'APPLIED' | 'DISCARDED';
export type EventState = 'DRAFT' | 'RECRUITING' | 'LIVE' | 'ENDED';

/** 직무 그룹: 운영/접수/시연 같은 평면 분류. 한 그룹에 여러 팀원이 속하고 팀장·계층이 없다. */
export type WorkGroup = {
    id: string;
    name: string;
    guide: string;
    /**
     * 장비 현황 조회 허용(V9-D13). 켜진 그룹의 팀원은 WEB W03을 조회만 한다.
     * 등록·예약·복구 계산·변경안·승인은 총괄만 한다. 물리 저장 방식은 미결(V9-GAP-09)이다.
     */
    equipmentView?: boolean;
};

export type Member = {
    id: string;
    name: string;
    role: Role;
    /** 총괄은 그룹 없이도 행사를 구성할 수 있다 */
    groupId: string | null;
    isReserve: boolean;
    available: boolean;
    /** 가용 시간 */
    avail: string;
    skills?: string;
    wait?: string;
    /** 참여 뒤 최초 배정 상태. 총괄·예비·처음부터 배정된 사람은 없다. */
    assign?: AssignState;
    /** 총괄 확인을 기다리는 새 이름. 팀원의 이름 변경은 총괄이 승인해야 반영된다. */
    nameRequest?: string;
};

/** 초대를 받았지만 아직 그룹을 골라 참여하지 않은 계정 */
export type Invitee = { id: string; name: string };

/** required가 false이면 완료 조건이 아니다. 데모는 값이 없어 모두 필수다(실서버는 서버 값, #373). */
export type ChecklistItem = { text: string; done: boolean; required?: boolean };

export type Task = {
    id: string;
    title: string;
    groupId: string;
    zone: string;
    /** 업무 날짜. 없으면 행사 당일 업무로 본다. */
    date?: string;
    start: string;
    end: string;
    assignee: string | null;
    /** 같은 그룹 안에서 한 사람이 전담하는 업무 묶음 키(P03) */
    bundle: string;
    /** 여러 명 배정으로 복사된 업무면 원 업무 id. 같은 원 업무를 가진 업무들은 "같은 업무"다 */
    copyOf?: string;
    /** 처음 실제 담당이 저장된 시각. 있으면 최초 자동 배정 경로를 다시 쓰지 않는다. */
    firstAssignedAt?: string;
    program?: string;
    status: TaskStatus;
    ack: boolean;
    changed?: string;
    steps: string[];
    checklist: ChecklistItem[];
    /** 장비 업무: 인수·설치·재시험은 예약/배정과 별개의 실제 수행 기록이다 */
    equipment?: TaskEquipment;
    /** 안전 업무: 담당 구역의 최소 집계와 도착·현장 확인 기록 */
    safety?: TaskSafety;
};

export type EquipStep = 'pickup' | 'install' | 'retest';
export type TaskEquipment = {
    assets: string[];
    pickup: string;
    dest: string;
    reason: string;
    records: Partial<Record<EquipStep, string>>;
};
export type SafetyStep = 'arrive' | 'check';
export type TaskSafety = {
    zoneId: string;
    keep: string;
    records: Partial<Record<SafetyStep, string>>;
};

/** 제품 운영 상황6종(product08). 기존 모델 category와 다른 계약이다. */
export type SituationType =
    | 'SCHEDULE_DELAY'
    | 'STAFF_GAP'
    | 'TASK_CONFLICT'
    | 'EQUIPMENT_ANOMALY'
    | 'PERFORMANCE_ORDER_CHANGE'
    | 'AUDIENCE_SAFETY';
/** 대표 상황은 최대1개(null=미분류), 연관 상황은 대표를 뺀 중복 없는 목록 */
export type Situation = {
    primary: SituationType | null;
    related: SituationType[];
    source: '모델 추천' | '총괄 확인';
    by?: string;
    at?: string;
};

export type ReportKind = 'INITIAL' | 'FOLLOWUP' | 'RESOLUTION';
export type ReportVia = '텍스트' | '음성' | '빠른 보고';

export type IssueReport = {
    kind: ReportKind;
    by: string;
    at: string;
    via: ReportVia;
    text: string;
};

export type Issue = {
    id: string;
    title: string;
    summary: string;
    zone: string;
    /** 실서버: 이슈의 직무 그룹(workGroupId). 데모는 비우고 업무·보고자로 그룹을 정한다 */
    groupId?: string;
    status: IssueStatus;
    at: string;
    reporter: string;
    tasks: string[];
    reports: IssueReport[];
    analysis?: {
        category: string;
        state: string;
        items: string[];
        /** 실서버: 분석 뒤 공연 정보가 바뀌었는가(CURRENT 최신·STALE 낡음). 데모는 비운다. */
        validity?: 'CURRENT' | 'PARTIAL_STALE' | 'STALE';
    };
    /**
     * 실서버: 가장 최근 보고의 분석 진행 상태(#358). running 분석 중, failed 실패(code), missing 결과 없음
     * (실패했거나 그사이 공연 정보가 바뀌어 반영하지 않음). 결과가 있으면 비운다.
     */
    analysisStatus?: { kind: 'running' | 'failed' | 'missing'; code?: string };
    /** 실서버: 다시 분석할 가장 최근 보고 */
    latestReportId?: string;
    situation?: Situation;
    next?: string;
    closedAt?: string;
    /** 총괄이 다른 부서·사람에게 전달한 기록. 받은 부서(전체) 또는 사람은 이 이슈를 볼 수 있다 */
    forwards?: IssueForward[];
};

export type IssueForward = {
    at: string;
    by: string;
    /** 받은 부서. 사람을 고르면 그 부서 전체가 아니라 그 사람에게만 전달한다 */
    groupId: string | null;
    memberId: string | null;
    /** 본문에서 @이름으로 태그한 사람 */
    mentions: string[];
    note: string;
};

// ── 장비 운영(W03) ─────────────────────────────────────────

/** 기술 상태: 정상 / 이상 / 점검 필요 / 미확인. 사용·예약·공연 영향과 다른 값이다. */
export type EquipTech = 'OK' | 'FAULT' | 'CHECK' | 'UNKNOWN';
/** 값의 출처: 자동 관측·담당자 확인·미확인을 구분한다 */
export type FactSource = '자동 관측' | '담당자 확인' | '미확인';

export type EquipmentAsset = {
    id: string;
    name: string;
    kind: string;
    tech: EquipTech;
    place: string;
    source: FactSource;
    observedAt?: string;
    note?: string;
    /** 신호 경로: 이 장비에서 최종 출력까지 거치는 장비 id 순서 */
    path: string[];
    spare?: boolean;
    /** 자동 관측(실서버, #398). 자동 관측 종류가 있는 장비에만 있다. 모르는 값은 null로 둔다(0·정상으로 바꾸지 않음). */
    auto?: EquipmentAuto;
};

export type EquipmentAutoState =
    'UNLINKED' | 'CODE_PENDING' | 'CONNECTED' | 'PROGRAM_OFFLINE' | 'RECEIVER_NOT_RESPONDING';

export type EquipmentAuto = {
    state: EquipmentAutoState;
    /** 웹 연결(WEB)·예비 수집 경로(CONFIG). 모르면 null */
    inputKind: 'WEB' | 'CONFIG' | null;
    transmitterOn: boolean | null;
    batteryBars: number | null;
    rfLevel: number | null;
    audioLevel: number | null;
    /** 마지막 수신 시각(KST HH:MM:SS). 받은 적 없으면 null */
    lastReceivedAt: string | null;
};

export type ReservationKind = '준비' | '공연' | '회수' | '예약';
export type EquipmentReservation = {
    id: string;
    assetId: string;
    kind: ReservationKind;
    start: string;
    end: string;
    label: string;
    program?: string;
};

export type RecoveryVerdict = 'OK' | 'CONDITIONAL' | 'NO' | 'UNKNOWN';
/** 복구 대안 계산 결과. 선택은 미리 보기이며 변경안 승인 전에는 아무것도 바꾸지 않는다. */
export type RecoveryOption = {
    id: string;
    issueId: string;
    title: string;
    verdict: RecoveryVerdict;
    /** 준비 완료 예상(모르면 null) */
    readyAt: string | null;
    deadline: string;
    margin: string;
    assets: string[];
    work: { label: string; min: number | null }[];
    staff: string | null;
    effects: string[];
    reason: string;
    unknowns: string[];
    reservations: Omit<EquipmentReservation, 'id'>[];
    /** 이 대안을 확정하면 해제할 기존 예약 id(대체되는 장비의 준비·공연 등) */
    releases?: string[];
    task: { title: string; zone: string; start: string; end: string; steps: string[] } & Omit<
        TaskEquipment,
        'records'
    >;
};

// ── 안전(W05) ─────────────────────────────────────────────

export type SafetyZone = {
    id: string;
    name: string;
    /** 확인된 실제 유효 면적(㎡). 없으면 명/㎡를 계산하지 않는다. */
    area: number | null;
    /** 확인된 기준 밀집도(명/㎡). 없으면 기준 대비 여유를 표시하지 않는다. */
    limit: number | null;
    minStaff: number;
    /** 이 구역 인력을 옮길 수 없는 필수 업무 */
    essential?: string;
    /** 평면도 위치(%, x·y·w·h) */
    rect: [number, number, number, number];
};
/** 평면도 이미지(데모: 줄인 data URL). 원본 파일은 보관하지 않는다 */
export type FloorPlan = {
    src: string;
    width: number;
    height: number;
    name: string;
    /** 그림 안에서 실제 평면도가 놓인 영역(0~1). 있으면 이 영역만 크게 보인다(V9-D62) */
    crop?: { x: number; y: number; width: number; height: number } | null;
};
export type CameraLink = 'CONNECTED' | 'CONNECTING' | 'DISCONNECTED' | 'NONE';
export type SafetyCamera = {
    id: string;
    name: string;
    link: CameraLink;
    /** false면 비활성: 수집·미리보기를 닫는다 */
    active?: boolean;
    zones: string[];
    calibrated: boolean;
    calibratedAt?: string;
    /** 보정이 있었지만 카메라 이동·줌·구역 변경으로 무효가 된 이유 */
    calibrationInvalid?: string;
    /** 현재 영상 캡처 시각(집계 기준 시각과 다르다) */
    frameAt?: string;
    /** 실서버 웹 연결(#397, V9-D60): 칸만 있고 아직 아무도 연결하지 않음(등록 안됨) */
    unlinked?: boolean;
    /** 입력 종류: 웹 연결·예비 수집 프로그램 */
    input?: 'BROWSER' | 'COLLECTOR' | null;
    /** 지금 웹으로 연결해 둔 사람 이름 */
    linkedBy?: string | null;
    /** 연결한 지 15초가 넘도록 첫 사진이 오지 않았다 */
    noFirstFrame?: boolean;
};
export type ObsQuality = 'GOOD' | 'PARTIAL' | 'LOW' | 'NONE';
/** PENDING: 연결·보정 직후 첫 분석 대기 */
export type ObsFresh = 'PENDING' | 'FRESH' | 'STALE' | 'FAILED';
export type SafetyObservation = {
    zoneId: string;
    cameraId: string | null;
    count: number | null;
    at: string | null;
    quality: ObsQuality;
    fresh: ObsFresh;
    /** 실서버: 서버가 정한 기준 대비 판정(#372). 있으면 화면이 면적·기준으로 다시 계산하지 않는다 */
    risk?: 'OVER' | 'NEAR' | 'OK' | 'CHECK';
};

export type Slot = {
    start: string;
    end: string;
    /** null은 미배정(담당 해제), undefined는 담당을 건드리지 않음 */
    assignee?: string | null;
    zone?: string;
    /** 업무가 속한 부서(직무 그룹). 업무 update에서만 쓴다 */
    groupId?: string;
    /** 편집으로 바꾼 절차(업무 update). undefined면 절차를 건드리지 않는다 */
    steps?: string[];
    /** assignee 외에 함께 배정하는 사람. 확정하면 사람마다 같은 내용의 업무를 하나씩 받는다 */
    extraAssignees?: string[];
};

/** 변경안 편집기에서 고칠 수 있는 칸 */
export type EditableField = '시간' | '담당' | '추가 담당' | '절차';

/**
 * editable: 편집기에서 고칠 수 있는 칸. 없으면 모든 칸을 고칠 수 있다(데모).
 * 실서버 모드는 서버 연산이 받는 칸만 둔다(#371, 예: 담당 변경 연산은 담당만).
 */
export type ChangeOp =
    | { kind: 'program'; program: string; before: Slot; after: Slot; editable?: EditableField[] }
    | { kind: 'update'; taskId: string; before: Slot; after: Slot; editable?: EditableField[] }
    | { kind: 'cancel'; taskId: string; before: Slot }
    | {
          kind: 'add';
          tempId: string;
          groupId: string;
          /** 여러 명 배정으로 같은 업무를 복사해 만들 때 원 업무 id */
          copyOf?: string;
          after: Slot;
          title: string;
          steps: string[];
          equipment?: TaskEquipment;
          safety?: TaskSafety;
          editable?: EditableField[];
      };

/** 변경안(ChangeSet). 총괄만 확정하고, 만들 때의 Event.revision과 지금이 다르면 낡은 것이다. */
export type Change = {
    id: string;
    issueId: string | null;
    by: string;
    at: string;
    status: ChangeStatus;
    mode: 'AI' | 'MANUAL';
    title: string;
    baseRevision: number;
    ops: ChangeOp[];
    /** 함께 확정할 장비 예약(복구 대안) */
    reservations?: Omit<EquipmentReservation, 'id'>[];
    /** 함께 해제할 기존 예약 id(복구 대안) */
    releases?: string[];
    /** 어디서 만든 변경안인지(복구 대안·안전 지원·보고 대응) */
    source?: string;
    /** 함께 바뀐 같은 업무(여러 명에게 복사 배정된 업무)의 id. 변경 기록에서 링크로 보인다 */
    related?: string[];
    appliedAt?: string;
    /** 만들 때 본 미리 보기의 지문(ops·예약·기준 revision). 승인 직전 같은지 다시 확인한다 */
    previewHash?: string;
    /** 만들 때 근거로 쓴 관측. 매 프레임은 revision이 아니므로 승인 직전에 따로 비교한다(C08) */
    basis?: { obs?: { zoneId: string; at: string | null }[] };
    /** 총괄이 고쳐 쓴 확인 필요 사항. 한 줄이 한 항목이다 */
    checks?: string;
    /** 확인 필요 사항을 고칠 수 없다(실서버 모드: 서버 변경안에 이 칸이 없다) */
    fixedChecks?: boolean;
    /** AI 대응안의 참고 기록(실서버, V9-D55): 검토한 다른 방법과 AI 처리 기록. 승인 조건이 아니다 */
    agentRecords?: {
        alternatives?: ChangeAlternative[];
        coordination?: ChangeCoordination;
    };
};

/** assign: 최초 배정 알림(공식 확인 대상 아님) · change/deploy: 본인 공식 확인 대상 */
export type NoticeKind = 'report' | 'task' | 'change' | 'deploy' | 'assign' | 'profile';

export type Notice = {
    id: string;
    to: string;
    kind: NoticeKind;
    text: string;
    at: string;
    read: boolean;
    ack: boolean;
    issue?: string;
    change?: string;
    task?: string;
    /** 변경 확인 대상(본인 업무 id)과 알림 당시 대상 지문 */
    targetIds?: string[];
    targetHash?: string;
    /** 명시 확인 기록. 대상이 바뀌어 다시 확인해도 과거 확인 사실은 남긴다 */
    acks?: { at: string; hash: string }[];
    /** 실서버(#373): 변경 확인 대상(077의 확인 id)과 바뀐 업무. 팀원은 변경안을 읽지 못해 이 값으로 그린다 */
    ackId?: string;
    ackTargets?: { taskId: string; title: string; changes: string[]; removed: boolean }[];
};

export type LogEntry = { date: string; at: string; type: string; text: string; ref?: string };

/** 채팅은 고정된 전체 채널과 직무 그룹 채널만 있다(CUSTOM·멘션·핀·읽음 커서 없음). */
export type Room = {
    id: string;
    kind: 'EVENT' | 'GROUP';
    name: string;
    groupId?: string;
};

export type Message = {
    id: string;
    room: string;
    seq: number;
    by: string;
    at: string;
    text: string;
    /** 실서버(#373): 보낼 때의 보낸 사람 이름. 팀원은 인원 목록을 못 읽어 이 값으로 보인다 */
    name?: string;
    /** 실서버: 서버 저장 전 내 메시지의 상태(저장되면 없다) */
    state?: 'sending' | 'unknown' | 'failed';
};

export type Program = {
    id: string;
    name: string;
    /** 최신 승인 계획 시각(HH:MM) */
    start: string;
    end: string;
    /** 확인된 실제 시작/종료(HH:MM:SS). 계획 시각과 따로 두고 초 단위를 버리지 않는다 */
    actualStart?: string;
    actualEnd?: string;
};

export type EventInfo = {
    id: string;
    name: string;
    date: string;
    time: string;
    place: string;
    state: EventState;
    /** 현재 상태 번호(Event.revision). 과거 계획을 저장하는 버전이 아니다. */
    revision: number;
    zones: string[];
    programs: Program[];
    prep?: string;
    open?: string;
    close?: string;
    teardown?: string;
    capacity?: number;
    visitors?: string;
    /** 공통 안내 */
    notice?: string;
};

export type EventData = {
    groups: WorkGroup[];
    members: Member[];
    invitees: Invitee[];
    tasks: Task[];
    issues: Issue[];
    changes: Change[];
    notices: Notice[];
    log: LogEntry[];
    rooms: Room[];
    messages: Message[];
    equipment: EquipmentAsset[];
    reservations: EquipmentReservation[];
    recovery: RecoveryOption[];
    safetyZones: SafetyZone[];
    /** 총괄이 올린 평면도 이미지(없으면 기본 배치도). 구역 rect는 이 이미지 기준 %다 */
    floorPlan?: FloorPlan;
    cameras: SafetyCamera[];
    observations: SafetyObservation[];
    /** 실서버: 안전 지도 배치도에 도형이 없어 구역 이름으로 기본 배치를 그린 구역이 있다(#372) */
    safetyDefaultLayout?: boolean;
    /**
     * 최초 배정 묶음 판정 규칙. 'server'면 서버 045와 같이 취소 업무가 섞이거나 그룹이 섞인 묶음을
     * 고를 수 없다(실서버 모드). 없으면 데모 규칙(취소 업무를 빼고 묶음을 본다).
     */
    bundleRule?: 'server';
    /** 영구 삭제한 업무의 이름(id → 업무 이름). 업무는 지웠지만 변경 기록이 이름을 읽을 수 있게 남긴다 */
    purgedTasks?: Record<string, string>;
};

export type DemoState = {
    me: string | null;
    eventId: string | null;
    events: EventInfo[];
    data: EventData;
    seq: number;
    /** 실서버 모드(#362): 화면 상태를 실제 계정의 API 응답에서 채운다. 데모 값으로 섞지 않는다. */
    live?: boolean;
};

/**
 * 데모 '계획 수정'에서 저장하는 계획 전체(그룹·구역·공연 순서·업무·공통 안내).
 * 실서버의 계획 초안 저장(028)처럼 전체를 바꾼다. 업무 배정·진행 상태는 같은 id의 업무에서 이어 받는다.
 */
export type PlanEdit = {
    groups: { id: string; name: string; guide: string }[];
    zones: string[];
    programs: { id: string; name: string; start: string; end: string }[];
    tasks: {
        id: string;
        title: string;
        groupId: string;
        zone: string;
        program?: string;
        bundle: string;
        date?: string;
        start: string;
        end: string;
        steps: string[];
        checklist: { text: string; required: boolean }[];
    }[];
    notice: string;
};
