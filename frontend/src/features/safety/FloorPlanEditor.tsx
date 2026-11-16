import { useId, useRef, useState, type PointerEvent } from 'react';
import { useData, useEvent } from '../../domain/hooks';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { FloorPlan } from '../../domain/types';
import { Dialog } from '../../shared/ui/Dialog';
import { Note } from '../../shared/ui/atoms';
import { ImageCrop } from './ImageCrop';
import { readFloorPlan } from './floor-plan-image';
import { useFloorView, useSharedFloorPlan, writeSharedFloorPlan } from './shared-floor-plan';
import { nameBoxes, type Rect, type ZoneDraft } from './zone-detect';
import styles from './floorplan.module.css';

const MIN = 3;
const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);
const r1 = (v: number) => Math.round(v * 10) / 10;

type Drag = { key: string; mode: 'move' | 'size'; px: number; py: number; start: Rect };

/**
 * 평면도 편집(W05·총괄): PNG·JPG 평면도를 올리면 구역을 자동으로 찾아 상자와 이름을 붙이고,
 * 사용자가 상자를 끌어 옮기거나(가운데) 오른쪽 아래 모서리로 크기를 바꾸고 이름을 고친 뒤 저장한다.
 * 기존 구역과 겹치는 상자는 그 구역(면적·기준·카메라 연결)을 이어받고, 나머지는 새 구역이 된다.
 */
export function FloorPlanEditor({
    onClose,
    frameRatio,
    initialFile,
    eventId,
}: {
    onClose: () => void;
    /** 안전 화면 평면도 틀의 가로/세로. "평면도 비율"로 자를 때 쓴다 */
    frameRatio: number;
    /** 계획 수정에서 고른 파일: 열자마자 자르기 단계로 들어간다 */
    initialFile?: File;
    /** 실서버 공연 id(계획 수정에서 열 때). 없으면 지금 공연 */
    eventId?: string;
}) {
    const d = useData();
    const ev = useEvent();
    const { live } = useDemoState();
    const dispatch = useDispatch();
    const view = useFloorView(eventId);
    const [plan, setPlan] = useState<FloorPlan | undefined>(view.plan);
    const [planChanged, setPlanChanged] = useState(false);
    // 실서버는 지난번에 이 브라우저에 저장한 상자(계획에 아직 없는 새 구역 포함)부터 보인다
    const shared = useSharedFloorPlan(live ? (eventId ?? ev.id) : undefined);
    const [zones, setZones] = useState<ZoneDraft[]>(() =>
        shared
            ? shared.zones.map((z, i) => ({
                  key: z.id ?? `s-${i}`,
                  id: z.id,
                  name: z.name ?? '',
                  rect: z.rect,
              }))
            : view.zones.map((z) => ({ key: z.id, id: z.id, name: z.name, rect: z.rect })),
    );
    const [sel, setSel] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    // 고른 파일은 먼저 자르기 단계를 거친다
    const [cropping, setCropping] = useState<File | null>(initialFile ?? null);
    const stage = useRef<HTMLDivElement>(null);
    const drag = useRef<Drag | null>(null);
    const existing = view.zones.map((z) => ({ id: z.id, name: z.name, rect: z.rect }));

    const upload = async (file: File, content: Rect) => {
        setBusy(true);
        setMessage('');
        try {
            const { plan: next, boxes } = await readFloorPlan(file, content);
            setPlan(next);
            setPlanChanged(true);
            if (boxes.length) setZones(nameBoxes(boxes, existing));
            setSel(null);
        } catch (e) {
            setMessage(e instanceof Error ? e.message : '이미지를 처리하지 못했습니다.');
        } finally {
            setBusy(false);
        }
    };

    const onDown = (e: PointerEvent<HTMLElement>, key: string, mode: Drag['mode']) => {
        e.stopPropagation();
        const z = zones.find((x) => x.key === key)!;
        setSel(key);
        drag.current = { key, mode, px: e.clientX, py: e.clientY, start: z.rect };
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    };
    const onMove = (e: PointerEvent<HTMLElement>) => {
        const g = drag.current;
        const box = stage.current?.getBoundingClientRect();
        if (!g || !box) return;
        const dx = ((e.clientX - g.px) / box.width) * 100;
        const dy = ((e.clientY - g.py) / box.height) * 100;
        const [x, y, w, h] = g.start;
        const rect: Rect =
            g.mode === 'move'
                ? [r1(clamp(x + dx, 0, 100 - w)), r1(clamp(y + dy, 0, 100 - h)), w, h]
                : [x, y, r1(clamp(w + dx, MIN, 100 - x)), r1(clamp(h + dy, MIN, 100 - y))];
        setZones((all) => all.map((z) => (z.key === g.key ? { ...z, rect } : z)));
    };
    const onUp = () => {
        drag.current = null;
    };

    const names = zones.map((z) => z.name.trim());
    // 같은 이름이면 처음 생긴 구역은 두고 그 뒤의 구역만 빨간 테두리로 표시한다
    const firstOf = new Map<string, string>();
    const dupKeys = new Set<string>();
    zones.forEach((z, i) => {
        const n = names[i];
        if (!n) return;
        if (firstOf.has(n)) dupKeys.add(z.key);
        else firstOf.set(n, z.key);
    });
    const dupNames = [
        ...new Set(zones.filter((z) => dupKeys.has(z.key)).map((z) => z.name.trim())),
    ];
    const problem = names.some((n) => !n)
        ? '이름이 빈 구역이 있습니다.'
        : dupKeys.size
          ? '같은 이름의 구역이 있습니다.'
          : '';
    const fileInputId = useId();
    const removed = d.safetyZones.filter((z) => !zones.some((x) => x.id === z.id));

    return (
        <Dialog
            wide
            className={styles.dialog}
            title="평면도 편집"
            actions={
                <>
                    <input
                        id={fileInputId}
                        type="file"
                        className="sr-only"
                        aria-label="평면도 이미지"
                        accept="image/png,image/jpeg,.png,.jpg,.jpeg"
                        disabled={busy || !!cropping}
                        onChange={(e) => {
                            const f = e.target.files?.[0];
                            if (f) {
                                setMessage('');
                                setCropping(f);
                            }
                            e.target.value = '';
                        }}
                    />
                    <label
                        htmlFor={fileInputId}
                        className={styles.reupload}
                        data-disabled={busy || !!cropping || undefined}
                    >
                        {plan ? '평면도 다시 올리기' : '평면도 올리기'}
                    </label>
                </>
            }
            onClose={onClose}
        >
            {cropping ? (
                <ImageCrop
                    file={cropping}
                    frameRatio={frameRatio}
                    onCancel={() => {
                        // 계획 수정에서 파일을 고르자마자 연 창은 자르기를 취소하면 바로 닫는다
                        if (initialFile && !planChanged) onClose();
                        else setCropping(null);
                    }}
                    onDone={(f, content) => {
                        setCropping(null);
                        void upload(f, content);
                    }}
                />
            ) : (
                <div className={styles.layout}>
                    <div
                        ref={stage}
                        className={styles.stage}
                        style={{
                            aspectRatio: plan ? `${plan.width} / ${plan.height}` : '4 / 3',
                            // 창 높이가 고정이라 세로로 긴 평면도는 높이에 맞춰 줄인다
                            width: `min(100%, calc(var(--stage-max-h) * ${plan ? plan.width / plan.height : 4 / 3}))`,
                            backgroundImage: plan ? `url(${plan.src})` : undefined,
                        }}
                        onPointerMove={onMove}
                        onPointerUp={onUp}
                        onPointerCancel={onUp}
                        onPointerDown={() => setSel(null)}
                        aria-label="평면도"
                    >
                        {zones.map((z) => (
                            <div
                                key={z.key}
                                className={styles.box}
                                aria-selected={z.key === sel}
                                data-dup={dupKeys.has(z.key) || undefined}
                                style={{
                                    left: `${z.rect[0]}%`,
                                    top: `${z.rect[1]}%`,
                                    width: `${z.rect[2]}%`,
                                    height: `${z.rect[3]}%`,
                                }}
                                onPointerDown={(e) => onDown(e, z.key, 'move')}
                            >
                                <span className={styles.boxName}>{z.name || '이름 없음'}</span>
                                <i
                                    className={styles.handle}
                                    aria-label={`${z.name} 크기 바꾸기`}
                                    onPointerDown={(e) => onDown(e, z.key, 'size')}
                                />
                            </div>
                        ))}
                    </div>
                    <aside className={styles.side}>
                        {busy && <span className="xs muted">이미지를 읽고 구역을 찾는 중…</span>}
                        {message && <Note kind="warn">{message}</Note>}
                        <div className="row between">
                            <b className="small">{zones.length}구역</b>
                            <button
                                type="button"
                                className={styles.addZone}
                                aria-label="상자 추가"
                                onClick={() => {
                                    const key = `n${Date.now()}`;
                                    setZones((all) => [
                                        ...all,
                                        {
                                            key,
                                            id: null,
                                            name: `구역 ${all.length + 1}`,
                                            rect: [35, 40, 30, 20],
                                        },
                                    ]);
                                    setSel(key);
                                }}
                            >
                                + 추가
                            </button>
                        </div>
                        <ul className={styles.list}>
                            {zones.map((z) => (
                                <li
                                    key={z.key}
                                    aria-selected={z.key === sel}
                                    data-dup={dupKeys.has(z.key) || undefined}
                                >
                                    <input
                                        className="inp"
                                        aria-label="구역 이름"
                                        value={z.name}
                                        maxLength={30}
                                        onFocus={() => setSel(z.key)}
                                        onChange={(e) =>
                                            setZones((all) =>
                                                all.map((x) =>
                                                    x.key === z.key
                                                        ? { ...x, name: e.target.value }
                                                        : x,
                                                ),
                                            )
                                        }
                                    />
                                    <button
                                        type="button"
                                        className={styles.removeZone}
                                        aria-label={`${z.name} 지우기`}
                                        onClick={() =>
                                            setZones((all) => all.filter((x) => x.key !== z.key))
                                        }
                                    >
                                        ✕
                                    </button>
                                </li>
                            ))}
                        </ul>
                        {!live && removed.length > 0 && (
                            <Note kind="warn">
                                저장하면 지워지는 기존 구역: {removed.map((z) => z.name).join(', ')}
                                . 그 구역의 면적/기준/카메라 연결도 함께 빠집니다.
                            </Note>
                        )}
                        {problem && !dupKeys.size && <Note kind="warn">{problem}</Note>}
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                            <button type="button" className="btn ghost" onClick={onClose}>
                                취소
                            </button>
                            <button
                                type="button"
                                className="btn primary"
                                disabled={!!problem || busy}
                                onClick={() => {
                                    if (live) {
                                        if (!plan) {
                                            setMessage('평면도 그림을 먼저 올려주세요.');
                                            return;
                                        }
                                        const ok = writeSharedFloorPlan(eventId ?? ev.id, {
                                            plan,
                                            zones: zones.map((z) => ({
                                                id: z.id,
                                                name: z.name.trim(),
                                                rect: z.rect,
                                            })),
                                        });
                                        if (!ok) {
                                            setMessage(
                                                '이 브라우저에 평면도를 저장하지 못했습니다. 더 작은 그림으로 다시 올려주세요.',
                                            );
                                            return;
                                        }
                                    } else
                                        dispatch({
                                            type: 'setFloorPlan',
                                            plan: planChanged ? plan : undefined,
                                            zones: zones.map((z) => ({
                                                id: z.id,
                                                name: z.name,
                                                rect: z.rect,
                                            })),
                                        });
                                    onClose();
                                }}
                            >
                                저장
                            </button>
                        </div>
                    </aside>
                </div>
            )}
            {dupNames.length > 0 && !cropping && (
                <div className={styles.toast} role="alert">
                    <b>이름이 같은 구역이 있습니다</b>
                    <span>
                        {dupNames.join(', ')}: 빨간 테두리 구역의 이름을 바꿔야 저장할 수 있습니다.
                    </span>
                </div>
            )}
        </Dialog>
    );
}
