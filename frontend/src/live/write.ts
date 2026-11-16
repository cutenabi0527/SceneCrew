import type { Workflow } from '../features/plan-workflow/client';
import { classifyFailure } from '../shared/api/failure';
import { UNKNOWN } from './runtime';

// 실서버 모드(#362)의 쓰기·불러오기 공통 처리. 화면 컴포넌트(LiveBridge)와 떼어 단위 시험한다.
/**
 * 권한이 없을 때(403·404)만 빈 목록으로 둔다. 503·네트워크 오류 같은 일시 실패는 그대로 던져
 * 불러오기 전체를 실패로 보고 직전 화면을 유지한다(빈 목록으로 덮지 않는다).
 */
export function emptyIfDenied<T>(error: unknown): T[] {
    if (classifyFailure(error).kind === 'access-denied') return [];
    throw error;
}

/**
 * 쓰기 하나를 처리하고 화면에 보일 문구를 돌려준다. 서버 처리 결과와 화면 갱신 실패를 섞지 않는다:
 * 처리 뒤 다시 읽기만 실패하면 처리 결과 문구에 '화면을 새로 고치지 못했다'를 덧붙인다.
 */
export async function runWrite(
    write: () => Promise<string>,
    reload: () => Promise<void>,
): Promise<string> {
    let message: string;
    try {
        message = await write();
    } catch (error) {
        await reload().catch(() => undefined);
        return failureText(error);
    }
    try {
        await reload();
        return message;
    } catch {
        return `${message} 다만 화면을 새로 고치지 못했습니다. 잠시 뒤 다시 불러옵니다.`;
    }
}

function failureText(error: unknown): string {
    const failure = classifyFailure(error);
    if (failure.kind === 'version-conflict')
        return '그사이 다른 변경이 있어 반영하지 못했습니다. 최신 내용으로 다시 불러왔습니다.';
    if (failure.kind === 'access-denied') return '이 작업을 할 권한이 없습니다.';
    return '서버에 반영하지 못했습니다. 잠시 뒤 다시 시도해주세요.';
}

/**
 * 045 수동 최초 배정. 묶음 전체를 같은 그룹 팀원에게 처음 맡기고, 서버가 첫 배정 알림을 만든다.
 * ok는 배정이 확인됐을 때만 true다. 결과를 모르면(응답 유실·서버 오류) 단정하지 않고 확인 안내를 낸다.
 */
export async function assignBundle(
    client: Workflow,
    bundleKey: string,
    memberId: string,
    revision: string,
): Promise<{ ok: boolean; text: string }> {
    const key = crypto.randomUUID();
    const result = await client.session.api.command(
        client.scope,
        {
            operation: `initial-assignment:${key}`,
            intentId: key,
            method: 'POST',
            path: `/events/${client.scope.eventId}/initial-assignments`,
            body: { bundleKey, memberId, expectedEventRevision: revision },
            recovery: 'same-key',
        },
        (d) => d,
    );
    if (result.status === 'succeeded')
        return { ok: true, text: '배정했습니다. 팀원에게 첫 배정 알림이 갑니다.' };
    if (result.status === 'failed' && result.failure.intent === 'finished')
        return {
            ok: false,
            text: '배정하지 못했습니다. 이미 배정됐거나 그사이 내용이 바뀌었을 수 있어 최신 내용을 다시 불러왔습니다.',
        };
    return { ok: false, text: UNKNOWN };
}
