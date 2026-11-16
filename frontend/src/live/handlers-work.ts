import type { Action } from '../domain/reducer';
import type { EquipStep } from '../domain/types';
import { MemberDesk } from '../features/members/client';
import { CommandNeedsReview } from '../features/plan-workflow/client';
import { stateQueryResult } from '../features/response/client';
import { kstTime } from './convert';
import type { Handler, LiveRuntime } from './runtime';
import { UNKNOWN, resultText } from './runtime';

// 앱 4탭의 업무 수행(#373): 시작(052)·완료(053)·체크(054)·장비 수행 기록(EXT09).
// 운영진(W06): 그룹 이동·예비 지정(038), 본인 가용(039). 다른 팀원의 가용은 본인만 바꾼다(BE10).
// 화면 값은 직접 바꾸지 않고, 끝나면 LiveBridge가 서버에서 다시 읽어 그린다.

type Of<T extends Action['type']> = Extract<Action, { type: T }>;
function expect<T extends Action['type']>(action: Action, type: T): Of<T> {
    if (action.type !== type) throw new Error('잘못된 액션');
    return action as Of<T>;
}

const NOT_FOUND = '업무를 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';

const apiTask = (r: LiveRuntime, id: string) => r.tasks.find((t) => t.id === id);

const STEP_OF: Record<string, EquipStep> = {
    HANDOVER: 'pickup',
    INSTALLATION: 'install',
    RETEST: 'retest',
};

/**
 * 업무 시작·완료·체크 앞에 남은 업무 의도를 버린다. 결과 모름 뒤 화면은 서버에서 다시 읽은 지금 상태를 보이고,
 * 서버는 중복 시작·완료를 상태로 거절하므로 새 의도로 보내도 안전하다. 남겨 두면 그 세션의 업무 명령이 모두 막힌다.
 * 장비 기록의 남은 의도는 버리지 않는다(같은 키로 다시 보내야 기록이 겹치지 않는다). 그 단계를 다시 누르게 안내한다.
 */
function settleTaskIntents(r: LiveRuntime): string | null {
    const pending = r.client.pendingOperations();
    for (const op of pending) if (op.startsWith('task:')) r.client.discardIntent(op);
    const equipment = pending.find((op) => op.startsWith('check:'));
    return equipment ? pendingEquipmentText(equipment) : null;
}

/** 결과 모름으로 남은 장비 기록(check:업무:장비:단계)을 마무리하라는 안내 */
function pendingEquipmentText(op: string) {
    const step = STEP_OF[op.split(':')[3] ?? ''];
    return `앞서 보낸 장비 ${step ? `${STEP_LABEL[step]} ` : ''}기록의 결과를 아직 확인하지 못했습니다. 그 기록을 다시 눌러 마무리해주세요.`;
}

/** 업무 명령 실패 안내. 서버가 확정 거절했는지, 결과를 모르는지 구별한다(결과를 모르면 다시 보내지 않는다). */
function commandText(error: unknown, step: string): string {
    if (error instanceof CommandNeedsReview && error.outcome.status === 'failed') {
        const f = error.outcome.failure;
        if (f.kind === 'version-conflict')
            return `${step} 중 그사이 다른 변경이 있어 반영하지 못했습니다. 최신 내용으로 다시 불러왔습니다.`;
        if (f.intent === 'finished')
            return `${step}이 거절됐습니다. 업무 상태와 필수 체크를 확인해주세요.`;
    }
    if (error instanceof CommandNeedsReview) return UNKNOWN;
    throw error;
}

const STEP_KIND: Record<EquipStep, 'HANDOVER' | 'INSTALLATION' | 'RETEST'> = {
    pickup: 'HANDOVER',
    install: 'INSTALLATION',
    retest: 'RETEST',
};
const STEP_LABEL: Record<EquipStep, string> = { pickup: '인수', install: '설치', retest: '재시험' };

/** 039 본인 가용. 다른 팀원의 가용은 바꿀 수 없다(BE10). */
async function availability(r: LiveRuntime, available: boolean) {
    const { eventId, memberId } = r.client.scope;
    const result = stateQueryResult(
        await r.session.availability(eventId, memberId, available, r.eventRevision),
    );
    if (result.kind === 'done')
        return available ? '지금 투입 가능으로 바꿨습니다.' : '잠시 투입 불가로 바꿨습니다.';
    return resultText(result, '가용 변경');
}

const CHIEF_TARGET = '총괄의 그룹/예비는 총괄 인계로만 바꿉니다.';

export const WORK_HANDLERS: Partial<Record<Action['type'], Handler>> = {
    /** 채팅 보내기(WebSocket SEND). 저장되면 연결이 받은 메시지로 바뀐다. 결과를 모르면 '확인 중'으로 남는다. */
    async send(r, action) {
        const { room, text } = expect(action, 'send');
        const connection = r.chat?.current;
        if (!connection) return '채팅에 연결되지 않았습니다. 잠시 뒤 다시 시도해주세요.';
        const ok = connection.send(room === 'ALL' ? null : room, text);
        return ok ? '' : '지금은 보낼 수 없습니다. 채팅 연결 상태를 확인해주세요.';
    },

    async taskStart(r, action) {
        const { id } = expect(action, 'taskStart');
        const t = apiTask(r, id);
        if (!t) return NOT_FOUND;
        const blocked = settleTaskIntents(r);
        if (blocked) return blocked;
        try {
            await r.client.perform(t, 'start', r.eventRevision);
            return '업무를 시작했습니다.';
        } catch (error) {
            return commandText(error, '업무 시작');
        }
    },

    async taskDone(r, action) {
        const { id } = expect(action, 'taskDone');
        const t = apiTask(r, id);
        if (!t) return NOT_FOUND;
        const blocked = settleTaskIntents(r);
        if (blocked) return blocked;
        try {
            await r.client.perform(t, 'complete', r.eventRevision);
            return '업무를 완료했습니다.';
        } catch (error) {
            return commandText(error, '업무 완료');
        }
    },

    /** 054는 체크 항목 전체를 보낸다. 누른 항목만 뒤집는다. */
    async toggleCheck(r, action) {
        const { id, index } = expect(action, 'toggleCheck');
        const t = apiTask(r, id);
        const item = t?.checklist[index];
        if (!t || !item) return NOT_FOUND;
        const blocked = settleTaskIntents(r);
        if (blocked) return blocked;
        const checks = t.checklist.map((c) => ({
            key: c.key,
            checked: c.key === item.key ? !c.checked : c.checked,
        }));
        try {
            await r.client.perform(t, 'checklist', r.eventRevision, checks);
            return '';
        } catch (error) {
            return commandText(error, '체크 저장');
        }
    },

    /**
     * EXT09 장비 수행 기록. 업무에 묶인 장비마다 같은 단계를 기록한다. 기록은 서버에서 다시 읽을 수 없어
     * 성공한 단계를 이 화면 실행 동안 기억한다(새로고침하면 다시 '기록 전'으로 보인다).
     */
    async equipmentRecord(r, action) {
        const { taskId, step } = expect(action, 'equipmentRecord');
        const detail = r.ops.taskDetails?.get(taskId);
        const equipment = detail?.equipment ?? [];
        if (!detail || !equipment.length)
            return '장비 지시를 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        // 다른 단계·업무의 장비 기록이 결과 모름으로 남았으면 그것부터 마무리한다(같은 키로 다시 보내야 한다)
        const kind = STEP_KIND[step];
        const mine = (op: string) => op.startsWith(`check:${taskId}:`) && op.endsWith(`:${kind}`);
        const other = r.client
            .pendingOperations()
            .find((op) => op.startsWith('check:') && !mine(op));
        if (other) return pendingEquipmentText(other);
        for (const op of r.client.pendingOperations())
            if (op.startsWith('task:')) r.client.discardIntent(op);
        // 이미 서버가 받은 장비는 건너뛴다. 결과 모름인 장비는 같은 키로 다시 보낸다
        const progressKey = `${taskId}:${step}`;
        const progress = r.equipmentProgress;
        const done = progress.get(progressKey) ?? new Set<string>();
        let revision = r.eventRevision;
        try {
            for (const e of equipment) {
                if (done.has(e.assetId)) continue;
                const receipt = await r.client.equipmentCheck(
                    detail,
                    { assetId: e.assetId, kind, passed: true },
                    revision,
                );
                done.add(e.assetId);
                progress.set(progressKey, done);
                revision = receipt.eventRevision;
            }
        } catch (error) {
            return commandText(error, `${STEP_LABEL[step]} 기록`);
        }
        progress.delete(progressKey);
        r.equipmentRecords.set(taskId, {
            ...(r.equipmentRecords.get(taskId) ?? {}),
            [step]: kstTime(new Date().toISOString()),
        });
        return `${STEP_LABEL[step]}을 기록했습니다.`;
    },
    async setAvailable(r, action) {
        return availability(r, expect(action, 'setAvailable').available);
    },

    /** 038 그룹 이동·예비 지정(총괄). 가용은 본인 것만 039로 바꾼다. */
    async memberManage(r, action, state) {
        const { memberId, patch } = expect(action, 'memberManage');
        if (patch.available !== undefined) {
            if (memberId !== r.client.scope.memberId)
                return '다른 팀원의 가용 여부는 본인만 바꿀 수 있습니다.';
            return availability(r, patch.available);
        }
        if (state.data.members.find((m) => m.id === memberId)?.role === 'CHIEF')
            return CHIEF_TARGET;
        const changes = {
            ...(patch.groupId !== undefined && { workGroupId: patch.groupId }),
            ...(patch.isReserve !== undefined && { isReserve: patch.isReserve }),
        };
        const result = await new MemberDesk(r.session, r.client.scope).update(
            memberId,
            changes,
            r.eventRevision,
        );
        if (result.kind === 'done') return '운영진 변경을 반영했습니다.';
        if (result.kind === 'rejected' && result.failure.code === 'INVALID_REFERENCE')
            return '고른 그룹을 지금은 쓸 수 없습니다. 최신 내용을 다시 불러왔습니다.';
        if (result.kind === 'rejected' && result.failure.code === 'INVALID_STATE')
            return '진행 중 업무가 있거나 지금 상태에서는 바꿀 수 없습니다. 최신 내용을 다시 불러왔습니다.';
        return resultText(result, '운영진 변경');
    },
};
