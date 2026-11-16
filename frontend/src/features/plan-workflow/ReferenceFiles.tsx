import { useEffect, useId, useState, type ReactNode } from 'react';
import { useData } from '../../domain/hooks';
import { useDemoState, useDispatch } from '../../domain/store-context';
import { Dialog } from '../../shared/ui/Dialog';
import { Icon } from '../../shared/ui/Icon';
import { FloorPlanEditor } from '../safety/FloorPlanEditor';
import { useFloorView, FLOOR_RATIO, writeSharedFloorPlan } from '../safety/shared-floor-plan';
import { readSheetFile, SheetReadError, type Sheet } from './sheet-file';
import styles from './workspace.module.css';

/**
 * 문서 표 아래 두 칸: 평면도와 장비 목록(엑셀) 미리보기. 점선 상자에 올리면 같은 자리·크기에 그대로 띄운다.
 * 평면도는 안전 탭과 같은 '평면도 편집' 창(자르기·구역 자동 인식)을 거쳐 안전 탭 평면도로 저장한다.
 * 장비 목록은 서버로 보내지 않고 이 탭(sessionStorage)에만 둔다.
 */

const sheetKey = (key: string) => `${key}:equipmentSheet`;

function readStored<T>(key: string, ok: (v: unknown) => v is T): T | null {
    try {
        const raw = sessionStorage.getItem(key);
        const value: unknown = raw ? JSON.parse(raw) : null;
        return ok(value) ? value : null;
    } catch {
        return null;
    }
}

function writeStored(key: string, value: unknown) {
    try {
        if (value === null) sessionStorage.removeItem(key);
        else sessionStorage.setItem(key, JSON.stringify(value));
    } catch {
        // 저장 공간이 모자라면 새로고침 뒤에는 보이지 않는다(지금 화면에는 그대로 보인다)
    }
}

const isSheet = (v: unknown): v is Sheet =>
    !!v && typeof (v as Sheet).name === 'string' && Array.isArray((v as Sheet).rows);

/** 0 → A, 25 → Z, 26 → AA */
function columnName(i: number) {
    let n = i + 1;
    let s = '';
    while (n > 0) {
        const r = (n - 1) % 26;
        s = String.fromCharCode(65 + r) + s;
        n = Math.floor((n - 1) / 26);
    }
    return s;
}

export function ReferenceFiles({
    storageKey,
    eventId,
    locked,
    onSheet,
    zones,
}: {
    storageKey: string;
    /** 계획의 구역 이름(평면도 구역과 맞춘 것). 평면도 오른쪽 표에서 읽기만 한다 */
    zones: string[];
    /** 실서버 공연 id. 데모는 비운다 */
    eventId?: string;
    locked?: boolean;
    /** 장비 목록(첫 시트)이 바뀌면 그 행을 알린다(미확정 초안의 장비 후보로 옮긴다). 지우면 null */
    onSheet?: (rows: string[][] | null) => void;
}) {
    const d = useData();
    const { live } = useDemoState();
    const dispatch = useDispatch();
    // 평면도는 안전 탭과 같은 곳을 읽고 쓴다
    const floor = useFloorView(eventId).plan;
    // 평면도 칸·자르기 '평면도 비율'은 5177×2748 비율이다
    const safetyRatio = FLOOR_RATIO;
    // 평면도 편집 창: file이 있으면 그 파일을 자르기부터, 없으면 지금 평면도의 구역 상자 편집부터 연다
    const [cropping, setCropping] = useState<{ file?: File; ratio: number } | null>(null);
    const [sheet, setSheet] = useState(() => readStored(sheetKey(storageKey), isSheet));
    // 올린(또는 이 탭에 남아 있던) 장비 목록을 부모에 알린다
    useEffect(() => {
        onSheet?.(sheet ? sheet.rows : null);
    }, [sheet, onSheet]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<{ title: string; message: string } | null>(null);

    const pickFloor = (file: File | undefined) => {
        if (!file) return;
        if (!/^image\/(png|jpeg)$/.test(file.type) && !/\.(png|jpe?g)$/i.test(file.name)) {
            setError({
                title: '평면도를 올리지 못했습니다',
                message: 'PNG/JPG 이미지만 올릴 수 있습니다.',
            });
            return;
        }
        setCropping({ file, ratio: safetyRatio });
    };

    const clearFloor = () => {
        if (live) {
            if (eventId) writeSharedFloorPlan(eventId, null);
        } else
            dispatch({
                type: 'setFloorPlan',
                plan: null,
                zones: d.safetyZones.map((z) => ({ id: z.id, name: z.name, rect: z.rect })),
            });
    };

    const pickSheet = async (file: File | undefined) => {
        if (!file || busy) return;
        setBusy(true);
        try {
            const next = await readSheetFile(file);
            if (!next.rows.length) throw new SheetReadError('시트에 읽을 수 있는 값이 없습니다.');
            setSheet(next);
            writeStored(sheetKey(storageKey), next);
        } catch (e) {
            setError({
                title: '장비 목록을 올리지 못했습니다',
                message:
                    e instanceof SheetReadError
                        ? e.message
                        : '엑셀 파일을 읽지 못했습니다. 파일이 손상되었는지 확인해주세요.',
            });
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className={styles.refGrid}>
            <RefColumn
                title="평면도"
                fileName={floor ? floor.name || '평면도' : undefined}
                accept=".png,.jpg,.jpeg,image/png,image/jpeg"
                inputLabel="평면도 그림"
                emptyTitle="+ 평면도 업로드"
                emptyHint="PNG/JPG, 15MB 이내. 안전 탭 평면도에 같이 적용됩니다"
                locked={locked || !!cropping}
                boxClass={styles.refFloor}
                replaceByClick
                onPick={pickFloor}
                onEdit={() => setCropping({ ratio: safetyRatio })}
                onClear={clearFloor}
            >
                {floor && (
                    // 빈 점선 상자와 같은 크기·비율(안전 탭 평면도 틀 비율). 그림은 잘리지 않게 안에 맞춘다
                    <div className={`${styles.refView} ${styles.refFloor}`}>
                        <img
                            className={styles.refImage}
                            src={floor.src}
                            alt={`평면도 ${floor.name}`}
                        />
                    </div>
                )}
            </RefColumn>
            <div className={styles.refCol}>
                {/* 평면도·장비와 같은 제목 줄. 평면도 제목 줄과 높이가 같아 두 상자의 위가 맞는다 */}
                <div className={styles.refHead}>
                    <span className={styles.refTitle}>구역</span>
                </div>
                {/* 테두리 없는 표. 머리 줄은 늘 보이고, 구역이 많으면 위아래로만 스크롤한다(긴 이름은 앞부분만) */}
                <div className={styles.zoneBox} tabIndex={0} role="region" aria-label="평면도 구역">
                    <table className={styles.zoneTable}>
                        <thead>
                            <tr>
                                <th scope="col">#</th>
                                <th scope="col">구역 이름</th>
                            </tr>
                        </thead>
                        <tbody>
                            {zones.map((name, i) => (
                                <tr key={i}>
                                    <td>{i + 1}</td>
                                    <td title={name}>{name}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>
            <RefColumn
                title="장비"
                fileName={sheet?.name}
                accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
                inputLabel="장비 목록 파일"
                emptyTitle={busy ? '장비 목록을 읽고 있습니다' : '+ 장비 목록 업로드'}
                emptyHint="XLSX/CSV, 첫 시트만 보입니다"
                locked={locked || busy}
                onPick={(f) => void pickSheet(f)}
                onClear={() => {
                    setSheet(null);
                    writeStored(sheetKey(storageKey), null);
                }}
            >
                {sheet && (
                    <div
                        className={`${styles.refView} ${styles.sheetScroll}`}
                        tabIndex={0}
                        role="region"
                        aria-label={`장비 목록 ${sheet.name}`}
                    >
                        <table className={styles.sheet}>
                            <thead>
                                <tr>
                                    <th className={styles.sheetCorner} aria-hidden="true" />
                                    {(sheet.rows[0] ?? []).map((_, i) => (
                                        <th key={i} scope="col">
                                            {columnName(i)}
                                        </th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {sheet.rows.map((row, r) => (
                                    <tr key={r}>
                                        <th scope="row">{r + 1}</th>
                                        {row.map((v, c) => (
                                            <td key={c}>{v}</td>
                                        ))}
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </RefColumn>
            {cropping && (
                <FloorPlanEditor
                    initialFile={cropping.file}
                    frameRatio={cropping.ratio}
                    eventId={eventId}
                    onClose={() => setCropping(null)}
                />
            )}
            {error && (
                <Dialog title={error.title} onClose={() => setError(null)}>
                    <div className="stack">
                        <p role="alert">{error.message}</p>
                        <div className={styles.actions}>
                            <button type="button" className="btn sm" onClick={() => setError(null)}>
                                확인
                            </button>
                        </div>
                    </div>
                </Dialog>
            )}
        </div>
    );
}

function RefColumn({
    title,
    fileName,
    accept,
    inputLabel,
    emptyTitle,
    emptyHint,
    locked,
    boxClass,
    replaceByClick,
    hideTitle,
    actionsInside,
    noHead,
    onPick,
    onEdit,
    onClear,
    children,
}: {
    title: string;
    fileName?: string;
    accept: string;
    inputLabel: string;
    emptyTitle: string;
    emptyHint: string;
    locked?: boolean;
    /** 빈 점선 상자에 덧붙이는 class */
    boxClass?: string;
    /** 올린 그림을 누르면 다른 파일로 바꾼다('바꾸기' 대신) */
    replaceByClick?: boolean;
    /** 제목 글자를 숨긴다(읽기 도구에는 남는다) */
    hideTitle?: boolean;
    /** 편집·지우기 버튼을 상자 안 오른쪽 위에 둔다 */
    actionsInside?: boolean;
    /** 제목 줄을 두지 않는다(파일 이름은 상자 안 왼쪽 위에 보인다) */
    noHead?: boolean;
    onPick: (file: File | undefined) => void;
    /** 올린 것을 고친다(평면도: 구역 상자 편집) */
    onEdit?: () => void;
    onClear: () => void;
    children: ReactNode;
}) {
    const inputId = useId();
    const [dragging, setDragging] = useState(false);
    const actions = (
        <>
            {onEdit && (
                <button
                    type="button"
                    className={styles.refEdit}
                    aria-label={`${title} 편집`}
                    title={`${title} 편집`}
                    disabled={locked}
                    onClick={onEdit}
                >
                    <Icon name="edit" />
                </button>
            )}
            <button
                type="button"
                className={styles.refRemove}
                aria-label={`${title} 지우기`}
                disabled={locked}
                onClick={onClear}
            >
                <Icon name="trash" />
            </button>
        </>
    );
    return (
        <div className={styles.refCol}>
            <div className={noHead ? 'sr-only' : styles.refHead}>
                <span className={hideTitle ? 'sr-only' : styles.refTitle}>{title}</span>
                {fileName && (
                    <>
                        <span className={styles.refName} title={fileName}>
                            {fileName}
                        </span>
                        {!replaceByClick && (
                            // 장비: 편집(아이콘)을 누르면 다른 파일로 바꾼다
                            <label
                                htmlFor={inputId}
                                className={styles.refEdit}
                                aria-label={`${title} 편집`}
                                title={`${title} 편집(다른 파일로 바꾸기)`}
                                data-disabled={locked || undefined}
                            >
                                <Icon name="edit" />
                            </label>
                        )}
                        {!actionsInside && actions}
                    </>
                )}
            </div>
            <input
                id={inputId}
                type="file"
                className="sr-only"
                aria-label={inputLabel}
                accept={accept}
                disabled={locked}
                onChange={(e) => {
                    const f = e.target.files?.[0];
                    e.target.value = '';
                    onPick(f);
                }}
            />
            {fileName && replaceByClick ? (
                <div className={styles.refBoxWrap}>
                    <label
                        htmlFor={inputId}
                        className={styles.refClick}
                        title="누르면 다른 파일로 바꿉니다"
                        data-disabled={locked || undefined}
                    >
                        {children}
                    </label>
                    {noHead && fileName && (
                        <span className={styles.refChip} title={fileName}>
                            {fileName}
                        </span>
                    )}
                    {actionsInside && <div className={styles.refOverlay}>{actions}</div>}
                </div>
            ) : fileName ? (
                children
            ) : (
                <label
                    htmlFor={inputId}
                    className={`${styles.dropzone} ${styles.refDrop} ${boxClass ?? ''}`}
                    data-dragging={dragging || undefined}
                    data-disabled={locked || undefined}
                    onDragOver={(e) => {
                        if (locked) return;
                        e.preventDefault();
                        setDragging(true);
                    }}
                    onDragLeave={() => setDragging(false)}
                    onDrop={(e) => {
                        e.preventDefault();
                        setDragging(false);
                        if (!locked) onPick(e.dataTransfer.files[0]);
                    }}
                >
                    <span className={styles.dropTitle}>{emptyTitle}</span>
                    <span className={styles.dropHint}>{emptyHint}</span>
                </label>
            )}
        </div>
    );
}
