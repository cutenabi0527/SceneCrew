import { ContractViolationError } from '../../shared/api/contract';
import { checkV9Shape } from '../../shared/api/v9';
import type { MyEquipmentLink, MyReceiver } from '../equipment-link/contract';

// 카메라 웹 연결(#393 BE, #396 FE, V9-D58·D60)의 응답 검사. 스키마는 v9 계약 묶음의 final393W1~W4다.
// 내 연결 목록은 계약에 없는 칸을 받는다(장비 칸 #398처럼 BE가 칸을 더해도 화면이 깨지지 않게).

export type ConnectionState =
    'UNLINKED' | 'DISABLED' | 'CONNECTING' | 'CONNECTED' | 'OFFLINE' | 'ERROR';

export interface MyCameraLink {
    id: string;
    name: string;
    revision: string;
    enabled: boolean;
    connectionState: ConnectionState;
    inputKind: 'BROWSER' | 'COLLECTOR' | null;
    /** 지금 내가 연결해 둔 칸(다른 브라우저일 수도 있다) */
    linkedByMe: boolean;
    /** 구역 보정 전. 인원을 세지 않는다 */
    calibrationRequired: boolean;
    /** 지금 연결을 시작할 자격·상태인지. 다른 브라우저가 보내는 중인지는 보지 않는다(그때는 409 TAKEN) */
    canLink: boolean;
    linkTask: {
        id: string;
        title: string;
        status: 'WAITING' | 'IN_PROGRESS' | 'DONE' | 'CANCELED';
    } | null;
    lastReceivedAt: string | null;
}

export interface MyLinks {
    cameras: MyCameraLink[];
    /** 장비 연결(#394·#398). 팀원은 연결 업무를 맡았거나 자기가 연결한 장비만, 총괄은 모든 자동 관측 장비 */
    equipment: MyEquipmentLink[];
    /** 내가 등록했고 폐기되지 않은 수집기가 보고한 수신기 */
    receivers: MyReceiver[];
}

export interface CameraLink {
    linkId: string;
    cameraRevision: string;
}

export interface FrameAck {
    receivedAt: string;
    sequence: string;
}

export function parseMyLinks(data: unknown): MyLinks {
    return checkV9Shape<MyLinks>('V9MyLinks', data);
}

export function parseCameraLink(data: unknown): CameraLink {
    return checkV9Shape<CameraLink>('V9CameraLink', data);
}

export function parseCameraUnlink(data: unknown): { cameraRevision: string } {
    return checkV9Shape<{ cameraRevision: string }>('V9CameraUnlink', data);
}

/** 프레임 접수 확인. 보낸 순번을 알면 같은 순번의 확인인지 본다 */
export function parseFrameAck(data: unknown, sequence?: number): FrameAck {
    const ack = checkV9Shape<FrameAck>('V9FrameAck', data);
    if (sequence !== undefined && ack.sequence !== String(sequence))
        throw new ContractViolationError('frame.sequence', '보낸 순번의 확인이어야 합니다.');
    return ack;
}
