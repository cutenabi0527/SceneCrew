import type { Action } from '../domain/reducer';
import type { ChangeOp } from '../domain/types';
import { equipmentCommands } from '../features/equipment/commands';
import { NotificationDesk } from '../features/notifications/client';
import { responder } from '../features/response/client';
import type { ChangeSet } from '../features/response/contract';
import { kstDate } from './convert';
import type { Handler, LiveRuntime } from './runtime';
import { UNKNOWN, resultText, waitJob } from './runtime';

// 보고·대응(W04)·장비 운영(W03)·변경안(X01)의 쓰기 액션을 API 명령으로 바꾼다(#371).
// 화면 값은 직접 바꾸지 않고, 끝나면 LiveBridge가 서버에서 다시 읽어 그린다.

type Of<T extends Action['type']> = Extract<Action, { type: T }>;
function expect<T extends Action['type']>(action: Action, type: T): Of<T> {
    if (action.type !== type) throw new Error('잘못된 액션');
    return action as Of<T>;
}

const desk = (r: LiveRuntime) => responder(r.session, r.client.scope);
const zoneId = (r: LiveRuntime, name: string) => r.plan?.zones.find((z) => z.name === name)?.id;
const changeSet = (r: LiveRuntime, id: string) => r.ops.changeSets.find((s) => s.id === id);

/** KST 날짜(YYYY-MM-DD)와 HH:MM → UTC ISO */
export const kstToUtc = (date: string, hm: string) =>
    new Date(`${date}T${hm}:00+09:00`).toISOString();

/** KST 날짜의 시작·종료 시각 → UTC. 종료가 시작보다 이르면 자정을 넘긴 것으로 보고 다음 날로 둔다. */
export function kstRange(date: string, start: string, end: string) {
    const startAt = kstToUtc(date, start);
    let endAt = kstToUtc(date, end);
    if (Date.parse(endAt) < Date.parse(startAt))
        endAt = new Date(Date.parse(endAt) + 24 * 60 * 60 * 1000).toISOString();
    return { startAt, endAt };
}

// 같은 키 재요청 의도(Responder·EquipmentCommands)는 결과를 모르면 처음 본문을 그대로 다시 보낸다.
// 사용자가 다른 내용을 새로 보내면 처음 본문이 재전송되어 새 입력이 사라지므로, 이 화면에서 마지막으로 보낸
// 본문과 다르면 남은 의도를 버리고 새로 보낸다. 같은 내용이면 같은 키로 다시 보내 서버가 중복을 막는다.
const sentBodies = new WeakMap<object, Map<string, string>>();
/** 남은 의도가 이번 입력과 다른가. 다르면(또는 무엇을 보냈는지 모르면) 버린다. */
export function freshIntent(
    owner: object,
    key: string,
    body: unknown,
    pending: () => boolean,
    forget: () => void,
) {
    let sent = sentBodies.get(owner);
    if (!sent) {
        sent = new Map();
        sentBodies.set(owner, sent);
    }
    const signature = JSON.stringify(body);
    if (pending() && sent.get(key) !== signature) forget();
    sent.set(key, signature);
}
type ResponderOp = Parameters<ReturnType<typeof responder>['forget']>[0];
const freshDesk = (r: LiveRuntime, op: ResponderOp, target: string, body: unknown) => {
    const d = desk(r);
    freshIntent(
        d,
        `${op}:${target}`,
        body,
        () => d.pending(op, target),
        () => d.forget(op, target),
    );
    return d;
};
const freshEquipment = (r: LiveRuntime, key: string, body: unknown) => {
    const commands = equipmentCommands(r.session, r.client.scope);
    freshIntent(
        commands,
        key,
        body,
        () => commands.pending(key),
        () => commands.forget(key),
    );
    return commands;
};
/** 이 화면에서 마지막으로 보낸 등록 본문(가져오기를 멈춘 줄부터 이어 가는 데 쓴다) */
const lastSent = (owner: object, key: string) => sentBodies.get(owner)?.get(key);

const str = (v: unknown) => (typeof v === 'string' ? v : undefined);

/** 미리보기(069). 서버가 현재 상태로 diff·해시를 다시 계산한다. */
async function preview(r: LiveRuntime, set: ChangeSet, done: string) {
    const result = await desk(r).preview(set.id, set.revision, r.eventRevision);
    return result.kind === 'done' ? done : resultText(result, '미리보기');
}

/**
 * 화면에서 고친 연산을 서버 연산에 되돌려 쓴다. 대상·근거·예약은 그대로 두고, 연산마다 계약이 허용하는 칸만 쓴다.
 * - ADD_TASK: 시각·담당(assigneeId)·절차(화면 연산의 steps)
 * - UPDATE_TASK: 시각·절차(담당은 ASSIGN_TASK로만 바뀐다)
 * - ASSIGN_TASK: 새 담당(toMemberId)만
 * - UPDATE_PROGRAM: 시각
 * 날짜는 원래 연산(없으면 대상 업무·프로그램)의 한국 날짜를 유지한다. original(고치기 전 화면 연산)을 주면
 * 시각을 고치지 않은 연산에는 시각을 새로 넣지 않는다.
 */
export function editedOperations(
    set: ChangeSet,
    index: readonly number[],
    edited: readonly ChangeOp[],
    baseStart: (ref: { taskId?: string; programId?: string }) => string | undefined,
    original?: readonly ChangeOp[],
): ChangeSet['operations'] {
    const ops = set.operations.map((o) => ({ type: o.type, payload: { ...o.payload } }));
    edited.forEach((op, j) => {
        const k = index[j];
        const target = k === undefined ? undefined : ops[k];
        if (!target || op.kind === 'cancel') return;
        const p = target.payload;
        if (target.type === 'ASSIGN_TASK') {
            p.toMemberId = op.after.assignee ?? null;
            return;
        }
        const base =
            str(p.startAt) || baseStart({ taskId: str(p.taskId), programId: str(p.programId) });
        const day = base ? kstDate(base) : undefined;
        const prev = original?.[j];
        const prevAfter = prev && prev.kind !== 'cancel' ? prev.after : undefined;
        const timeChanged =
            !prevAfter || prevAfter.start !== op.after.start || prevAfter.end !== op.after.end;
        if (day && timeChanged && op.after.start && op.after.end)
            Object.assign(p, kstRange(day, op.after.start, op.after.end));
        if (target.type === 'ADD_TASK' && op.kind === 'add') {
            p.assigneeId = op.after.assignee ?? null;
            p.steps = op.steps;
        }
        if (target.type === 'UPDATE_TASK' && op.kind === 'update' && op.after.steps)
            p.steps = op.after.steps;
    });
    return ops;
}

/** 지금 공연·지금 참여 회차의 알림(#373). 화면 알림 id는 서버 알림 id다 */
const myNotifications = (r: LiveRuntime) => {
    const me = r.ops.me;
    return (r.ops.notifications ?? []).filter(
        (n) =>
            me && n.eventId === me.eventId && n.participationRevision === me.participationRevision,
    );
};

export const OPS_HANDLERS: Partial<Record<Action['type'], Handler>> = {
    /** 075 알림 읽음. 변경 확인이 아니다(IA05). 이미 읽은 알림은 보내지 않는다. */
    async readNotice(r, action) {
        const { id } = expect(action, 'readNotice');
        const n = myNotifications(r).find((x) => x.id === id);
        if (!n || n.readAt !== null) return '';
        const result = await new NotificationDesk(r.session).read(n.id);
        return result.kind === 'done' ? '' : resultText(result, '읽음 표시');
    },

    /** 모두 읽음: 일괄 API가 없어 안 읽은 알림을 하나씩 075로 보낸다. 실패하면 거기서 멈춘다. */
    async readAllNotices(r) {
        const unread = myNotifications(r).filter((n) => n.readAt === null);
        if (!unread.length) return '읽지 않은 알림이 없습니다.';
        const desk = new NotificationDesk(r.session);
        let done = 0;
        for (const n of unread) {
            const result = await desk.read(n.id);
            if (result.kind !== 'done')
                return `${done}건을 읽음으로 표시하고 멈췄습니다. ${resultText(result, '읽음 표시')}`;
            done++;
        }
        return `${done}건을 읽음으로 표시했습니다.`;
    },

    /**
     * 077 변경 명시 확인. 화면에 보인 대상의 서버 지문(targetsHash)을 보낸다. 그사이 대상이 바뀌면 서버가 거절하고,
     * 다시 읽은 내용을 보고 다시 확인한다(P05).
     */
    async ackNotice(r, action, state) {
        const { id } = expect(action, 'ackNotice');
        const n = myNotifications(r).find((x) => x.id === id);
        const ack =
            n?.target.kind === 'CHANGE_ACK'
                ? (r.ops.acks ?? []).find((a) => a.id === n.target.id)
                : undefined;
        if (!ack) return '확인할 변경을 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        if (ack.state !== 'PENDING') return '이미 확인한 변경입니다.';
        // 사용자가 화면에서 본 대상의 지문을 보낸다(P05). 그사이 다시 읽어 대상이 바뀌었으면 서버가 409로 거절한다.
        const shown = state.data.notices.find((x) => x.id === id)?.targetHash ?? ack.targetsHash;
        const result = await desk(r).ack(ack.id, shown);
        if (result.kind === 'done') return '변경 내용을 확인했습니다.';
        if (
            result.kind === 'conflict' ||
            (result.kind === 'rejected' && result.failure.status === 409)
        )
            // 077의 409는 지문이 바뀐 경우와 공연이 종료된 경우 모두 INVALID_STATE다
            return '확인하지 못했습니다. 확인하는 사이 변경 내용이 바뀌었거나 공연이 종료되었습니다. 다시 불러온 내용을 확인해주세요.';
        if (result.kind === 'rejected' && result.failure.status === 404)
            return '지금 참여 회차의 변경이 아닙니다. 최신 내용을 다시 불러왔습니다.';
        return resultText(result, '변경 확인');
    },

    /** 056 최초 보고·059 추가/해결 보고. */
    async report(r, action) {
        const { input } = expect(action, 'report');
        if (input.issueId) {
            const kind = input.kind === 'RESOLUTION' ? 'RESOLUTION' : 'FOLLOWUP';
            const d = freshDesk(r, 'addReport', `${input.issueId}:${kind}`, input.text);
            const result = await d.addReport(
                input.issueId,
                kind,
                input.text,
                input.audioId ?? null,
            );
            // 총괄이 보낸 보고의 분석 작업은 총괄 화면에서 진행·실패를 따라간다(#358)
            if (result.kind === 'done' && r.chief && result.data.analysisJobId)
                r.analysisJobs.set(input.issueId, result.data.analysisJobId);
            return result.kind === 'done'
                ? kind === 'RESOLUTION'
                    ? '해결 보고를 보냈습니다.'
                    : '추가 보고를 보냈습니다.'
                : resultText(result, '보고');
        }
        const zone = zoneId(r, input.zone);
        if (!zone) return '구역을 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        const task = input.taskId ? r.tasks.find((t) => t.id === input.taskId) : undefined;
        // 총괄은 직무 그룹이 없다. 업무가 없으면 그 구역 업무의 그룹(없으면 첫 그룹)으로 보낸다.
        const group = task
            ? undefined
            : r.chief
              ? (r.tasks.find((t) => t.zoneId === zone)?.workGroupId ?? r.plan?.workGroups[0]?.id)
              : undefined;
        const body = {
            zoneId: zone,
            workGroupId: group,
            taskId: input.taskId,
            text: input.text,
            audioId: input.audioId ?? null,
        };
        // 보고 접수는 대상이 고정('new')이라, 결과를 모르는 앞 보고와 내용이 다르면 그 의도를 버리고 새로 보낸다
        const d = freshDesk(r, 'createIssue', 'new', body);
        const result = await d.createIssue(body);
        if (result.kind !== 'done') return resultText(result, '보고');
        if (r.chief && result.data.analysisJobId)
            r.analysisJobs.set(result.data.issue.id, result.data.analysisJobId);
        return '보고를 접수했습니다.';
    },

    /**
     * 062 가장 최근 보고 다시 분석(#358). 분석이 실패했거나, 분석 뒤 공연 정보가 바뀌어 낡았거나, 결과가 반영되지 않았을 때
     * 총괄이 요청한다. 접수한 작업은 기억해 두고 화면을 다시 읽을 때 진행·실패를 보인다.
     */
    async reanalyze(r, action) {
        const { issueId } = expect(action, 'reanalyze');
        if (!r.chief) return '분석은 총괄만 요청할 수 있습니다.';
        const issue = r.ops.issues.find((i) => i.id === issueId);
        const latest = issue?.reports.reduce<(typeof issue.reports)[number] | undefined>(
            (a, x) => (!a || (x.createdAt ?? '') >= (a.createdAt ?? '') ? x : a),
            undefined,
        );
        if (!latest) return '분석할 보고를 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        // 같은 보고를 다시 요청하면 앞 요청의 결과와 섞이지 않게 새 의도로 보낸다
        const d = desk(r);
        d.forget('analyze', latest.id);
        const result = await d.analyze(latest.id, r.eventRevision);
        if (result.kind !== 'job') return resultText(result, '분석 요청');
        r.analysisJobs.set(issueId, result.jobId);
        return '보고 분석을 다시 요청했습니다. 결과가 나오면 화면에 보입니다.';
    },

    /** 058 해결 확인 후 종료 */
    async closeIssue(r, action) {
        const { id } = expect(action, 'closeIssue');
        const result = await desk(r).patchIssue(id, { status: 'CLOSED' }, r.eventRevision);
        return result.kind === 'done' ? '이슈를 종료했습니다.' : resultText(result, '종료');
    },

    /** EXT19 운영 상황 정정(V9-D48). 사유가 필수다. */
    async setSituation(r, action) {
        const a = expect(action, 'setSituation');
        const reason = a.reason?.trim();
        if (!reason) return '정정 사유를 입력해주세요. 바뀐 것이 없습니다.';
        const input = { primaryType: a.primary, relatedTypes: a.related, reason };
        const result = await freshDesk(r, 'correctSituation', a.issueId, input).correctSituation(
            a.issueId,
            input,
            r.eventRevision,
        );
        return result.kind === 'done' ? '운영 상황을 정정했습니다.' : resultText(result, '정정');
    },

    /** 064 AI 대응안 생성(#343). 접수 뒤 결과를 기다려 변경안이 생기면 확정 대기 목록에 보인다. */
    async aiChange(r, action) {
        const { issueId } = expect(action, 'aiChange');
        const issue = r.ops.issues.find((i) => i.id === issueId);
        if (!issue) return '이슈를 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        const instruction = '이 보고에 대한 대응안을 만들어주세요.';
        const reportIds = issue.reports.map((x) => x.id);
        const d = freshDesk(r, 'generate', issueId, { instruction, reportIds });
        const accepted = await d.generateChange({
            issueId,
            instruction,
            reportIds,
            expectedEventRevision: r.eventRevision,
        });
        if (accepted.kind === 'rejected')
            return 'AI 대응안을 아직 만들 수 없습니다. 장비 이상은 장비 운영의 복구 대안으로 대응해주세요.';
        if (accepted.kind !== 'job') return resultText(accepted, 'AI 대응안 요청');
        const job = await waitJob(() => d.changeJob(accepted.jobId));
        if (!job) return 'AI 대응안을 만드는 중입니다. 잠시 뒤 확정 대기 변경에서 확인해주세요.';
        if (job.state === 'FAILED')
            return 'AI 대응안을 만들지 못했습니다. 다시 요청하거나 직접 대응해주세요.';
        return job.result?.changeSetId
            ? 'AI 대응안을 만들었습니다. 확정 대기 변경에서 확인해주세요.'
            : 'AI가 대응안 대신 추가 질문을 남겼습니다. 보고를 보완한 뒤 다시 요청해주세요.';
    },

    /** 070 승인. 미리보기 전(초안)이면 먼저 미리보기만 하고 총괄이 다시 확인하게 한다. */
    async applyChange(r, action) {
        const { id } = expect(action, 'applyChange');
        const set = changeSet(r, id);
        if (!set) return '변경안을 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        if (set.status !== 'READY' || !set.previewHash)
            return preview(
                r,
                set,
                '미리보기를 만들었습니다. 바뀐 내용을 확인한 뒤 다시 확정해주세요.',
            );
        const d = freshDesk(r, 'apply', set.id, [set.revision, set.previewHash]);
        const result = await d.apply(set.id, {
            expectedChangeSetRevision: set.revision,
            previewHash: set.previewHash,
            expectedEventRevision: r.eventRevision,
        });
        if (result.kind !== 'done') return resultText(result, '확정');
        const touchesTasks = set.operations.some(
            (o) => !o.type.includes('RESERVATION') && o.type !== 'RESERVE_EQUIPMENT',
        );
        return touchesTasks
            ? '변경을 확정했습니다. 영향 받는 팀원에게 변경 확인 알림이 갑니다.'
            : '변경을 확정했습니다. 장비 예약이 바뀌었습니다.';
    },

    /** 다시 확인: 같은 연산으로 현재 상태에 맞춰 미리보기를 다시 만든다 */
    async rebaseChange(r, action) {
        const { id } = expect(action, 'rebaseChange');
        const set = changeSet(r, id);
        if (!set) return '변경안을 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        return preview(r, set, '현재 상태로 다시 확인했습니다. 바뀐 내용을 본 뒤 확정해주세요.');
    },

    /** 071 폐기 */
    async discardChange(r, action) {
        const { id } = expect(action, 'discardChange');
        const set = changeSet(r, id);
        if (!set) return '변경안을 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        const result = await desk(r).discard(set.id, set.revision);
        return result.kind === 'done' ? '변경안을 폐기했습니다.' : resultText(result, '폐기');
    },

    /** 068 편집 → 069 미리보기. 편집하면 서버가 미리보기를 지우므로 바로 다시 만든다. */
    async editChange(r, action, state) {
        const a = expect(action, 'editChange');
        const set = changeSet(r, a.id);
        if (!set) return '변경안을 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        const operations = editedOperations(
            set,
            r.opIndex.get(set.id) ?? [],
            a.ops,
            ({ taskId, programId }) =>
                (taskId && r.tasks.find((t) => t.id === taskId)?.startAt) ||
                (programId && r.plan?.programs.find((p) => p.id === programId)?.startAt) ||
                undefined,
            state.data.changes.find((c) => c.id === a.id)?.ops,
        );
        const d = desk(r);
        const edited = await d.edit(set.id, { operations }, set.revision, r.eventRevision);
        if (edited.kind !== 'done') return resultText(edited, '편집');
        const result = await d.preview(edited.data.id, edited.data.revision, r.eventRevision);
        return result.kind === 'done'
            ? '고친 내용으로 미리보기를 다시 만들었습니다. 확인한 뒤 확정해주세요.'
            : resultText(result, '미리보기');
    },

    /** 고른 복구 대안 → 066 변경안 → 069 미리보기. 확정은 총괄이 변경안을 보고 따로 한다. */
    async recoveryChange(r, action) {
        const { optionId } = expect(action, 'recoveryChange');
        const [simulationId, candidateId] = optionId.split(':');
        const simulation = [...r.ops.simulations.values()].find((s) => s.id === simulationId);
        const candidate = simulation?.candidates.find((c) => c.candidateId === candidateId);
        if (!simulation || !candidate) return '복구 대안을 찾지 못했습니다. 다시 계산해주세요.';
        const d = desk(r);
        const created = await d.createFromCandidate({
            simulation,
            candidate,
            issueId: simulation.issueId,
            expectedEventRevision: r.eventRevision,
        });
        if (created.kind !== 'done') return resultText(created, '변경안 만들기');
        const set = created.data;
        const result = await d.preview(set.id, set.revision, set.eventRevision);
        return result.kind === 'done'
            ? '변경안을 만들었습니다. 바뀌는 내용을 확인한 뒤 확정해주세요.'
            : resultText(result, '미리보기');
    },

    /** EXT06 복구 대안 계산. 끝나면 대안이 보인다. 계산은 예약·담당을 바꾸지 않는다. */
    async recoveryRun(r, action) {
        const a = expect(action, 'recoveryRun');
        if (!a.assetIds.length) return '복구할 장비를 고른 뒤 계산해주세요.';
        const d = freshDesk(r, 'recover', a.issueId, [...a.assetIds].sort());
        // 장비 이상으로 분류되지 않은 이슈도 다시 읽을 때 이 계산 결과를 함께 읽는다
        r.recoveryIssues.add(a.issueId);
        const accepted = await d.recover({
            issueId: a.issueId,
            targetAssetIds: a.assetIds,
            expectedEventRevision: r.eventRevision,
        });
        if (accepted.kind !== 'job') return resultText(accepted, '복구 대안 계산');
        const job = await waitJob(() => d.recoveryJob(accepted.jobId));
        if (!job) return '복구 대안을 계산하는 중입니다. 잠시 뒤 다시 열어주세요.';
        return job.state === 'SUCCEEDED'
            ? '복구 대안을 계산했습니다.'
            : '복구 대안을 계산하지 못했습니다. 현장 확인/수동 대응으로 진행해주세요.';
    },

    /** EXT02 장비 등록. 종류는 모델명으로 남기고 기술 상태는 미확인으로 시작한다. */
    async addEquipment(r, action) {
        const a = expect(action, 'addEquipment');
        const input = {
            name: a.name,
            model: a.kind,
            ports: [],
            storageZoneId: zoneId(r, a.place) ?? null,
        };
        const result = await freshEquipment(r, 'register', input).register(input, r.eventRevision);
        return result.kind === 'done'
            ? `장비 '${a.name}'을(를) 등록했습니다.`
            : resultText(result, '장비 등록');
    },

    /** 장비 목록 파일의 행을 하나씩 등록한다. 등록마다 revision이 오르므로 받은 값으로 이어 간다. */
    async importEquipment(r, action) {
        const a = expect(action, 'importEquipment');
        const commands = equipmentCommands(r.session, r.client.scope);
        const inputs = a.items.map((item) => ({
            name: item.name,
            model: item.kind,
            ports: [] as { key: string; kind: string }[],
            storageZoneId: zoneId(r, item.place) ?? null,
        }));
        // 결과를 모르고 멈춘 가져오기를 다시 하면 멈춘 줄부터 같은 키로 이어 간다(앞 줄은 이미 등록됐다).
        const left = lastSent(commands, 'register');
        const from = commands.pending('register')
            ? Math.max(
                  inputs.findIndex((x) => JSON.stringify(x) === left),
                  0,
              )
            : 0;
        let revision = r.eventRevision;
        let done = 0;
        for (const input of inputs.slice(from)) {
            const result = await freshEquipment(r, 'register', input).register(input, revision);
            if (result.kind !== 'done') {
                const skipped = from ? `앞서 등록한 ${from}건을 건너뛰고 ` : '';
                return `${skipped}${done}건을 등록하고 멈췄습니다. ${resultText(result, '장비 등록')}`;
            }
            revision = result.data.eventRevision;
            done++;
        }
        return from
            ? `앞서 등록한 ${from}건을 건너뛰고 장비 ${done}건을 이어서 등록했습니다.`
            : `장비 ${done}건을 등록했습니다.`;
    },
};

export { UNKNOWN };
