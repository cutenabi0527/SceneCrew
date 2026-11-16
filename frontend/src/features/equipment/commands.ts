import type { CommandSpec } from '../../shared/api/api';
import { ContractViolationError, asUuid } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { sameKeyResult, type Result } from '../response/client';
import type { LiveAsset, TechnicalState } from './live-contract';

// 장비 운영 명령(#308 T2): 등록(EXT02)·상태 관측(EXT04)·장비 관계(R1 연결·필요 조건·선후 관계)와 관계 조회.
// - 모든 명령은 같은 키 재요청(24시간 최초 응답 재전달)이다. 응답을 잃으면 같은 의도(키·본문)로만 다시 보낸다.
//   결과 불명·만료는 같은 키도 다시 보내지 않고 현재 상태를 조회한다(sameKeyResult).
// - 관계 명령은 현재 총괄 전용이다. 상태 관측은 총괄 화면에서 taskId 없이 보낸다(팀원 점검은 내 업무의 EXT09).
// - 확인 근거는 이 화면에서 총괄이 직접 확인한 것(MANUAL_CONFIRMATION)으로 적는다. 자동 계측으로 꾸미지 않는다.

export interface Receipt {
    id: string;
    revision: string;
    eventRevision: string;
}
export interface ReleaseReceipt {
    id: string;
    eventRevision: string;
    released: true;
}

export interface Connection {
    id: string;
    eventId: string;
    fromAssetId: string;
    fromPort: string;
    toAssetId: string;
    toPort: string;
    confirmedByMemberId: string;
    confirmedAt: string;
    revision: string;
    active: boolean;
}
export interface RequirementItem {
    assetIds: string[];
    compatibleAssetIds: string[];
    quantity: number;
    componentAssetIds: string[];
    requiredPortKeys: string[];
}
export interface Evidence {
    sourceKind: string;
    sourceRef: string;
    observedAt: string | null;
    recordedAt: string;
    confirmedByMemberId: string | null;
    confirmed: boolean;
}
export interface Requirement {
    id: string;
    eventId: string;
    programId: string | null;
    taskIds: string[];
    requirements: RequirementItem[];
    readyBy: string;
    schedulePolicy: {
        mode: 'FIXED' | 'CHANGE_ALLOWED';
        allowedStartAt: string | null;
        allowedEndAt: string | null;
        evidence: Evidence;
    };
    timeEvidence: Evidence;
    revision: string;
}
export interface TypedRef {
    kind: 'PROGRAM' | 'TASK';
    id: string;
}
export interface Dependency {
    id: string;
    eventId: string;
    from: TypedRef;
    to: TypedRef;
    lagSec: number;
    evidence: Evidence;
    confirmedByMemberId: string;
    confirmedAt: string;
}
export interface EquipmentContext {
    eventId: string;
    eventRevision: string;
    connections: Connection[];
    requirements: Requirement[];
    dependencies: Dependency[];
}

export function parseContext(data: unknown, eventId: string): EquipmentContext {
    const ctx = parseV9<EquipmentContext>('FinalEquipmentContext', data);
    const all = [...ctx.connections, ...ctx.requirements, ...ctx.dependencies];
    if (ctx.eventId !== eventId || all.some((x) => x.eventId !== eventId))
        throw new ContractViolationError('equipmentContext.eventId', '현재 공연이어야 합니다.');
    return ctx;
}
export const parseReceipt = (d: unknown) => parseV9<Receipt>('FinalReceipt', d);
export function parseRelease(d: unknown, id: string) {
    const r = parseV9<ReleaseReceipt>('FinalRelationDeleteReceipt', d);
    if (r.id !== id) throw new ContractViolationError('release.id', '요청한 관계여야 합니다.');
    return r;
}

type Spec = Omit<CommandSpec, 'operation' | 'intentId'>;
export type RelationKind = 'connections' | 'requirements' | 'dependencies';

export class EquipmentCommands {
    private intents = new Map<string, { id: string; spec: Spec }>();

    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {
        scope.onDispose(() => this.intents.clear());
    }

    /** 이 화면에서 총괄이 확인했다는 근거 */
    private confirmed(): Evidence {
        const now = new Date().toISOString();
        return {
            sourceKind: 'MANUAL_CONFIRMATION',
            sourceRef: `chief:${this.scope.memberId}`,
            observedAt: now,
            recordedAt: now,
            confirmedByMemberId: this.scope.memberId,
            confirmed: true,
        };
    }
    private input(note?: string) {
        return {
            sourceKind: 'MANUAL_CONFIRMATION',
            sourceRef: `chief:${this.scope.memberId}`,
            observedAt: new Date().toISOString(),
            ...(note?.trim() && { note: note.trim() }),
        };
    }

    /** 응답을 잃은 의도가 남아 있는가(같은 내용으로 다시 보내기를 보여 줄지) */
    pending(key: string) {
        return this.intents.has(key);
    }
    /** 사용자가 결과를 확인하고 새로 하겠다고 했다 */
    forget(key: string) {
        const intent = this.intents.get(key);
        if (intent)
            this.session.scopes.intentKeys.release(this.scope, `equipment:${key}`, intent.id);
        this.intents.delete(key);
    }

    private async send<T>(key: string, spec: Spec, parser: (d: unknown) => T): Promise<Result<T>> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        // 남은 의도가 있으면 원래 본문 그대로 보낸다(새 입력을 같은 키에 끼워 넣지 않는다)
        const intent = this.intents.get(key) ?? { id: crypto.randomUUID(), spec };
        this.intents.set(key, intent);
        const outcome = await this.session.api.command(
            this.scope,
            {
                ...intent.spec,
                operation: `equipment:${key}`,
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

    context(signal?: AbortSignal) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        return this.session.api.get(
            this.scope,
            `/events/${this.scope.eventId}/equipment-context`,
            (d) => parseContext(d, this.scope.eventId),
            { signal },
        );
    }

    /** EXT02 장비 등록(총괄) */
    async register(
        input: {
            name: string;
            model: string | null;
            ports: { key: string; kind: string }[];
            storageZoneId: string | null;
            note?: string;
        },
        eventRevision: string,
    ) {
        const name = input.name.trim();
        if (!name) throw new ContractViolationError('name', '장비 이름이 필요합니다.');
        const keys = input.ports.map((p) => p.key);
        if (new Set(keys).size !== keys.length)
            throw new ContractViolationError('ports', '포트 이름이 겹칩니다.');
        return this.send(
            'register',
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/equipment`,
                body: {
                    expectedEventRevision: eventRevision,
                    name,
                    model: input.model?.trim() || null,
                    componentAssetIds: [],
                    ports: input.ports,
                    storageZoneId: input.storageZoneId,
                    evidence: this.input(input.note),
                },
            },
            parseReceipt,
        );
    }

    /** EXT04 장비 상태 관측 기록(총괄 화면, taskId 없음) */
    async observe(
        asset: LiveAsset,
        technicalState: TechnicalState,
        note: string,
        eventRevision: string,
    ) {
        const id = asUuid(asset.id, 'assetId');
        return this.send(
            `observe:${id}`,
            {
                method: 'POST',
                path: `/equipment/${id}/observations`,
                body: {
                    expectedEventRevision: eventRevision,
                    expectedAssetRevision: asset.revision,
                    taskId: null,
                    technicalState,
                    evidence: this.input(note),
                },
            },
            parseReceipt,
        );
    }

    /** R1 연결 추가·바꾸기 */
    async saveConnection(
        input: {
            fromAssetId: string;
            fromPort: string;
            toAssetId: string;
            toPort: string;
            summary: string;
        },
        eventRevision: string,
        replace?: { id: string; revision: string },
    ) {
        if (input.fromAssetId === input.toAssetId && input.fromPort === input.toPort)
            throw new ContractViolationError('connection', '같은 포트끼리는 연결할 수 없습니다.');
        const body = {
            fromAssetId: asUuid(input.fromAssetId, 'fromAssetId'),
            fromPort: input.fromPort,
            toAssetId: asUuid(input.toAssetId, 'toAssetId'),
            toPort: input.toPort,
            expectedEventRevision: eventRevision,
            confirmation: this.confirmation(input.summary),
        };
        return this.relation('connections', body, replace);
    }

    /** R1 필요 조건 추가·바꾸기 */
    async saveRequirement(
        input: {
            taskIds: string[];
            programId: string | null;
            assetIds: string[];
            quantity: number;
            readyBy: string;
            summary: string;
        },
        eventRevision: string,
        replace?: { id: string; revision: string },
    ) {
        if (!input.taskIds.length && !input.programId)
            throw new ContractViolationError(
                'requirement',
                '업무나 프로그램을 하나 이상 골라야 합니다.',
            );
        if (!input.assetIds.length || input.quantity < 1)
            throw new ContractViolationError('requirement', '장비와 수량이 필요합니다.');
        const evidence = this.confirmed();
        const body = {
            programId: input.programId,
            taskIds: input.taskIds.map((t) => asUuid(t, 'taskIds')),
            requirements: [
                {
                    assetIds: input.assetIds.map((a) => asUuid(a, 'assetIds')),
                    compatibleAssetIds: [],
                    quantity: input.quantity,
                    componentAssetIds: [],
                    requiredPortKeys: [],
                },
            ],
            readyBy: input.readyBy,
            schedulePolicy: { mode: 'FIXED', allowedStartAt: null, allowedEndAt: null, evidence },
            timeEvidence: evidence,
            expectedEventRevision: eventRevision,
            confirmation: this.confirmation(input.summary),
        };
        return this.relation('requirements', body, replace);
    }

    /** R1 선후 관계 추가·바꾸기. 선후 관계는 revision이 없어 공연 revision으로만 충돌을 막는다. */
    async saveDependency(
        input: { from: TypedRef; to: TypedRef; lagSec: number; summary: string },
        eventRevision: string,
        replace?: { id: string },
    ) {
        if (input.from.kind === input.to.kind && input.from.id === input.to.id)
            throw new ContractViolationError(
                'dependency',
                '같은 대상끼리는 선후 관계를 둘 수 없습니다.',
            );
        if (!(input.lagSec >= 0))
            throw new ContractViolationError('lagSec', '0 이상의 초여야 합니다.');
        const body = {
            from: input.from,
            to: input.to,
            lagSec: input.lagSec,
            evidence: this.confirmed(),
            expectedEventRevision: eventRevision,
            confirmation: this.confirmation(input.summary),
        };
        return this.relation('dependencies', body, replace && { id: replace.id, revision: null });
    }

    private confirmation(summary: string) {
        const text = summary.trim();
        if (!text) throw new ContractViolationError('confirmation', '확인 내용을 적어주세요.');
        return {
            summary: text,
            sourceKind: 'MANUAL_CONFIRMATION',
            observedAt: new Date().toISOString(),
        };
    }

    private relation(
        kind: RelationKind,
        body: Record<string, unknown>,
        replace?: { id: string; revision: string | null },
    ) {
        const base = `/events/${this.scope.eventId}/equipment-${kind}`;
        if (!replace)
            return this.send(`${kind}:create`, { method: 'POST', path: base, body }, parseReceipt);
        const id = asUuid(replace.id, 'relationId');
        return this.send(
            `${kind}:${id}`,
            {
                method: 'PATCH',
                path: `${base}/${id}`,
                body:
                    replace.revision === null
                        ? body
                        : { ...body, expectedRevision: replace.revision },
            },
            parseReceipt,
        );
    }

    /** R1 관계 해제. 기대 revision은 query로 보낸다(선후 관계는 공연 revision만). */
    async release(kind: RelationKind, id: string, eventRevision: string, revision: string | null) {
        const rid = asUuid(id, 'relationId');
        const query = new URLSearchParams({ expectedEventRevision: eventRevision });
        if (kind !== 'dependencies') {
            if (revision === null)
                throw new ContractViolationError('expectedRevision', '관계 revision이 필요합니다.');
            query.set('expectedRevision', revision);
        }
        return this.send(
            `${kind}:release:${rid}`,
            {
                method: 'DELETE',
                path: `/events/${this.scope.eventId}/equipment-${kind}/${rid}?${query.toString()}`,
            },
            (d) => parseRelease(d, rid),
        );
    }
}

const desks = new WeakMap<ParticipationScope, EquipmentCommands>();
/** 같은 참여 회차 안에서는 화면을 다시 열어도 남은 의도(키·본문)를 이어 간다. */
export function equipmentCommands(session: IdentitySession, scope: ParticipationScope) {
    let value = desks.get(scope);
    if (!value) {
        value = new EquipmentCommands(session, scope);
        desks.set(scope, value);
    }
    return value;
}
