import type { Action } from '../domain/reducer';
import type { DemoState, EquipStep } from '../domain/types';
import type { IdentitySession } from '../features/identity/session';
import type { Workflow } from '../features/plan-workflow/client';
import type { Plan, Task as ApiTask } from '../features/plan-workflow/contract';
import type { ChatConnection } from '../features/live-chat/connection';
import type { OpsRaw } from './ops';

/** 실서버 모드의 현재 연결과 마지막으로 읽은 원본 응답. 쓰기 핸들러가 id·revision을 여기서 찾는다. */
export interface LiveRuntime {
    session: IdentitySession;
    client: Workflow;
    eventRevision: string;
    chief: boolean;
    plan: Plan | null;
    tasks: readonly ApiTask[];
    ops: OpsRaw;
    /** 변경안 id → 화면 연산 순서별 서버 연산 번호 */
    opIndex: Map<string, number[]>;
    /** 이 화면에서 복구 계산을 돌린 이슈(다시 읽을 때 결과를 함께 읽는다). 불러오기를 거듭해도 이어 간다. */
    recoveryIssues: Set<string>;
    /** 이 화면이 접수한 보고 분석 작업(이슈 id → 작업 id, #358). 불러오기를 거듭해도 이어 간다. */
    analysisJobs: Map<string, string>;
    /** 이 화면에서 남긴 장비 수행 기록(#373). 서버에 읽기 API가 없어 화면 실행 동안만 기억한다 */
    equipmentRecords: Map<string, Partial<Record<EquipStep, string>>>;
    /** 장비 기록 중 이미 서버가 받은 장비(업무:단계 → 장비 id). 결과 모름 뒤 같은 단계를 다시 누르면 남은 장비부터 보낸다 */
    equipmentProgress: Map<string, Set<string>>;
    /** 실서버 채팅 연결(#373). 연결 전이면 비어 있다 */
    chat?: { current: ChatConnection | null };
}

export type Handler = (runtime: LiveRuntime, action: Action, state: DemoState) => Promise<string>;

export const UNKNOWN = '처리 결과를 확인하지 못했습니다. 최신 내용을 다시 불러왔습니다.';

/** 결과를 끝까지 받지 못했을 때의 안내 */
export function resultText(result: { kind: string }, step: string) {
    if (result.kind === 'conflict')
        return `${step} 중 그사이 다른 변경이 있어 멈췄습니다. 최신 내용으로 다시 불러왔습니다.`;
    if (result.kind === 'rejected')
        return `${step}이 거절됐습니다. 입력과 현재 상태를 확인해주세요.`;
    if (result.kind === 'retry')
        return `${step}을 서버에 전하지 못했습니다. 잠시 뒤 다시 시도해주세요.`;
    return UNKNOWN;
}

/** 접수한 AI 작업이 끝날 때까지 기다린다. 오래 걸리면 화면을 막지 않고 그만 기다린다. */
export async function waitJob<J extends { state: string }>(
    read: () => Promise<J>,
    { timeoutMs = 60_000, intervalMs = 1_500 } = {},
): Promise<J | null> {
    const until = Date.now() + timeoutMs;
    for (;;) {
        const job = await read();
        if (job.state === 'SUCCEEDED' || job.state === 'FAILED') return job;
        if (Date.now() > until) return null;
        await new Promise((r) => setTimeout(r, intervalMs));
    }
}
