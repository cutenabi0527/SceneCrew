import { useEffect, useState } from 'react';
import { CameraLinkCard } from '../camera-link/CameraLinkCard';
import type { CameraLinkDesk } from '../camera-link/client';
import type { MyCameraLink } from '../camera-link/contract';
import { Chip } from '../../shared/ui/atoms';
import { toLocalInput } from '../plan-workflow/time';
import { Field } from '../plan-workflow/ui';
import type { Result } from '../response/client';
import { resultText } from '../response/ui';
import {
    formatPoints,
    parsePoints,
    type CoverageInput,
    type MinimumCrewInput,
    type SafetyCommands,
} from './commands';
import type { SafetyReader } from './client';
import {
    CONNECTION_LABEL,
    DEMO_CAMERA_SOURCE,
    bindingProblem,
    noFirstFrame,
    type Camera,
    type SafetyMap,
} from './contract';
import { RoiDrawer } from './RoiDrawer';

// 안전 지도·카메라 설정(총괄, #309 T3). 좌표는 배치도 이미지 기준 0~1 정규화 값으로 "x,y; x,y; x,y"처럼 적는다.
// 응답을 잃으면 같은 내용으로만 다시 보내고, 결과 불명·충돌·거절이면 다시 조회해 확인하게 한다.

function useRun(commands: SafetyCommands, onChanged: () => void) {
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [retry, setRetry] = useState<string | null>(null);
    const run = async <T,>(key: string, work: () => Promise<Result<T>>, done: string) => {
        if (busy) return false;
        setBusy(true);
        setMessage('');
        try {
            const result = await work();
            if (result.kind === 'done') {
                setRetry(null);
                setMessage(done);
                onChanged();
                return true;
            }
            setRetry(result.kind === 'retry' ? key : null);
            if (result.kind !== 'retry') onChanged();
            setMessage(resultText(result, '설정 저장'));
            return false;
        } catch (error) {
            setMessage(
                error instanceof Error ? error.message.replace(/^.*?: /, '') : '보내지 못했습니다.',
            );
            return false;
        } finally {
            setBusy(false);
        }
    };
    const fresh = (key: string) => {
        if (retry === key) setRetry(null);
        commands.forget(key);
    };
    return { busy, message, retry, run, fresh };
}

type ZoneDraft = {
    points: string;
    area: string;
    rule: string;
    // 최소 인원 기준. group이 비면 기준 없음
    crewGroup: string;
    crewCount: string;
    crewStart: string;
    crewEnd: string;
};

type CrewRule = { workGroupId: string; minimumCount: number; startAt: string; endAt: string };

/** 입력한 최소 인원 기준. 그룹을 고르지 않으면 null, 값이 맞지 않으면 'invalid' */
function readCrew(d: ZoneDraft): MinimumCrewInput | null | 'invalid' {
    if (!d.crewGroup) return null;
    const count = Number(d.crewCount);
    const start = Date.parse(d.crewStart);
    const end = Date.parse(d.crewEnd);
    if (!d.crewCount.trim() || !Number.isInteger(count) || count < 0) return 'invalid';
    if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) return 'invalid';
    return {
        workGroupId: d.crewGroup,
        minimumCount: count,
        startAt: new Date(start).toISOString(),
        endAt: new Date(end).toISOString(),
    };
}

export function MapSettings({
    commands,
    map,
    zones,
    groups,
    eventRevision,
    onChanged,
}: {
    commands: SafetyCommands;
    map: SafetyMap;
    zones: { id: string; name: string }[];
    groups: { id: string; name: string }[];
    eventRevision: string;
    onChanged: () => void;
}) {
    const [width, setWidth] = useState(String(map.layout?.width ?? 1600));
    const [height, setHeight] = useState(String(map.layout?.height ?? 900));
    const [note, setNote] = useState('');
    const [drafts, setDrafts] = useState<Record<string, ZoneDraft>>(() =>
        Object.fromEntries(
            zones.map((z) => {
                const poly = map.layout?.zonePolygons.find((p) => p.zoneId === z.id);
                const profile = map.profiles.find((p) => p.zoneId === z.id);
                const rule = profile?.riskRule as { condition?: string } | null | undefined;
                const crew = profile?.minimumCrewRule as CrewRule | null | undefined;
                return [
                    z.id,
                    {
                        points: poly ? formatPoints(poly.points) : '',
                        area:
                            profile?.effectiveAreaM2 != null ? String(profile.effectiveAreaM2) : '',
                        rule: rule?.condition ?? '',
                        crewGroup: crew?.workGroupId ?? '',
                        crewCount: crew ? String(crew.minimumCount) : '',
                        crewStart: crew ? toLocalInput(crew.startAt) : '',
                        crewEnd: crew ? toLocalInput(crew.endAt) : '',
                    },
                ];
            }),
        ),
    );
    const { busy, message, retry, run, fresh } = useRun(commands, onChanged);
    const set = (zoneId: string, field: keyof ZoneDraft) => (v: string) => {
        fresh('map');
        setDrafts((prev) => ({ ...prev, [zoneId]: { ...prev[zoneId], [field]: v } }));
    };
    const drawn = zones.filter((z) => drafts[z.id]?.points.trim());
    const invalid = drawn.filter((z) => !parsePoints(drafts[z.id].points));
    const badCrew = drawn.filter((z) => readCrew(drafts[z.id]) === 'invalid');

    const save = () =>
        run(
            'map',
            () =>
                commands.saveMap(
                    map,
                    {
                        width: Number(width),
                        height: Number(height),
                        note,
                        zones: drawn.map((z) => {
                            const d = drafts[z.id];
                            return {
                                zoneId: z.id,
                                points: parsePoints(d.points) ?? [],
                                effectiveAreaM2: d.area.trim() ? Number(d.area) : null,
                                riskCondition: d.rule.trim() || null,
                                minimumCrew: readCrew(d) as MinimumCrewInput | null,
                            };
                        }),
                    },
                    eventRevision,
                ),
            '안전 지도 설정을 저장했습니다.',
        );

    return (
        <section aria-label="안전 지도 설정" className="card stack">
            <h2 className="h3">안전 지도 설정</h2>
            <p className="xs">
                배치도 이미지 기준으로 구역 꼭짓점을 0~1 좌표로 적습니다(예: 0.1,0.1; 0.5,0.1;
                0.5,0.5). 비운 구역은 지도에 넣지 않습니다. 면적/위험 기준이 있어야 밀도와 기준
                대비를 표시합니다. 최소 인원 기준은 지원 인력을 빼도 그 구역에 남겨야 할 직무 그룹
                인원과 적용 시간입니다.
            </p>
            <div className="row">
                <Field
                    label="배치도 가로"
                    value={width}
                    change={(v) => (fresh('map'), setWidth(v))}
                />
                <Field
                    label="배치도 세로"
                    value={height}
                    change={(v) => (fresh('map'), setHeight(v))}
                />
            </div>
            {zones.map((z) => (
                <fieldset key={z.id} className="stack" aria-label={`${z.name} 설정`}>
                    <legend className="small">{z.name}</legend>
                    <Field
                        label={`${z.name} 꼭짓점`}
                        value={drafts[z.id].points}
                        change={set(z.id, 'points')}
                    />
                    <Field
                        label={`${z.name} 유효 면적(㎡)`}
                        value={drafts[z.id].area}
                        change={set(z.id, 'area')}
                    />
                    <Field
                        label={`${z.name} 위험 기준`}
                        value={drafts[z.id].rule}
                        change={set(z.id, 'rule')}
                    />
                    <label className="small">
                        {z.name} 최소 인원 그룹{' '}
                        <select
                            aria-label={`${z.name} 최소 인원 그룹`}
                            value={drafts[z.id].crewGroup}
                            onChange={(e) => set(z.id, 'crewGroup')(e.target.value)}
                        >
                            <option value="">기준 없음</option>
                            {groups.map((g) => (
                                <option key={g.id} value={g.id}>
                                    {g.name}
                                </option>
                            ))}
                        </select>
                    </label>
                    {drafts[z.id].crewGroup && (
                        <div className="row">
                            <Field
                                label={`${z.name} 최소 인원(명)`}
                                value={drafts[z.id].crewCount}
                                change={set(z.id, 'crewCount')}
                            />
                            <label className="small">
                                적용 시작{' '}
                                <input
                                    type="datetime-local"
                                    aria-label={`${z.name} 최소 인원 적용 시작`}
                                    value={drafts[z.id].crewStart}
                                    onChange={(e) => set(z.id, 'crewStart')(e.target.value)}
                                />
                            </label>
                            <label className="small">
                                적용 끝{' '}
                                <input
                                    type="datetime-local"
                                    aria-label={`${z.name} 최소 인원 적용 끝`}
                                    value={drafts[z.id].crewEnd}
                                    onChange={(e) => set(z.id, 'crewEnd')(e.target.value)}
                                />
                            </label>
                        </div>
                    )}
                </fieldset>
            ))}
            <Field
                label="확인 메모(선택)"
                value={note}
                change={(v) => (fresh('map'), setNote(v))}
            />
            {!!invalid.length && (
                <p role="alert">
                    꼭짓점을 확인해 주세요: {invalid.map((z) => z.name).join(', ')} (세 점 이상, 0~1
                    범위)
                </p>
            )}
            {!!badCrew.length && (
                <p role="alert">
                    최소 인원 기준을 확인해 주세요: {badCrew.map((z) => z.name).join(', ')} (0 이상
                    정수, 끝 시각은 시작보다 뒤)
                </p>
            )}
            <button
                className="btn primary"
                type="button"
                disabled={busy || !drawn.length || !!invalid.length || !!badCrew.length}
                onClick={() => void save()}
            >
                {retry === 'map' ? '같은 내용으로 다시 보내기' : '안전 지도 설정 저장'}
            </button>
            {message && <p role="status">{message}</p>}
        </section>
    );
}

export function CameraSettings({
    commands,
    map,
    cameras,
    zones,
    eventRevision,
    onChanged,
    reader,
    tasks,
    memberName,
    desk,
}: {
    commands: SafetyCommands;
    map: SafetyMap;
    cameras: Camera[];
    zones: { id: string; name: string }[];
    eventRevision: string;
    onChanged: () => void;
    /** 있으면 관측 구역을 현재 화면 위에 그린다(#397) */
    reader?: SafetyReader;
    /** 연결 담당 업무로 고를 수 있는 업무. 없으면 고르는 칸을 숨긴다 */
    tasks?: { id: string; title: string }[];
    /** 구성원 id → 이름(연결한 사람 표시) */
    memberName?: (memberId: string) => string;
    /** 있으면 총괄도 자기 노트북 카메라로 연결·넘겨받는다(#396 부품) */
    desk?: CameraLinkDesk;
}) {
    const mapped = zones.filter((z) => map.profiles.some((p) => p.zoneId === z.id));
    const [name, setName] = useState('');
    const [binding, setBinding] = useState('');
    // 새 카메라의 연결 담당 업무: AUTO(키를 빼서 서버가 카메라 연결 업무 자동 지정)·NONE(null)·업무 id
    const [linkTask, setLinkTask] = useState(AUTO_TASK);
    // 웹 연결 카메라는 담당자가 연결한 뒤 현재 화면 위에 그린다. 지금 좌표로 정할 때만 켠다
    const [withCoverage, setWithCoverage] = useState(false);
    const links = useMyLinks(desk, cameras);
    const bindingError = bindingProblem(binding);
    const [rows, setRows] = useState<CoverageRow[]>(() => [newRow(mapped[0]?.id ?? '')]);
    const [renames, setRenames] = useState<Record<string, string>>({});
    const [editing, setEditing] = useState<string | null>(null);
    const { busy, message, retry, run, fresh } = useRun(commands, onChanged);
    // 구역 프로필이 없을 때 연 양식은 구역을 비운 채 시작한다. 지도를 저장해 구역이 생기면 첫 구역으로 채운다.
    const filled = rows.map((r) => (r.zoneId || !mapped[0] ? r : { ...r, zoneId: mapped[0].id }));
    const coverage = readRows(filled);
    const zoneName = (id: string) => zones.find((z) => z.id === id)?.name ?? '알 수 없는 구역';

    const canCover = !!map.layout && mapped.length > 0;
    const create = async () => {
        if (withCoverage && !coverage) return;
        const ok = await run(
            'camera:create',
            () =>
                commands.createCamera(
                    map,
                    {
                        name,
                        sourceBindingRef: binding,
                        enabled: true,
                        coverage: withCoverage && coverage ? coverage : [],
                        linkTaskId:
                            linkTask === AUTO_TASK
                                ? undefined
                                : linkTask === NO_TASK
                                  ? null
                                  : linkTask,
                    },
                    eventRevision,
                ),
            `${name.trim()} 카메라를 등록했습니다.`,
        );
        if (ok) {
            setName('');
            setBinding('');
            setLinkTask(AUTO_TASK);
            setWithCoverage(false);
            setRows([newRow(mapped[0]?.id ?? '')]);
        }
    };

    return (
        <section aria-label="카메라 설정" className="card stack">
            <h2 className="h3">카메라 설정</h2>
            <ul aria-label="카메라 관리" className="stack">
                {cameras.map((c) => {
                    const key = `camera:${c.id}`;
                    return (
                        <li key={c.id} className="stack">
                            <div className="row">
                                <Field
                                    label={`${c.name} 이름`}
                                    value={renames[c.id] ?? c.name}
                                    change={(v) => {
                                        fresh(key);
                                        setRenames((prev) => ({ ...prev, [c.id]: v }));
                                    }}
                                />
                                <button
                                    className="btn"
                                    type="button"
                                    disabled={busy || (renames[c.id] ?? c.name) === c.name}
                                    onClick={() =>
                                        void run(
                                            key,
                                            () => commands.updateCamera(c, { name: renames[c.id] }),
                                            '카메라 이름을 바꿨습니다.',
                                        )
                                    }
                                >
                                    이름 저장
                                </button>
                                <button
                                    className="btn"
                                    type="button"
                                    disabled={busy}
                                    onClick={() =>
                                        void run(
                                            key,
                                            () => commands.updateCamera(c, { enabled: !c.enabled }),
                                            c.enabled
                                                ? '카메라를 사용하지 않도록 바꿨습니다.'
                                                : '카메라를 다시 사용합니다.',
                                        )
                                    }
                                >
                                    {retry === key
                                        ? '같은 내용으로 다시 보내기'
                                        : c.enabled
                                          ? '사용 중지'
                                          : '다시 사용'}
                                </button>
                                <button
                                    className="btn"
                                    type="button"
                                    aria-expanded={editing === c.id}
                                    disabled={!map.layout || !mapped.length}
                                    onClick={() => setEditing((v) => (v === c.id ? null : c.id))}
                                >
                                    관측 구역 바꾸기
                                </button>
                            </div>
                            <CameraStatus camera={c} memberName={memberName} />
                            <p className="xs">
                                관측 구역:{' '}
                                {c.coverage.map((v) => zoneName(v.zoneId)).join(', ') ||
                                    '없음(보정 전이라 인원을 세지 않습니다)'}
                            </p>
                            {tasks && (
                                <label className="small">
                                    연결 담당 업무{' '}
                                    <select
                                        aria-label={`${c.name} 연결 담당 업무`}
                                        value={c.linkTaskId ?? ''}
                                        disabled={busy}
                                        onChange={(e) =>
                                            void run(
                                                `${key}:task`,
                                                () =>
                                                    commands.updateCamera(c, {
                                                        linkTaskId: e.target.value || null,
                                                    }),
                                                '연결 담당 업무를 바꿨습니다.',
                                            )
                                        }
                                    >
                                        <option value="">정하지 않음</option>
                                        {taskOptions(tasks, c.linkTaskId).map((t) => (
                                            <option key={t.id} value={t.id}>
                                                {t.title}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            )}
                            {desk && links.get(c.id) && (
                                <details>
                                    <summary className="small">이 컴퓨터 카메라로 연결</summary>
                                    <CameraLinkCard
                                        camera={links.get(c.id)!}
                                        desk={desk}
                                        onChanged={onChanged}
                                    />
                                </details>
                            )}
                            {editing === c.id && (
                                <CoverageEditor
                                    camera={c}
                                    reader={reader}
                                    zones={mapped}
                                    busy={busy}
                                    retry={retry === `${key}:coverage`}
                                    fresh={() => fresh(`${key}:coverage`)}
                                    save={(items) =>
                                        void run(
                                            `${key}:coverage`,
                                            () =>
                                                commands.updateCamera(c, { coverage: items }, map),
                                            '관측 구역을 바꿨습니다. 보정을 다시 확인해 주세요.',
                                        ).then((ok) => ok && setEditing(null))
                                    }
                                />
                            )}
                        </li>
                    );
                })}
            </ul>
            <b>카메라 등록</b>
            <p className="xs">
                웹 연결 카메라는 이름과 연결 담당 업무만 정해 칸을 만듭니다(등록 안됨). 담당자가
                노트북 WEB의 내 연결에서 연결하면 사진이 들어오고, 그 현재 화면 위에 관측 구역을
                그려 보정합니다.
            </p>
            {
                <>
                    <Field
                        label="카메라 이름"
                        value={name}
                        change={(v) => {
                            fresh('camera:create');
                            setName(v);
                        }}
                    />
                    {tasks && (
                        <label className="small">
                            연결 담당 업무{' '}
                            <select
                                aria-label="새 카메라 연결 담당 업무"
                                value={linkTask}
                                onChange={(e) => {
                                    fresh('camera:create');
                                    setLinkTask(e.target.value);
                                }}
                            >
                                <option value={AUTO_TASK}>자동(카메라 연결 업무)</option>
                                <option value={NO_TASK}>지정 안 함</option>
                                {taskOptions(tasks, null).map((t) => (
                                    <option key={t.id} value={t.id}>
                                        {t.title}
                                    </option>
                                ))}
                            </select>
                        </label>
                    )}
                    <Field
                        label="연결 식별자(수집 프로그램 설정값)"
                        value={binding}
                        change={(v) => {
                            fresh('camera:create');
                            setBinding(v);
                        }}
                    />
                    <p className="xs">
                        예비 수집 프로그램을 쓸 때만 적습니다(웹 연결 카메라는 비워 둡니다). 현장
                        노트북의 웹캠 프로그램(webcam.py) 설정 SCENECREW_CAMERA_SOURCE와 같은 값을
                        넣어야 사진이 이 카메라로 들어옵니다. 시연 노트북은{' '}
                        <code>{DEMO_CAMERA_SOURCE}</code>입니다.
                    </p>
                    {binding.trim() && bindingError && (
                        <p className="xs" role="alert">
                            {bindingError}
                        </p>
                    )}
                    <label className="check small">
                        <input
                            type="checkbox"
                            checked={withCoverage}
                            disabled={!canCover}
                            onChange={(e) => {
                                fresh('camera:create');
                                setWithCoverage(e.target.checked);
                            }}
                        />
                        지금 관측 구역을 좌표로 정하기
                        {!canCover && ' (안전 지도 설정(배치도/구역)을 먼저 저장해야 합니다)'}
                    </label>
                    {withCoverage && canCover && (
                        <CoverageRows
                            label="새 카메라"
                            rows={filled}
                            zones={mapped}
                            change={(next) => {
                                fresh('camera:create');
                                setRows(next);
                            }}
                        />
                    )}
                    <button
                        className="btn primary"
                        type="button"
                        disabled={
                            busy ||
                            (withCoverage && !coverage) ||
                            !name.trim() ||
                            !!(binding.trim() && bindingError)
                        }
                        onClick={() => void create()}
                    >
                        {retry === 'camera:create' ? '같은 내용으로 다시 보내기' : '카메라 등록'}
                    </button>
                </>
            }
            {message && <p role="status">{message}</p>}
        </section>
    );
}

/** 카메라 칸의 상태: 연결 상태(첫 사진 없음)·입력 종류·연결한 사람·보정 필요(#397, V9-D60) */
function CameraStatus({
    camera,
    memberName,
}: {
    camera: Camera;
    memberName?: (memberId: string) => string;
}) {
    const stalled = noFirstFrame(camera);
    return (
        <span className="row" role="group" aria-label={`${camera.name} 상태`}>
            <Chip kind={stalled ? 'bad' : camera.connectionState === 'CONNECTED' ? 'ok' : ''}>
                {stalled ? '첫 사진 없음' : CONNECTION_LABEL[camera.connectionState]}
            </Chip>
            {camera.inputKind && (
                <Chip>{camera.inputKind === 'BROWSER' ? '웹 연결' : '예비 수집 프로그램'}</Chip>
            )}
            {camera.linkMemberId && (
                <Chip>연결 {memberName?.(camera.linkMemberId) ?? '담당자'}</Chip>
            )}
            {camera.coverage.length === 0 && <Chip kind="warn">보정 필요</Chip>}
        </span>
    );
}

const AUTO_TASK = 'auto';
const NO_TASK = 'none';

/** 고를 수 있는 업무. 지금 지정된 업무가 목록에 없으면(취소 등) 그 칸도 남긴다 */
function taskOptions(tasks: { id: string; title: string }[], current: string | null) {
    if (!current || tasks.some((t) => t.id === current)) return tasks;
    return [...tasks, { id: current, title: '목록에 없는 업무' }];
}

/** 총괄의 내 연결 목록(W1, 모든 카메라). 카메라 목록이 바뀔 때마다 다시 읽는다 */
function useMyLinks(desk: CameraLinkDesk | undefined, cameras: Camera[]) {
    const [links, setLinks] = useState<Map<string, MyCameraLink>>(() => new Map());
    const stamp = cameras.map((c) => `${c.id}:${c.revision}:${c.connectionState}`).join('|');
    useEffect(() => {
        if (!desk) return;
        const ac = new AbortController();
        desk.myLinks(ac.signal).then(
            (r) => setLinks(new Map(r.cameras.map((c) => [c.id, c]))),
            () => undefined,
        );
        return () => ac.abort();
    }, [desk, stamp]);
    return links;
}

type CoverageRow = {
    key: string;
    zoneId: string;
    scope: 'FULL' | 'PARTIAL';
    roi: string;
    area: string;
    note: string;
};

const FULL_FRAME = '0,0; 1,0; 1,1; 0,1';
let rowSeq = 0;
const newRow = (zoneId: string, roi = FULL_FRAME): CoverageRow => ({
    key: `row-${++rowSeq}`,
    zoneId,
    scope: 'FULL',
    roi,
    area: '',
    note: '',
});

/** 입력한 관측 구역 목록. 빈 구역·잘못된 좌표·면적·같은 구역 중복이 있으면 null */
function readRows(rows: CoverageRow[]): CoverageInput[] | null {
    if (!rows.length) return null;
    const ids = rows.map((r) => r.zoneId);
    if (ids.some((id) => !id) || new Set(ids).size !== ids.length) return null;
    const items: CoverageInput[] = [];
    for (const r of rows) {
        const roi = parsePoints(r.roi);
        const area = r.area.trim() ? Number(r.area) : null;
        if (!roi || (area !== null && !(area > 0))) return null;
        items.push({ zoneId: r.zoneId, scope: r.scope, roi, effectiveAreaM2: area, note: r.note });
    }
    return items;
}

/**
 * 카메라 한 대의 관측 구역 목록 입력. 한 화면에 여러 구역이 보이면 구역마다 화면 속 영역을 따로 적는다
 * (예: 왼쪽 절반 "0,0; 0.5,0; 0.5,1; 0,1"은 1구역, 오른쪽 절반은 2구역).
 */
function CoverageRows({
    label,
    rows,
    zones,
    change,
    drawing,
}: {
    label: string;
    rows: CoverageRow[];
    zones: { id: string; name: string }[];
    change: (rows: CoverageRow[]) => void;
    /** 현재 화면 위에 그릴 관측 구역 고르기 */
    drawing?: { active: string | null; choose: (key: string) => void };
}) {
    const update = (key: string, patch: Partial<CoverageRow>) =>
        change(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
    const used = new Set(rows.map((r) => r.zoneId));
    const free = zones.filter((z) => !used.has(z.id));
    const duplicated = rows.length !== used.size;
    return (
        <div className="stack">
            <p className="xs">
                한 카메라가 여러 구역을 비추면 관측 구역을 추가해 구역마다 화면 속 영역을 나눠
                적습니다. 영역이 겹치면 같은 사람을 두 번 셀 수 있으니 겹치지 않게 적어 주세요.
            </p>
            {rows.map((r, i) => {
                const n = `${label} 관측 구역 ${i + 1}`;
                const roiOk = !!parsePoints(r.roi);
                const areaOk = !r.area.trim() || Number(r.area) > 0;
                return (
                    <fieldset key={r.key} className="stack" aria-label={n}>
                        <legend className="small">{n}</legend>
                        {drawing && (
                            <label className="check small">
                                <input
                                    type="radio"
                                    name={`${label}-draw`}
                                    checked={drawing.active === r.key}
                                    onChange={() => drawing.choose(r.key)}
                                />
                                현재 화면에 이 구역 그리기
                            </label>
                        )}
                        <label className="small">
                            구역{' '}
                            <select
                                aria-label={`${n} 구역`}
                                value={r.zoneId}
                                onChange={(e) => update(r.key, { zoneId: e.target.value })}
                            >
                                {zones.map((z) => (
                                    <option key={z.id} value={z.id}>
                                        {z.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label className="small">
                            관측 범위{' '}
                            <select
                                aria-label={`${n} 관측 범위`}
                                value={r.scope}
                                onChange={(e) =>
                                    update(r.key, { scope: e.target.value as 'FULL' | 'PARTIAL' })
                                }
                            >
                                <option value="FULL">구역 전체</option>
                                <option value="PARTIAL">구역 일부</option>
                            </select>
                        </label>
                        <Field
                            label={`${n} 화면 속 영역(0~1 좌표)`}
                            value={r.roi}
                            change={(v) => update(r.key, { roi: v })}
                        />
                        <Field
                            label={`${n} 면적(㎡, 선택)`}
                            value={r.area}
                            change={(v) => update(r.key, { area: v })}
                        />
                        <Field
                            label={`${n} 보정 메모(선택)`}
                            value={r.note}
                            change={(v) => update(r.key, { note: v })}
                        />
                        {!roiOk && (
                            <p role="alert">화면 속 영역은 세 점 이상, 0~1 범위로 적어 주세요.</p>
                        )}
                        {!areaOk && <p role="alert">면적은 0보다 커야 합니다.</p>}
                        {rows.length > 1 && (
                            <button
                                className="btn"
                                type="button"
                                onClick={() => change(rows.filter((x) => x.key !== r.key))}
                            >
                                이 관측 구역 빼기
                            </button>
                        )}
                    </fieldset>
                );
            })}
            {duplicated && <p role="alert">한 카메라에서 같은 구역을 두 번 정할 수 없습니다.</p>}
            <button
                className="btn"
                type="button"
                disabled={!free.length}
                onClick={() => change([...rows, newRow(free[0]?.id ?? '')])}
            >
                관측 구역 추가
            </button>
        </div>
    );
}

/** 카메라 한 대의 관측 구역 바꾸기. 바꾸면 서버가 보정 확인을 다시 요구한다. */
function CoverageEditor({
    camera,
    reader,
    zones,
    busy,
    retry,
    fresh,
    save,
}: {
    camera: Camera;
    reader?: SafetyReader;
    zones: { id: string; name: string }[];
    busy: boolean;
    retry: boolean;
    fresh: () => void;
    save: (coverage: CoverageInput[]) => void;
}) {
    const [rows, setRows] = useState<CoverageRow[]>(() => {
        const known = camera.coverage.filter((c) => zones.some((z) => z.id === c.zoneId));
        if (!known.length) return [newRow(zones[0]?.id ?? '')];
        // 기존 영역 면적은 조회 응답에 없어 빈 칸으로 시작한다
        return known.map((c) => ({
            ...newRow(c.zoneId, c.roi?.length ? formatPoints(c.roi) : FULL_FRAME),
            scope: c.scope === 'PARTIAL' ? 'PARTIAL' : 'FULL',
        }));
    });
    const items = readRows(rows);
    const [active, setActive] = useState<string | null>(null);
    const current = rows.find((r) => r.key === active) ?? rows[0];
    const change = (next: CoverageRow[]) => {
        fresh();
        setRows(next);
    };
    const zoneName = (id: string) => zones.find((z) => z.id === id)?.name ?? '구역';
    return (
        <div className="stack" role="group" aria-label={`${camera.name} 관측 구역 바꾸기`}>
            {reader && (
                <RoiDrawer
                    reader={reader}
                    cameraId={camera.id}
                    cameraName={camera.name}
                    shapes={rows.map((r) => ({
                        key: r.key,
                        label: zoneName(r.zoneId),
                        points: parsePoints(r.roi),
                        active: r.key === current?.key,
                    }))}
                    onDraw={(points) =>
                        current &&
                        change(
                            rows.map((r) =>
                                r.key === current.key ? { ...r, roi: formatPoints(points) } : r,
                            ),
                        )
                    }
                />
            )}
            <CoverageRows
                label={camera.name}
                rows={rows}
                zones={zones}
                change={change}
                drawing={reader ? { active: current?.key ?? null, choose: setActive } : undefined}
            />
            <button
                className="btn primary"
                type="button"
                disabled={busy || !items}
                onClick={() => items && save(items)}
            >
                {retry ? '같은 내용으로 다시 보내기' : '관측 구역 저장'}
            </button>
        </div>
    );
}
