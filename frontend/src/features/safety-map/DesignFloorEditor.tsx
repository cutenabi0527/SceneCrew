import { useState } from 'react';
import { useData, useEvent } from '../../domain/hooks';
import { useDispatch } from '../../domain/store-context';
import { Dialog } from '../../shared/ui/Dialog';
import { Note } from '../../shared/ui/atoms';
import { moveZone, pointsOf, type DraftLayout } from '../unconfirmed-draft/layout';
import { VenueLayoutEditor } from '../unconfirmed-draft/VenueLayoutEditor';
import { forgetSharedZones } from '../safety/shared-floor-plan';
import { useLiveFloorPlan } from '../../live/safety-link';

// 실서버 W05 평면도 편집(#372). 계획에 있는 구역의 도형 위치·크기만 고친다.
// 평면도 그림 올리기·자동 인식·구역 추가·이름 변경·삭제는 하지 않는다(구역은 공연 계획에서 바꾼다).
// V9-D62: 발행 때 저장한 PDF 평면도 그림이 있으면 바탕에 깔고 고친다.
// 저장은 setFloorPlan 액션으로 보내고, 실서버 핸들러가 안전 지도 배치도(PATCH safety-map)로 저장한다.
// 작성(DRAFT) 중인 공연은 계획 입력의 공연장 평면도 초안에서 고친다(발행 때 안전 지도로 옮겨진다).

const toRatio = (v: number) => Math.round(v * 10) / 1000;

export function DesignFloorEditor({
    frameRatio,
    onClose,
}: {
    /** W05 평면도 틀의 가로/세로. 화면과 같은 비율로 보이게 한다 */
    frameRatio: number;
    onClose: () => void;
}) {
    const d = useData();
    const ev = useEvent();
    const dispatch = useDispatch();
    const plan = useLiveFloorPlan();
    const draft = ev.state === 'DRAFT';
    // 배경 그림이 있으면 그 그림 크기(쪽 전체)를 좌표 기준으로 쓴다. 보일 영역은 편집기가 crop으로 자른다
    const [layout, setLayout] = useState<DraftLayout>(() => ({
        coordinateSystem: 'normalized-image',
        width: plan?.width ?? 1000,
        height: plan?.height ?? Math.round(1000 / (frameRatio > 0 ? frameRatio : 4 / 3)),
        zonePolygons: d.safetyZones.map((z) => ({
            zoneId: z.id,
            points: pointsOf({
                x: toRatio(z.rect[0]),
                y: toRatio(z.rect[1]),
                width: toRatio(z.rect[2]),
                height: toRatio(z.rect[3]),
            }),
        })),
        source: {
            sourceKind: 'MANUAL_CONFIRMATION',
            sourceRef: 'w05:floor-editor',
            observedAt: null,
        },
    }));
    const [changed, setChanged] = useState(false);
    const zones = d.safetyZones.map((z) => ({ key: z.id, name: z.name }));
    const pct = (v: number) => Math.round(v * 1000) / 10;

    const save = () => {
        dispatch({
            type: 'setFloorPlan',
            zones: layout.zonePolygons.map((p) => {
                const xs = p.points.map((pt) => pt.x);
                const ys = p.points.map((pt) => pt.y);
                const x = Math.min(...xs);
                const y = Math.min(...ys);
                return {
                    id: p.zoneId,
                    name: zones.find((z) => z.key === p.zoneId)?.name ?? '',
                    rect: [pct(x), pct(y), pct(Math.max(...xs) - x), pct(Math.max(...ys) - y)],
                };
            }),
        });
        // 서버에 구역 도형을 저장하면 계획 수정에서 맞춘 상자 위치 대신 서버 도형을 따른다
        forgetSharedZones(ev.id);
        onClose();
    };

    return (
        <Dialog
            wide
            title="평면도 편집"
            sub="구역 도형을 끌어 옮기고 오른쪽 아래 모서리로 크기를 바꿉니다. 구역을 더하거나 이름을 바꾸려면 공연 계획에서 고칩니다."
            onClose={onClose}
        >
            <div className="stack">
                {draft ? (
                    <Note kind="warn">
                        작성 중인 공연은 계획 입력의 공연장 평면도 초안에서 고칩니다. 공개하면 안전
                        지도로 옮겨집니다.
                    </Note>
                ) : !zones.length ? (
                    <Note kind="warn">
                        공연 계획에 구역이 없습니다. 공연 계획에서 구역을 먼저 더하세요.
                    </Note>
                ) : (
                    <VenueLayoutEditor
                        layout={layout}
                        zones={zones}
                        disabled={ev.state === 'ENDED'}
                        background={plan ? { src: plan.src, crop: plan.crop ?? null } : null}
                        change={(zoneId, rect) => {
                            setLayout((prev) => moveZone(prev, zoneId, rect));
                            setChanged(true);
                        }}
                    />
                )}
                <div className="row">
                    <button
                        type="button"
                        className="btn primary"
                        disabled={draft || !changed || ev.state === 'ENDED'}
                        onClick={save}
                    >
                        평면도 저장
                    </button>
                    <button type="button" className="btn" onClick={onClose}>
                        닫기
                    </button>
                </div>
            </div>
        </Dialog>
    );
}
