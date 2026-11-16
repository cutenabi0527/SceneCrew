import type { IdentitySession } from '../features/identity/session';
import { equipmentCommands } from '../features/equipment/commands';
import { EquipmentReader } from '../features/equipment/live-client';
import type {
    LiveAsset,
    LiveConnection,
    LiveReservation,
} from '../features/equipment/live-contract';
import type { Plan, Task as ApiTask } from '../features/plan-workflow/contract';
import { NotificationDesk } from '../features/notifications/client';
import type { Notification } from '../features/notifications/contract';
import { responder } from '../features/response/client';
import type {
    Acknowledgment,
    ChangeSet,
    Issue as ApiIssue,
    RecoverySimulation,
} from '../features/response/contract';
import type { ParticipationScope } from '../shared/scope/scope';
import { classifyFailure } from '../shared/api/failure';
import type { EquipStep, EventData } from '../domain/types';
import { workflow } from '../features/plan-workflow/client';
import { parseChannelPage, type Channel } from '../features/live-chat/contract';
import { toRooms } from './convert-chat';
import {
    expandReleases,
    lookup,
    toChange,
    toEquipment,
    toIssues,
    type AnalysisJobState,
    toRecovery,
    toReservations,
} from './convert-ops';
import { markPendingAcks, toNotices } from './convert-notices';
import { toSafety, type LiveSafety } from './convert-safety';
import { SafetyReader } from '../features/safety-map/client';

// 보고·변경안·장비·복구 계산(#371)을 한 번에 읽는다. 권한이 없거나(팀원의 변경안·장비 등) 아직 없는 자료는
// 그 부분만 비우고 나머지 화면은 그대로 연다. 데모 값으로 채우지 않는다.

export interface OpsRaw {
    issues: ApiIssue[];
    changeSets: ChangeSet[];
    assets: LiveAsset[];
    reservations: LiveReservation[];
    connections: LiveConnection[];
    /** 이슈 id → 최근 완료된 복구 계산 */
    simulations: Map<string, RecoverySimulation>;
    /** 이슈 id → 이 화면이 접수한 보고 분석 작업의 상태(총괄만, #358) */
    analysisJobs: Map<string, AnalysisJobState>;
    /** 내 알림 첫 쪽(074, #373). 계정 단위라 다른 공연·지난 회차도 섞여 있다 */
    notifications?: Notification[];
    /** 내 변경 확인 대상(076, #373) */
    acks?: Acknowledgment[];
    /** 읽은 참여 범위. 알림을 지금 공연·지금 회차로 거른다 */
    me?: { eventId: string; memberId: string; participationRevision: string };
    /** 들어갈 수 있는 채팅 채널(072, #373) */
    channels?: Channel[];
    /** 안전 지도·카메라(총괄만, #372). 읽지 못하면 비우거나 직전 값을 둔다 */
    safety?: LiveSafety | null;
    /** 내 진행 중 업무의 단건 조회(051). 장비 지시가 여기에만 있다(#373) */
    taskDetails?: Map<string, ApiTask>;
    /** 이 화면에서 남긴 장비 수행 기록(업무 id → 단계 → 시각). 서버에 읽기 API가 없어 화면 실행 동안만 기억한다 */
    equipmentRecords?: ReadonlyMap<string, Partial<Record<EquipStep, string>>>;
}

/** 단건 조회할 내 업무 상한. 팀원 한 명의 진행 중 업무는 많지 않다 */
const DETAIL_LIMIT = 10;

const empty = <T>(): T[] => [];

/**
 * 알림·변경 확인·채널은 일시 오류(503·연결 끊김 등) 한 번에 화면에서 사라지지 않게 직전 값을 유지한다.
 * 권한이 없을 때(403·404)만 비운다(#373 리뷰).
 */
const keep =
    <T>(previous: readonly T[] | undefined) =>
    (error: unknown): T[] =>
        classifyFailure(error).kind === 'access-denied' ? [] : [...(previous ?? [])];

/** 알림 074는 계정 전체 목록이다. 다른 공연 알림에 밀리지 않게 몇 쪽까지 읽는다 */
const NOTICE_PAGES = 5;

async function pages<T>(
    read: (cursor?: string) => Promise<{ items: T[]; nextCursor: string | null }>,
    max: number,
) {
    const items: T[] = [];
    let cursor: string | undefined;
    for (let i = 0; i < max; i++) {
        const page = await read(cursor);
        items.push(...page.items);
        if (!page.nextCursor) break;
        cursor = page.nextCursor;
    }
    return items;
}

/** 장비 이상으로 분류된 열린 이슈 */
export const equipmentIssues = (issues: readonly ApiIssue[]) =>
    issues.filter(
        (i) =>
            i.status !== 'CLOSED' &&
            (i.situation?.primaryType === 'EQUIPMENT_ANOMALY' ||
                !!i.situation?.relatedTypes.includes('EQUIPMENT_ANOMALY')),
    );

export async function loadOps(
    session: IdentitySession,
    scope: ParticipationScope,
    chief: boolean,
    /** 장비 이상이 아니어도 이 화면에서 복구 계산을 돌린 이슈. 결과를 함께 읽는다. */
    recoveryIssues: ReadonlySet<string> = new Set(),
    /** 이 화면이 접수한 보고 분석 작업(이슈 id → 작업 id). 진행·실패 상태를 읽는다. */
    analysisJobIds: ReadonlyMap<string, string> = new Map(),
    extra: {
        tasks?: readonly ApiTask[];
        equipmentRecords?: ReadonlyMap<string, Partial<Record<EquipStep, string>>>;
        /** 같은 공연을 다시 읽을 때 직전 원본. 일시 오류 때 그 부분을 유지한다 */
        previous?: OpsRaw;
    } = {},
): Promise<OpsRaw> {
    const previous = extra.previous;
    const desk = responder(session, scope);
    const reader = new EquipmentReader(session, scope);
    const [issues, changeSets, overview, context, notifications, acks, channels, safety] =
        await Promise.all([
            desk
                .issues()
                .then((p) => p.items)
                .catch(empty<ApiIssue>),
            chief
                ? desk
                      .changeSets()
                      .then((p) => p.items)
                      .catch(empty<ChangeSet>)
                : Promise.resolve(empty<ChangeSet>()),
            reader.overview().catch(() => null),
            chief
                ? equipmentCommands(session, scope)
                      .context()
                      .catch(() => null)
                : Promise.resolve(null),
            // 알림함·변경 확인·채널(#373). 권한이 없으면 비우고, 일시 오류면 직전 값을 둔다.
            pages((cursor) => new NotificationDesk(session).list(cursor), NOTICE_PAGES).catch(
                keep<Notification>(previous?.notifications),
            ),
            pages((cursor) => desk.myChanges(undefined, cursor), NOTICE_PAGES).catch(
                keep<Acknowledgment>(previous?.acks),
            ),
            session.api
                .get(scope, `/events/${scope.eventId}/channels`, parseChannelPage)
                .then((p) => p.items)
                .catch(keep<Channel>(previous?.channels)),
            // 안전 지도·카메라(#372). 총괄만 읽을 수 있다. 일시 오류면 직전 값을 둔다.
            chief ? loadSafety(session, scope, previous?.safety) : Promise.resolve(null),
        ]);
    // 내 진행 중 업무만 단건 조회해 장비 지시를 읽는다. 읽지 못한 업무는 지시 없이 둔다.
    const taskDetails = new Map<string, ApiTask>();
    const client = workflow(session, scope);
    await Promise.all(
        (extra.tasks ?? [])
            .filter(
                (t) =>
                    t.assigneeId === scope.memberId &&
                    (t.status === 'WAITING' || t.status === 'IN_PROGRESS'),
            )
            .slice(0, DETAIL_LIMIT)
            .map(async (t) => {
                try {
                    taskDetails.set(t.id, await client.task(t.id));
                } catch {
                    // 지시 없이 업무만 보인다
                }
            }),
    );
    const simulations = new Map<string, RecoverySimulation>();
    const targets = new Set(equipmentIssues(issues).map((i) => i.id));
    for (const id of recoveryIssues) if (issues.some((i) => i.id === id)) targets.add(id);
    if (chief)
        await Promise.all(
            [...targets].map(async (id) => {
                try {
                    const pointer = await desk.latestSimulation(id);
                    if (pointer.expired) return;
                    simulations.set(id, await desk.simulation(pointer.simulationId));
                } catch {
                    // 계산이 아직 없거나(404) 만료되었다. 화면에서 새로 계산한다.
                }
            }),
        );
    // 분석 작업은 총괄만 읽을 수 있다. 결과를 반영해 이슈에 분석이 생긴 작업은 더 읽지 않는다.
    const analysisJobs = new Map<string, AnalysisJobState>();
    if (chief)
        await Promise.all(
            [...analysisJobIds].map(async ([issueId, jobId]) => {
                const issue = issues.find((i) => i.id === issueId);
                if (!issue) return;
                try {
                    const job = await desk.analysisJob(jobId);
                    analysisJobs.set(issueId, {
                        state: job.state,
                        code: job.state === 'FAILED' ? job.error.code : undefined,
                    });
                } catch {
                    // 작업을 읽지 못하면 보고 시각으로 판단한다
                }
            }),
        );
    return {
        issues,
        changeSets,
        assets: overview?.assets ?? [],
        reservations: overview?.reservations ?? [],
        connections: (context?.connections ?? []).map((c) => ({
            id: c.id,
            fromAssetId: c.fromAssetId,
            fromPort: c.fromPort,
            toAssetId: c.toAssetId,
            toPort: c.toPort,
            confirmedAt: c.confirmedAt,
            active: c.active,
        })),
        simulations,
        analysisJobs,
        notifications,
        acks,
        taskDetails,
        equipmentRecords: extra.equipmentRecords,
        channels,
        safety,
        me: {
            eventId: scope.eventId,
            memberId: scope.memberId,
            participationRevision: scope.participationRevision,
        },
    };
}

async function loadSafety(
    session: IdentitySession,
    scope: ParticipationScope,
    previous: LiveSafety | null | undefined,
): Promise<LiveSafety | null> {
    const reader = new SafetyReader(session, scope);
    try {
        const [map, cameras] = await Promise.all([reader.map(), reader.cameras()]);
        return { map, cameras: cameras.items };
    } catch (error) {
        // 권한 없음(403)·없음(404)은 비우고, 그 밖의 일시 오류는 직전 값을 둔다
        const status = classifyFailure(error).status;
        return status === 403 || status === 404 ? null : (previous ?? null);
    }
}

/** 읽은 자료를 화면 상태에 채운다. 변경안의 화면 연산 ↔ 서버 연산 번호도 함께 돌려준다. */
export function fillOps(
    data: EventData,
    raw: OpsRaw,
    plan: Plan | null,
    tasks: readonly ApiTask[],
    chiefId = '',
): Map<string, number[]> {
    const look = lookup(plan, tasks);
    data.issues = toIssues(raw.issues, look, Date.now(), raw.analysisJobs);
    const opIndex = new Map<string, number[]>();
    data.changes = raw.changeSets.map((set) => {
        const { change, opIndex: index } = toChange(set, look, chiefId);
        opIndex.set(set.id, index);
        return change;
    });
    data.equipment = toEquipment(raw.assets, raw.connections, look);
    data.reservations = toReservations(raw.reservations, look);
    const names = new Map(raw.assets.map((a) => [a.id, a.name]));
    data.recovery = [...raw.simulations.values()].flatMap((s) => toRecovery(s, names, look));
    // 해제할 서버 예약 id를 화면 예약 줄(`id#n`)로 펼친다. 그래야 낡음 판단·전후 표시가 예약을 찾는다.
    for (const c of data.changes) c.releases = expandReleases(c.releases, data.reservations);
    for (const o of data.recovery) o.releases = expandReleases(o.releases, data.reservations);
    // 내 업무의 장비 지시(#373). 안전 도착 기록은 서버 API가 없어 실서버에서 보이지 않는다(결정, V9-D49).
    for (const [id, detail] of raw.taskDetails ?? []) {
        const t = data.tasks.find((x) => x.id === id);
        const eq = detail.equipment ?? [];
        if (!t || !eq.length) continue;
        const zone = (zoneId: string | null) =>
            (zoneId && plan?.zones.find((z) => z.id === zoneId)?.name) || '지정 없음';
        t.equipment = {
            assets: eq.map((e) => e.assetId),
            pickup: zone(eq[0].pickupZoneId),
            dest: zone(eq[0].destinationZoneId),
            reason: eq.map((e) => e.instruction).join(' / '),
            records: { ...(raw.equipmentRecords?.get(id) ?? {}) },
        };
    }
    // 안전 지도·카메라(#372). 구역은 공연 계획의 구역이다.
    if (raw.safety && plan) {
        const view = toSafety(
            raw.safety,
            plan.zones,
            new Map(data.members.map((m) => [m.id, m.name])),
        );
        data.safetyZones = view.safetyZones;
        data.observations = view.observations;
        data.cameras = view.cameras;
        data.safetyDefaultLayout = view.defaultArrangement;
    }
    // 채팅 방(#373). 메시지는 LiveBridge의 채팅 연결이 채운다.
    if (raw.channels) data.rooms = toRooms(raw.channels);
    // 알림함·미확인 변경(#373)
    if (raw.me) {
        markPendingAcks(data.tasks, raw.acks ?? []);
        data.notices = toNotices(raw.notifications ?? [], raw.acks ?? [], {
            ...raw.me,
            tasks: data.tasks,
            issues: data.issues,
        });
    }
    return opIndex;
}
