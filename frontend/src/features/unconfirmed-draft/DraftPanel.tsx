import { useEffect, useMemo, useRef, useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import type { Workflow } from '../plan-workflow/client';
import { displayTime } from '../plan-workflow/time';
import { Field } from '../plan-workflow/ui';
import { resultText } from '../response/ui';
import { DraftDesk } from './client';
import type { SheetAsset } from './auto-fill';
import {
    assetKey,
    danglingAssetRefs,
    emptyEquipment,
    emptyVenue,
    type EquipmentDraft,
    type UnconfirmedDraft,
    type VenueDraft,
} from './contract';
import {
    addZone,
    defaultLayout,
    documentPage,
    layoutGaps,
    moveZone,
    remapLayout,
    removeZone,
    type DraftLayout,
    type ZoneRef,
} from './layout';
import { useBackground } from './useBackground';
import { VenueLayoutEditor } from './VenueLayoutEditor';

// 계획 화면(총괄·DRAFT)의 공유 미확정 장비·장소 초안(Q3-A).
// - 확인되지 않은 후보라 실제 장비·배치로 보이지 않게 "미확정"으로 표시한다.
// - 장비 후보 추가(이름·모델)와 항목 빼기만 한다. 연결·예약은 장비 설정 화면에서 붙인다.
// - 공연장 평면도(#372, V9-D45): AI 결과6이 읽은 도형이나 구역 이름의 기본 배치로 그리고, 총괄이
//   끌어서 고친 뒤 이 초안으로 저장한다. 발행 때 BE가 안전 지도로 옮긴다(UUID 구역만).
// - 명시 저장만 한다(자동 저장 없음). 충돌하면 다른 사람이 저장한 최신본을 다시 읽고 내 편집은 버린다.

type Kind = 'assets' | 'connections' | 'requirements' | 'reservations' | 'dependencies';

const KIND_LABEL: Record<Kind, string> = {
    assets: '장비 후보',
    connections: '연결 후보',
    requirements: '필요 조건 후보',
    reservations: '예약 후보',
    dependencies: '선후 관계 후보',
};

function itemText(kind: Kind, item: Record<string, unknown>) {
    switch (kind) {
        case 'assets':
            return `${String(item.name)}${item.model ? ` (${String(item.model)})` : ''}`;
        case 'connections':
            return `${String(item.fromAssetId)}:${String(item.fromPort)} → ${String(item.toAssetId)}:${String(item.toPort)}`;
        case 'reservations':
            return `${displayTime(String(item.startAt))} ~ ${displayTime(String(item.endAt))} / 장비 ${(item.assetIds as unknown[]).length}대`;
        default:
            return `${KIND_LABEL[kind]} 1건`;
    }
}

export function UnconfirmedDraftPanel({
    client,
    zones = [],
    incoming = null,
    appliedJobId = null,
    mappings = [],
    autoLayout = null,
    autoAssets = null,
    hideVenue = false,
}: {
    client: Workflow;
    /** 지금 계획 초안의 구역(편집 중이면 편집 값, tempId 포함) */
    zones?: ZoneRef[];
    /** 검토로 가져온 계획 후보의 결과6 평면도(저장 전) */
    incoming?: { layout: DraftLayout; jobId: string } | null;
    /** 마지막 계획 초안 저장(028)의 tempId → id 대응 */
    mappings?: { tempId: string; id: string }[];
    /** 방금 저장(028)한 계획 초안을 가져온 계획 생성 작업. 미확정 초안의 출처로 남긴다(#395) */
    appliedJobId?: string | null;
    /** 상세 정보 업로드에서 맞춘 평면도 구역 상자로 만든 배치도. 바뀌면 미확정 초안에 자동으로 저장한다 */
    autoLayout?: DraftLayout | null;
    /** 상세 정보 업로드의 장비 목록에서 읽은 장비 후보. 없는 후보를 더해 자동으로 저장한다 */
    autoAssets?: SheetAsset[] | null;
    /** 평면도 초안 화면을 숨긴다(등록 화면의 미확정 초안 탭: 평면도는 상세 정보 업로드에서 올린 것을 쓴다) */
    hideVenue?: boolean;
}) {
    const desk = useMemo(() => new DraftDesk(client.session, client.scope), [client]);
    const [saved, setSaved] = useState<UnconfirmedDraft | null>(null);
    const [equipment, setEquipment] = useState<EquipmentDraft>(emptyEquipment());
    const [venue, setVenue] = useState<VenueDraft>(emptyVenue());
    const [sourceJobId, setSourceJobId] = useState<string | null>(null);
    const [dirty, setDirty] = useState(false);
    const [loaded, setLoaded] = useState(false);
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);
    const [name, setName] = useState('');
    const [model, setModel] = useState('');
    const [reload, setReload] = useState(0);

    useEffect(() => {
        let active = true;
        desk.read().then(
            (draft) => {
                if (!active) return;
                setSaved(draft);
                setEquipment(draft?.equipmentDraft ?? emptyEquipment());
                setVenue(draft?.venueDraft ?? emptyVenue());
                setSourceJobId(draft?.sourceJobId ?? null);
                setDirty(false);
                setLoaded(true);
            },
            (e: unknown) => {
                if (!active) return;
                setLoaded(false);
                setMessage(
                    classifyFailure(e).kind === 'cancelled'
                        ? ''
                        : '미확정 초안을 불러오지 못했습니다. 다시 조회해주세요.',
                );
            },
        );
        return () => {
            active = false;
        };
    }, [desk, reload]);

    // 계획 초안을 저장해 임시 구역(tmp-)이 새 id를 받으면 평면도도 그 id로 맞춘다(BE는 tmp- 도형을 옮기지 않는다).
    // 저장한 값과 다르면 저장할 변경으로 본다.
    const stored = venue.layout as DraftLayout | null;
    const layout = stored && remapLayout(stored, mappings);
    const remapped = layout !== stored;
    const changed = dirty || remapped;
    const setLayout = (next: DraftLayout | null) => {
        setVenue((prev) => ({ ...prev, layout: next as unknown as VenueDraft['layout'] }));
        setDirty(true);
    };
    const gaps = layout ? layoutGaps(layout, zones) : null;
    // V9-D62: PDF 평면도에서 읽은 도형이면 그 쪽 그림을 배경으로 받는다(작성 중 원본 권한, 못 받으면 배경 없음)
    const page = documentPage(layout);
    const background = useBackground(
        page && `${page.documentId}#${page.page}`,
        page && ((signal) => desk.pageImage(page.documentId, page.page, signal)),
    );

    const remove = (kind: Kind, index: number) => {
        setEquipment((prev) => ({ ...prev, [kind]: prev[kind].filter((_, i) => i !== index) }));
        setDirty(true);
    };
    const add = () => {
        if (!name.trim()) return;
        setEquipment((prev) => ({
            ...prev,
            assets: [
                ...prev.assets,
                {
                    tempId: `tmp-${crypto.randomUUID().slice(0, 8)}`,
                    name: name.trim(),
                    model: model.trim() || null,
                    componentAssetIds: [],
                    ports: [],
                },
            ],
        }));
        setName('');
        setModel('');
        setDirty(true);
    };

    const dangling = danglingAssetRefs(equipment);
    const save = async () => {
        if (busy || !changed || dangling.length) return;
        setBusy(true);
        setMessage('');
        try {
            const result = await desk.save({
                expectedRevision: saved?.revision ?? '0',
                equipmentDraft: equipment,
                venueDraft: { ...venue, layout: layout as unknown as VenueDraft['layout'] },
                sourceJobId:
                    appliedJobId && saved?.sourceJobId !== appliedJobId
                        ? appliedJobId
                        : sourceJobId,
            });
            if (result.kind === 'done')
                setMessage(`미확정 초안을 저장했습니다(revision ${result.data.revision}).`);
            else
                setMessage(
                    result.kind === 'conflict'
                        ? '다른 사람이 먼저 초안을 저장했습니다. 최신 초안을 다시 불러왔으니 확인한 뒤 다시 고쳐주세요.'
                        : resultText(result, '초안 저장'),
                );
            // 저장 결과·충돌 모두 서버의 현재 초안으로 다시 맞춘다
            if (result.kind !== 'retry') setReload((n) => n + 1);
        } finally {
            setBusy(false);
        }
    };

    // 계획 생성 결과로 계획 초안을 저장하면 그 작업을 미확정 초안의 출처(sourceJobId)로 남긴다(#395).
    // 공개(029) 때 BE가 이 출처 결과의 카메라·장비로 칸을 만든다. 고치던 초안이 있으면 그 저장에 함께 싣는다.
    const recorded = useRef<string | null>(null);
    useEffect(() => {
        if (!appliedJobId || !loaded || changed || recorded.current === appliedJobId) return;
        if (saved?.sourceJobId === appliedJobId) return;
        recorded.current = appliedJobId;
        // 평면도를 아직 그리지 않았으면 같은 생성 결과가 읽은 평면도(결과6)를 함께 저장한다(#401 운영 확인).
        // 공개(029) 때 이 평면도가 안전 지도와 PDF 배경 그림이 된다. 임시 구역 id는 방금 저장의 새 id로 맞춘다.
        const ai =
            !layout && incoming?.jobId === appliedJobId
                ? remapLayout(incoming.layout, mappings)
                : null;
        void desk
            .save({
                expectedRevision: saved?.revision ?? '0',
                equipmentDraft: saved?.equipmentDraft ?? emptyEquipment(),
                venueDraft: {
                    ...venue,
                    layout: (ai ?? layout) as unknown as VenueDraft['layout'],
                },
                sourceJobId: appliedJobId,
            })
            .then(
                (result) => {
                    if (result.kind === 'done')
                        setMessage(
                            ai
                                ? '계획 생성 결과를 이 초안의 출처로 남기고, AI가 읽은 평면도를 함께 저장했습니다. 공개 전에 평면도를 확인하세요.'
                                : '계획 생성 결과를 이 초안의 출처로 남겼습니다.',
                        );
                    if (result.kind !== 'retry') setReload((n) => n + 1);
                },
                () => undefined,
            );
    }, [appliedJobId, loaded, saved, changed, desk, venue, layout, incoming, mappings]);

    // 상세 정보 업로드에서 올린 평면도·장비 목록을 미확정 초안에 자동으로 저장한다(미확정 초안 탭).
    // 평면도는 그 구역 상자로 배치도를 바꾸고, 장비 목록은 아직 없는 후보만 더한다. 고치던 편집이 있으면
    // 그 편집을 덮지 않도록 기다렸다가, 편집을 저장하거나 버린 뒤 다시 맞춘다. 같은 자료로는 한 번만 보낸다.
    const autoKey = JSON.stringify({
        layout: autoLayout?.zonePolygons ?? null,
        assets: autoAssets,
    });
    const autoSent = useRef<string | null>(null);
    useEffect(() => {
        if (!loaded || busy || changed || autoSent.current === autoKey) return;
        if (!autoLayout && !autoAssets?.length) return;
        const known = new Set(equipment.assets.map((a) => `${a.name}\u0000${a.model ?? ''}`));
        const added = (autoAssets ?? []).filter(
            (a) => !known.has(`${a.name}\u0000${a.model ?? ''}`),
        );
        const sameLayout =
            !autoLayout ||
            JSON.stringify(layout?.zonePolygons ?? null) ===
                JSON.stringify(autoLayout.zonePolygons);
        autoSent.current = autoKey;
        if (!added.length && sameLayout) return;
        const nextEquipment = {
            ...equipment,
            assets: [
                ...equipment.assets,
                ...added.map((a) => ({
                    tempId: `tmp-${crypto.randomUUID().slice(0, 8)}`,
                    name: a.name,
                    model: a.model,
                    componentAssetIds: [],
                    ports: [],
                })),
            ],
        };
        const nextLayout = sameLayout ? layout : autoLayout;
        void desk
            .save({
                expectedRevision: saved?.revision ?? '0',
                equipmentDraft: nextEquipment,
                venueDraft: { ...venue, layout: nextLayout as unknown as VenueDraft['layout'] },
                sourceJobId,
            })
            .then(
                (result) => {
                    if (result.kind === 'done')
                        setMessage(
                            [
                                !sameLayout && '상세 정보 업로드의 평면도를 반영했습니다.',
                                added.length > 0 &&
                                    `장비 목록에서 장비 후보 ${added.length}개를 더했습니다.`,
                            ]
                                .filter(Boolean)
                                .join(' '),
                        );
                    else autoSent.current = null;
                    if (result.kind !== 'retry') setReload((n) => n + 1);
                },
                () => {
                    autoSent.current = null;
                },
            );
    }, [
        autoKey,
        autoLayout,
        autoAssets,
        loaded,
        busy,
        changed,
        equipment,
        layout,
        venue,
        saved,
        sourceJobId,
        desk,
    ]);

    const kinds: Kind[] = ['assets', 'connections', 'requirements', 'reservations', 'dependencies'];
    return (
        <section aria-label="미확정 장비/장소 초안" className="card stack">
            <h2>미확정 장비/장소 초안</h2>
            {saved && (
                <p className="xs">
                    저장 revision {saved.revision} / {displayTime(saved.savedAt)} 저장
                </p>
            )}
            {message && <p role="status">{message}</p>}
            {remapped && (
                <p className="small" role="status">
                    계획 초안 저장으로 새 구역 id가 생겨 평면도를 맞췄습니다. 미확정 초안을 저장해야
                    발행 때 안전 지도로 옮겨집니다.
                </p>
            )}
            {loaded && (
                <>
                    {kinds.map((kind) =>
                        equipment[kind].length ? (
                            <ul key={kind} aria-label={KIND_LABEL[kind]} className="small">
                                {equipment[kind].map((item, i) => (
                                    <li
                                        key={
                                            kind === 'assets'
                                                ? assetKey(item as EquipmentDraft['assets'][number])
                                                : i
                                        }
                                    >
                                        {KIND_LABEL[kind]}: {itemText(kind, item)}{' '}
                                        <button
                                            className="btn"
                                            type="button"
                                            disabled={busy}
                                            onClick={() => remove(kind, i)}
                                        >
                                            빼기
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        ) : null,
                    )}
                    {!hideVenue && (
                        <section aria-label="공연장 평면도 초안" className="stack">
                            <h3>공연장 평면도 초안</h3>
                            <p className="xs">
                                배치도{' '}
                                {layout ? `있음(구역 ${layout.zonePolygons.length}개)` : '없음'} /
                                구역 프로필 {venue.profiles.length}개
                            </p>
                            <div className="row">
                                {incoming && (
                                    <button
                                        className="btn"
                                        type="button"
                                        disabled={busy}
                                        onClick={() => {
                                            setLayout(incoming.layout);
                                            setSourceJobId(incoming.jobId);
                                            setMessage(
                                                'AI가 자료의 평면도에서 읽은 구역으로 그렸습니다. 확인하고 고친 뒤 저장해주세요.',
                                            );
                                        }}
                                    >
                                        AI가 읽은 평면도로 그리기
                                    </button>
                                )}
                                <button
                                    className="btn"
                                    type="button"
                                    disabled={busy || !zones.length}
                                    onClick={() => {
                                        setLayout(defaultLayout(zones));
                                        setMessage(
                                            '구역 이름으로 기본 배치를 그렸습니다. 무대 앞에 나란히 놓았으니 실제 위치에 맞게 고쳐주세요.',
                                        );
                                    }}
                                >
                                    {layout
                                        ? '구역 이름으로 다시 그리기'
                                        : '구역 이름으로 기본 배치 그리기'}
                                </button>
                                {layout && (
                                    <button
                                        className="btn"
                                        type="button"
                                        disabled={busy}
                                        onClick={() => setLayout(null)}
                                    >
                                        평면도 비우기
                                    </button>
                                )}
                            </div>
                            {layout && (
                                <VenueLayoutEditor
                                    layout={layout}
                                    zones={zones}
                                    disabled={busy}
                                    background={background}
                                    change={(zoneId, rect) =>
                                        setLayout(moveZone(layout, zoneId, rect))
                                    }
                                />
                            )}
                            {layout && gaps && gaps.missing.length > 0 && (
                                <div role="alert" className="small">
                                    지금 계획에 없는 구역의 도형이 {gaps.missing.length}개 있습니다.
                                    발행 때 옮겨지지 않습니다.{' '}
                                    <button
                                        className="btn"
                                        type="button"
                                        disabled={busy}
                                        onClick={() =>
                                            setLayout(
                                                gaps.missing.reduce(
                                                    (acc, p) => removeZone(acc, p.zoneId),
                                                    layout,
                                                ),
                                            )
                                        }
                                    >
                                        없는 구역 도형 빼기
                                    </button>
                                </div>
                            )}
                            {gaps && gaps.temporary.length > 0 && gaps.missing.length === 0 && (
                                <p className="small">
                                    아직 계획 초안에 저장되지 않은 구역이 있습니다. 계획 초안을
                                    저장하면 평면도를 새 구역 id로 맞추니, 그 뒤 미확정 초안을
                                    저장해주세요.
                                </p>
                            )}
                            {layout && gaps && gaps.undrawn.length > 0 && (
                                <div className="row small">
                                    도형이 없는 구역:{' '}
                                    {gaps.undrawn.map((z) => (
                                        <button
                                            key={z.key}
                                            className="btn"
                                            type="button"
                                            disabled={busy}
                                            onClick={() => setLayout(addZone(layout, z.key))}
                                        >
                                            {z.name} 도형 추가
                                        </button>
                                    ))}
                                </div>
                            )}
                        </section>
                    )}
                    <div className="row">
                        <Field label="장비 후보 이름" value={name} change={setName} />
                        <Field label="모델(선택)" value={model} change={setModel} />
                        <button className="btn" type="button" disabled={!name.trim()} onClick={add}>
                            장비 후보 추가
                        </button>
                    </div>
                    {!!dangling.length && (
                        <p role="alert">
                            뺀 장비 후보를 아직 다른 후보가 가리키고 있습니다({dangling.join(', ')}
                            ). 연결/예약 후보도 함께 빼주세요.
                        </p>
                    )}
                    <div className="row">
                        <button
                            className="btn primary"
                            type="button"
                            disabled={busy || !changed || !!dangling.length}
                            onClick={() => void save()}
                        >
                            미확정 초안 저장
                        </button>
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={() => setReload((n) => n + 1)}
                        >
                            {dirty ? '편집 버리고 다시 불러오기' : '초안 다시 불러오기'}
                        </button>
                    </div>
                </>
            )}
            {!loaded && (
                <button className="btn" type="button" onClick={() => setReload((n) => n + 1)}>
                    초안 다시 불러오기
                </button>
            )}
        </section>
    );
}
