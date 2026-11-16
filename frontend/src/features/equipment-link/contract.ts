import { checkV9Shape } from '../../shared/api/v9';
import type { AutoObservation } from '../equipment/live-contract';

// 장비 웹 연결(#394 BE, #398 FE, V9-D59·D60)의 응답 검사. 스키마는 v9 계약 묶음의 final394W5~W7이다.
// 수집기 토큰·수신기 IP는 어느 응답에도 없다. 화면에도 보이지 않는다.

export interface MyEquipmentLink {
    id: string;
    name: string;
    revision: string;
    autoObservationKind: string;
    /** 지금 내가 연결해 둔 칸 */
    linkedByMe: boolean;
    /** 지금 연결(코드 받기·수신기 연결)을 시작할 자격·상태인지 */
    canLink: boolean;
    linkTask: {
        id: string;
        title: string;
        status: 'WAITING' | 'IN_PROGRESS' | 'DONE' | 'CANCELED';
    } | null;
    link: { collectorId: string; receiverKey: string; channel: number } | null;
    autoObservation: AutoObservation;
}

export interface MyReceiver {
    collectorId: string;
    /** 수집 프로그램을 켠 노트북 이름 */
    hostLabel: string | null;
    receiverKey: string;
    name: string | null;
    model: string | null;
    channels: number;
    seenAt: string;
    linkedAssetId: string | null;
}

export interface LinkCode {
    code: string;
    expiresAt: string;
}

export interface LinkReceipt {
    id: string;
    revision: string;
}

/** WEB `/downloads/`에 올린 수집 프로그램 정보(#399). 계약 API가 아니라 정적 파일이다. */
export interface ProgramRelease {
    version: string;
    file: string;
    sha256: string;
    bytes: number | null;
}

export const parseLinkCode = (data: unknown) => checkV9Shape<LinkCode>('V9EquipmentLinkCode', data);
export const parseLinkReceipt = (data: unknown) =>
    checkV9Shape<LinkReceipt>('V9EquipmentLinkReceipt', data);

const SAFE_FILE = /^[A-Za-z0-9._-]+\.exe$/;
const SHA256 = /^[0-9a-f]{64}$/;

/** 내려받기 정보. 형식이 다르면 null(링크를 만들지 않는다) */
export function parseRelease(data: unknown): ProgramRelease | null {
    if (!data || typeof data !== 'object') return null;
    const r = data as Record<string, unknown>;
    if (typeof r.version !== 'string' || typeof r.file !== 'string' || typeof r.sha256 !== 'string')
        return null;
    if (!SAFE_FILE.test(r.file) || !SHA256.test(r.sha256)) return null;
    return {
        version: r.version,
        file: r.file,
        sha256: r.sha256,
        bytes: typeof r.bytes === 'number' && r.bytes > 0 ? r.bytes : null,
    };
}
