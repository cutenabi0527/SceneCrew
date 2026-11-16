import { ContractViolationError } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';

// 행사 공유 미확정 장비·장소 초안(Q3-A, V9-D17). 행사당 1개, 총괄이 DRAFT에서 명시 저장한다.
// 확인된 실제 장비·배치와 섞지 않는다. 저장은 전체 교체이고 기대 revision으로 충돌을 막는다(처음은 0).

type Json = Record<string, unknown>;

export interface DraftAsset extends Json {
    name: string;
    model: string | null;
    componentAssetIds: string[];
    ports: unknown[];
    id?: string;
    tempId?: string;
}

export interface EquipmentDraft {
    assets: DraftAsset[];
    connections: (Json & {
        fromAssetId: string;
        toAssetId: string;
        fromPort: string;
        toPort: string;
    })[];
    requirements: Json[];
    reservations: (Json & { assetIds: string[]; startAt: string; endAt: string })[];
    dependencies: Json[];
}

export interface VenueDraft {
    layout: (Json & { zonePolygons: unknown[] }) | null;
    profiles: Json[];
}

export interface UnconfirmedDraft {
    id: string;
    eventId: string;
    revision: string;
    sourceJobId: string | null;
    equipmentDraft: EquipmentDraft;
    venueDraft: VenueDraft;
    savedByMemberId: string;
    savedAt: string;
}

export interface Receipt {
    id: string;
    revision: string;
    eventRevision: string;
}

export const emptyEquipment = (): EquipmentDraft => ({
    assets: [],
    connections: [],
    requirements: [],
    reservations: [],
    dependencies: [],
});
export const emptyVenue = (): VenueDraft => ({ layout: null, profiles: [] });

export function parseUnconfirmedDraft(data: unknown, eventId: string): UnconfirmedDraft {
    const draft = parseV9<UnconfirmedDraft>('FinalUnconfirmedDraft', data);
    if (draft.eventId !== eventId)
        throw new ContractViolationError('draft.eventId', '현재 공연이어야 합니다.');
    return draft;
}

export function parseReceipt(data: unknown): Receipt {
    return parseV9<Receipt>('FinalReceipt', data);
}

/** 초안 안에서 장비를 가리키는 키(id 또는 tempId) */
export const assetKey = (a: DraftAsset) => a.id ?? a.tempId ?? '';

/** 남은 장비가 가리키지 않는 장비 참조. 저장 전에 FE가 막는다(서버도 INVALID_REFERENCE로 거절한다). */
export function danglingAssetRefs(draft: EquipmentDraft): string[] {
    const keys = new Set(draft.assets.map(assetKey));
    const refs = [
        ...draft.assets.flatMap((a) => a.componentAssetIds),
        ...draft.connections.flatMap((c) => [c.fromAssetId, c.toAssetId]),
        ...draft.reservations.flatMap((r) => r.assetIds),
    ];
    // 실제 장비(UUID)는 확인된 장비일 수 있어 초안 밖을 가리켜도 된다. 임시 키(tmp-)만 검사한다.
    return [...new Set(refs.filter((r) => r.startsWith('tmp-') && !keys.has(r)))];
}
