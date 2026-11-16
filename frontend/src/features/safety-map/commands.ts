import type { CommandSpec } from '../../shared/api/api';
import { ContractViolationError, asUuid } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { sameKeyResult, type Result } from '../response/client';
import type { Camera, Point, SafetyMap } from './contract';

// 안전 지도·카메라 설정(#309 T3, 현재 총괄 전용). 지도 저장(PATCH safety-map)·카메라 등록·수정.
// - 모두 같은 키 재요청이다. 응답을 잃으면 같은 의도(키·본문)로만 다시 보낸다.
// - 지도 저장은 배치도와 구역 프로필 전체를 바꾼다. 바꾸지 않은 값(위험 기준·면적·최소 인원 기준)은
//   서버가 준 값과 근거를 그대로 돌려보내 지우지 않는다.
// - 카메라 수정은 이름·사용 여부·연결 담당 업무·관측 구역(coverage)을 바꾼다. 관측 구역을 바꾸면 서버가 보정 확인을 다시 요구한다.
// - 근거는 이 화면에서 총괄이 확인한 것(MANUAL_CONFIRMATION)으로 적는다.

export interface Receipt {
    id: string;
    revision: string;
    eventRevision: string;
}
const parseReceipt = (d: unknown) => parseV9<Receipt>('FinalReceipt', d);

type ReadEvidence = { sourceKind: string; sourceRef: string; observedAt: string | null };
type ReadRule = { version: string; unit: string; condition: string; evidence: ReadEvidence };
type ReadCrewRule = {
    workGroupId: string;
    minimumCount: number;
    startAt: string;
    endAt: string;
    evidence: ReadEvidence;
};

/** 조회 근거(V9Evidence)를 쓰기 근거(V9EvidenceInput)로 옮긴다. 확인자·기록 시각은 서버가 다시 정한다. */
const asInput = (e: ReadEvidence) => ({
    sourceKind: e.sourceKind,
    sourceRef: e.sourceRef,
    observedAt: e.observedAt,
});

export interface ZoneInput {
    zoneId: string;
    points: Point[];
    effectiveAreaM2: number | null;
    // 위험 기준 조건 문장. null이면 기준 없음
    riskCondition: string | null;
    // 최소 인원 기준. null이면 기준 없음, 생략하면 서버가 준 값을 그대로 둔다
    minimumCrew?: MinimumCrewInput | null;
}

export interface MinimumCrewInput {
    workGroupId: string;
    minimumCount: number;
    // UTC ISO 시각
    startAt: string;
    endAt: string;
}

const sameCrew = (a: ReadCrewRule, b: MinimumCrewInput) =>
    a.workGroupId === b.workGroupId &&
    a.minimumCount === b.minimumCount &&
    Date.parse(a.startAt) === Date.parse(b.startAt) &&
    Date.parse(a.endAt) === Date.parse(b.endAt);

/** "x,y; x,y; x,y" 형식의 정규화 좌표(0~1)를 읽는다. 세 점 미만이거나 범위를 벗어나면 null */
export function parsePoints(text: string): Point[] | null {
    const parts = text
        .split(';')
        .map((t) => t.trim())
        .filter(Boolean);
    const points = parts.map((p) => {
        const [x, y] = p.split(',').map((v) => Number(v.trim()));
        return { x, y };
    });
    if (points.length < 3) return null;
    if (points.some((p) => !Number.isFinite(p.x) || !Number.isFinite(p.y))) return null;
    if (points.some((p) => p.x < 0 || p.x > 1 || p.y < 0 || p.y > 1)) return null;
    return points;
}
export const formatPoints = (points: Point[]) => points.map((p) => `${p.x},${p.y}`).join('; ');

export interface CoverageInput {
    zoneId: string;
    scope: 'FULL' | 'PARTIAL';
    roi: Point[];
    effectiveAreaM2: number | null;
    note?: string;
}

type Spec = Omit<CommandSpec, 'operation' | 'intentId'>;

export class SafetyCommands {
    private intents = new Map<string, { id: string; spec: Spec }>();

    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {
        scope.onDispose(() => this.intents.clear());
    }

    pending(key: string) {
        return this.intents.has(key);
    }
    forget(key: string) {
        const intent = this.intents.get(key);
        if (intent) this.session.scopes.intentKeys.release(this.scope, `safety:${key}`, intent.id);
        this.intents.delete(key);
    }

    private evidence(note?: string) {
        return {
            sourceKind: 'MANUAL_CONFIRMATION',
            sourceRef: `chief:${this.scope.memberId}`,
            observedAt: new Date().toISOString(),
            ...(note?.trim() && { note: note.trim() }),
        };
    }

    private async send<T>(key: string, spec: Spec, parser: (d: unknown) => T): Promise<Result<T>> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const intent = this.intents.get(key) ?? { id: crypto.randomUUID(), spec };
        this.intents.set(key, intent);
        const outcome = await this.session.api.command(
            this.scope,
            {
                ...intent.spec,
                operation: `safety:${key}`,
                intentId: intent.id,
                recovery: 'same-key',
                response: 'data',
            },
            parser,
        );
        const { result, keep } = sameKeyResult(outcome);
        if (!keep && this.intents.get(key) === intent) this.intents.delete(key);
        return result;
    }

    /** 배치도와 구역 프로필 저장 */
    async saveMap(
        map: SafetyMap,
        input: { width: number; height: number; zones: ZoneInput[]; note?: string },
        eventRevision: string,
    ) {
        if (!(input.width > 0 && input.height > 0))
            throw new ContractViolationError('layout', '배치도 가로/세로는 0보다 커야 합니다.');
        if (!input.zones.length)
            throw new ContractViolationError('layout', '구역을 하나 이상 그려야 합니다.');
        const ids = input.zones.map((z) => z.zoneId);
        if (new Set(ids).size !== ids.length)
            throw new ContractViolationError('layout', '같은 구역을 두 번 그릴 수 없습니다.');
        for (const z of input.zones) {
            if (z.points.length < 3)
                throw new ContractViolationError('layout', '구역은 세 점 이상이어야 합니다.');
            if (z.effectiveAreaM2 !== null && !(z.effectiveAreaM2 > 0))
                throw new ContractViolationError('effectiveAreaM2', '면적은 0보다 커야 합니다.');
            const crew = z.minimumCrew;
            if (crew) {
                if (!Number.isInteger(crew.minimumCount) || crew.minimumCount < 0)
                    throw new ContractViolationError(
                        'minimumCount',
                        '최소 인원은 0 이상의 정수여야 합니다.',
                    );
                const start = Date.parse(crew.startAt);
                const end = Date.parse(crew.endAt);
                if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end)
                    throw new ContractViolationError(
                        'minimumCrewRule',
                        '최소 인원 기준의 끝 시각은 시작 시각보다 뒤여야 합니다.',
                    );
            }
        }
        const now = this.evidence(input.note);
        const profiles = input.zones.map((z) => {
            const prev = map.profiles.find((p) => p.zoneId === z.zoneId) as
                | (SafetyMap['profiles'][number] & {
                      revision: string;
                      areaEvidence: ReadEvidence | null;
                  })
                | undefined;
            const prevRule = (prev?.riskRule ?? null) as ReadRule | null;
            const prevCrew = (prev?.minimumCrewRule ?? null) as ReadCrewRule | null;
            const areaSame = prev !== undefined && prev.effectiveAreaM2 === z.effectiveAreaM2;
            const ruleSame = (prevRule?.condition ?? null) === z.riskCondition;
            return {
                zoneId: asUuid(z.zoneId, 'zoneId'),
                expectedProfileRevision: prev?.revision ?? null,
                effectiveAreaM2: z.effectiveAreaM2,
                areaEvidence:
                    z.effectiveAreaM2 === null
                        ? null
                        : areaSame && prev?.areaEvidence
                          ? asInput(prev.areaEvidence)
                          : now,
                riskRule:
                    z.riskCondition === null
                        ? null
                        : ruleSame && prevRule
                          ? { ...prevRule, evidence: asInput(prevRule.evidence) }
                          : {
                                version: 'v1',
                                unit: '명/㎡',
                                condition: z.riskCondition,
                                evidence: now,
                            },
                // 바꾸지 않은 최소 인원 기준은 서버가 준 값과 근거를 그대로 돌려보낸다
                minimumCrewRule:
                    z.minimumCrew === undefined ||
                    (z.minimumCrew && prevCrew && sameCrew(prevCrew, z.minimumCrew))
                        ? prevCrew
                            ? { ...prevCrew, evidence: asInput(prevCrew.evidence) }
                            : null
                        : z.minimumCrew === null
                          ? null
                          : {
                                workGroupId: asUuid(z.minimumCrew.workGroupId, 'workGroupId'),
                                minimumCount: z.minimumCrew.minimumCount,
                                startAt: new Date(z.minimumCrew.startAt).toISOString(),
                                endAt: new Date(z.minimumCrew.endAt).toISOString(),
                                evidence: now,
                            },
            };
        });
        return this.send(
            'map',
            {
                method: 'PATCH',
                path: `/events/${this.scope.eventId}/safety-map`,
                body: {
                    expectedEventRevision: eventRevision,
                    expectedLayoutRevision: (map.layout?.revision as string | undefined) ?? null,
                    layout: {
                        coordinateSystem: 'normalized-image',
                        width: input.width,
                        height: input.height,
                        zonePolygons: input.zones.map((z) => ({
                            zoneId: z.zoneId,
                            points: z.points,
                        })),
                        source: now,
                    },
                    profiles,
                },
            },
            parseReceipt,
        );
    }

    /**
     * 카메라 등록. 저장된 배치도와 각 구역의 프로필이 있어야 한다.
     * 카메라 한 대가 여러 구역을 비추면 구역마다 화면 속 관측 영역을 따로 준다(예: 왼쪽 1구역, 오른쪽 2구역).
     */
    async createCamera(
        map: SafetyMap,
        input: {
            name: string;
            /** 예비 수집 프로그램 카메라만. 웹 연결 카메라는 비운다(등록 안됨으로 시작, #397) */
            sourceBindingRef?: string;
            enabled: boolean;
            /** 비우면 보정 전(보정 필요) 칸으로 만들고, 담당자가 연결한 뒤 현재 화면 위에 그린다 */
            coverage?: CoverageInput[];
            /** 연결 담당 업무. 빼면 서버가 기존 카메라 연결 업무를 자동 지정하고, null이면 지정하지 않는다(#395) */
            linkTaskId?: string | null;
        },
        eventRevision: string,
    ) {
        const name = input.name.trim();
        if (!name) throw new ContractViolationError('camera', '카메라 이름이 필요합니다.');
        const binding = input.sourceBindingRef?.trim();
        const body: Record<string, unknown> = {
            expectedEventRevision: eventRevision,
            name,
            enabled: input.enabled,
        };
        if (binding) body.sourceBindingRef = binding;
        if (input.coverage?.length) body.coverage = this.coverage(map, input.coverage);
        if (input.linkTaskId === null) body.linkTaskId = null;
        else if (input.linkTaskId) body.linkTaskId = asUuid(input.linkTaskId, 'linkTaskId');
        return this.send(
            'camera:create',
            { method: 'POST', path: `/events/${this.scope.eventId}/safety-cameras`, body },
            parseReceipt,
        );
    }

    /** 카메라 관측 구역(coverage) 목록. 각 항목을 저장된 배치도와 그 구역의 프로필 revision에 묶는다. */
    private coverage(map: SafetyMap, items: CoverageInput[]) {
        const layout = map.layout;
        if (!layout) throw new ContractViolationError('camera', '배치도를 먼저 저장해야 합니다.');
        if (!items.length)
            throw new ContractViolationError('coverage', '관측 구역을 하나 이상 정해야 합니다.');
        const zones = items.map((i) => i.zoneId);
        // 한 구역을 두 영역으로 나눠 적으면 같은 사람을 두 번 셀 수 있다
        if (new Set(zones).size !== zones.length)
            throw new ContractViolationError(
                'coverage',
                '한 카메라에서 같은 구역을 두 번 정할 수 없습니다.',
            );
        return items.map((input) => {
            const profile = map.profiles.find((p) => p.zoneId === input.zoneId) as
                { revision?: string } | undefined;
            if (!profile?.revision)
                throw new ContractViolationError(
                    'camera',
                    '이 구역의 프로필을 먼저 저장해야 합니다.',
                );
            if (input.roi.length < 3)
                throw new ContractViolationError('roi', '관측 범위는 세 점 이상이어야 합니다.');
            if (input.effectiveAreaM2 !== null && !(input.effectiveAreaM2 > 0))
                throw new ContractViolationError('effectiveAreaM2', '면적은 0보다 커야 합니다.');
            return {
                zoneId: asUuid(input.zoneId, 'zoneId'),
                layoutId: layout.id,
                layoutRevision: layout.revision as string,
                profileRevision: profile.revision,
                scope: input.scope,
                roi: input.roi,
                effectiveAreaM2: input.effectiveAreaM2,
                calibrationEvidence: this.evidence(input.note),
            };
        });
    }

    /** 카메라 이름·사용 여부·관측 구역 바꾸기 */
    async updateCamera(
        camera: Camera,
        changes: {
            name?: string;
            enabled?: boolean;
            coverage?: CoverageInput[];
            /** 연결 담당 업무. null이면 지정을 푼다 */
            linkTaskId?: string | null;
        },
        map?: SafetyMap,
    ) {
        const id = asUuid(camera.id, 'cameraId');
        const body: Record<string, unknown> = { expectedCameraRevision: camera.revision };
        if (changes.name !== undefined) {
            const name = changes.name.trim();
            if (!name) throw new ContractViolationError('name', '카메라 이름이 필요합니다.');
            if (name !== camera.name) body.name = name;
        }
        if (changes.enabled !== undefined && changes.enabled !== camera.enabled)
            body.enabled = changes.enabled;
        if (changes.linkTaskId !== undefined && changes.linkTaskId !== camera.linkTaskId)
            body.linkTaskId = changes.linkTaskId && asUuid(changes.linkTaskId, 'linkTaskId');
        if (changes.coverage) {
            if (!map) throw new ContractViolationError('camera', '안전 지도를 먼저 읽어야 합니다.');
            body.coverage = this.coverage(map, changes.coverage);
        }
        if (Object.keys(body).length < 2)
            throw new ContractViolationError('camera', '바꾼 내용이 없습니다.');
        return this.send(
            changes.coverage ? `camera:${id}:coverage` : `camera:${id}`,
            { method: 'PATCH', path: `/safety-cameras/${id}`, body },
            parseReceipt,
        );
    }
}

const desks = new WeakMap<ParticipationScope, SafetyCommands>();
export function safetyCommands(session: IdentitySession, scope: ParticipationScope) {
    let value = desks.get(scope);
    if (!value) {
        value = new SafetyCommands(session, scope);
        desks.set(scope, value);
    }
    return value;
}
