import { type IntentKeyStore } from '../scope/intent-keys';
import { type Scope, ScopeDisposedError, runInScope } from '../scope/scope';
import type { ScopeManager } from '../scope/scope-manager';
import {
    ApiError,
    createApiTransport,
    createBinaryTransport,
    createStreamTransport,
    type BinaryResult,
} from './client';
import { ContractViolationError, asRecord, asString, asUuid } from './contract';
import { type Failure, classifyFailure } from './failure';
import type { SessionGate } from './session-gate';

// 공통 API 소비 경계. 195의 범위(계정·행사·참여 회차)와 기존 HTTP 클라이언트를 연결한다.
//  - 조회(get): 범위가 끝나면 요청이 취소되고 늦은 응답은 전달되지 않는다. 401은 GET에 한해 한 번만 복구한다.
//  - 명령(command): 의도마다 Idempotency-Key를 보관한다. 재전송은 멱등 보장 API만 허용한다. 자동 재전송·새 키 자동 발급·오프라인 보류
//    자동 실행은 없다. 결과는 성공/접수/실패/만료로 구별해서 돌려준다.
// 채널별 인증 전송은 channel-transport를 주입한다. 세션/토큰 저장과 실시간 연결은 각 호출자가 맡는다.

export interface ApiOptions {
    base: string;
    // 합의된 인증 transport가 쿠키(WEB)나 Bearer(APP)를 붙인 fetch를 주입한다. 기본은 전역 fetch.
    transport?: typeof fetch;
    scopes: ScopeManager;
    // 있으면 GET의 401을 한 번 복구한다. 명령은 복구해도 다시 보내지 않는다.
    session?: SessionGate;
    // 403/404를 받았을 때(숨겨진 자원이거나 권한 회수). 호출자가 현재 참여 정보를 다시 조회해
    // setParticipation/leaveParticipation으로 이어 간다. 404는 단순히 없는 자원일 수도 있어
    // 자동으로 행사를 떠나지 않는다.
    onAccessDenied?: (scope: Scope, failure: Failure) => void;
}

export type CommandMethod = 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface CommandSpec {
    // 같은 계정·행사 안에서 명령 종류를 구별하는 이름
    operation: string;
    // 사용자가 새 의도를 시작할 때 호출자가 만든다. 같은 의도의 재전송은 같은 값을 쓴다.
    intentId: string;
    method: CommandMethod;
    path: string;
    // JSON 본문. 기대 revision·previewHash 같은 값은 엔드포인트 계약(BE07)이 정한 필드로 호출자가
    // 넣고 의도가 끝날 때까지 바꾸지 않는다. BIGINT는 문자열로 넣는다.
    body?: unknown;
    // 파일 내용·순서·이름·MIME을 포함한 호출자의 지문. 원래 FormData를 같은 의도 동안 유지한다.
    form?: { data: FormData; fingerprint: string };
    response?: 'data' | 'job';
    // BE가 멱등 처리를 보장하는 API만 same-key를 쓴다. 비대상·미확정은 state-query(기본값).
    // 결과를 모르는 명령을 다시 보내기 전에 현재 상태를 조회하도록 호출자를 구분한다(V7-D16).
    recovery?: 'same-key' | 'state-query';
    // API의 x-idempotency에 맞춘다. 비대상은 헤더와 재전송을 사용하지 않는다.
    idempotency?: 'required' | 'none';
    // 계약이 정한 추가 요청 헤더(예: 020 X-Device-Proof). 비밀값일 수 있어 키 지문에는 넣지 않는다.
    headers?: Record<string, string>;
}

export type CommandOutcome<T> =
    // 처리 결과를 받았다(200/201/204). 키는 정리했다.
    | { status: 'succeeded'; data: T | undefined; resumed: boolean }
    // 비동기 Job을 접수했다(202 + jobId). 접수 성공과 작업 완료는 별개이며 키는 정리했다.
    | { status: 'accepted'; jobId: string; statusUrl: string; data: unknown; resumed: boolean }
    // 실패. pending이면 키를 유지한다. same-key로 명시한 API만 같은 의도를 재전송할 수 있다.
    | { status: 'failed'; failure: Failure; resumed: boolean }
    // 비멱등·미확정 명령의 결과 확인이 먼저다. 요청을 보내지 않았고 기존 키는 유지했다.
    | { status: 'reconcile' }
    // 24시간이 지났다. 요청을 보내지 않았고 새 키도 만들지 않았다. 현재 상태를 조회해 결과를 확인한 뒤
    // intentKeys.release로 정리한다.
    | { status: 'expired' };

// 여러 signal 중 하나라도 끝나면 끝나는 signal. 범위의 signal은 계정 세션 내내 살아 있으므로
// 요청이 끝나면 반드시 dispose로 리스너를 제거한다(제거하지 않으면 요청마다 리스너가 쌓인다).
function anySignal(signals: (AbortSignal | undefined)[]) {
    const controller = new AbortController();
    const cleanups: (() => void)[] = [];
    const dispose = () => {
        cleanups.splice(0).forEach((cleanup) => cleanup());
    };
    for (const signal of signals) {
        if (!signal) continue;
        if (signal.aborted) {
            controller.abort(signal.reason);
            dispose();
            break;
        }
        const listener = () => {
            controller.abort(signal.reason);
            dispose();
        };
        signal.addEventListener('abort', listener, { once: true });
        cleanups.push(() => signal.removeEventListener('abort', listener));
    }
    return { signal: controller.signal, dispose };
}

export function createApi(options: ApiOptions) {
    const send = createApiTransport(options.base, options.transport);
    const sendBinary = createBinaryTransport(options.base, options.transport);
    const sendStream = createStreamTransport(options.base, options.transport);
    const intentKeys: IntentKeyStore = options.scopes.intentKeys;

    // 조회. parse는 응답에서 필요한 필드만 읽고 계약과 다르면 ContractViolationError를 던진다.
    async function get<T>(
        scope: Scope,
        path: string,
        parse: (data: unknown) => T,
        init: { signal?: AbortSignal } = {},
    ): Promise<T> {
        const attempt = () =>
            runInScope(scope, async (scopeSignal) => {
                const { signal, dispose } = anySignal([scopeSignal, init.signal]);
                try {
                    const { data } = await send<unknown>(path, { method: 'GET', signal });
                    return parse(data);
                } finally {
                    dispose();
                }
            });
        try {
            return await attempt();
        } catch (error) {
            if (error instanceof ApiError && error.status === 401 && options.session) {
                // 같은 시점의 401은 하나의 복구를 공유한다. 복구된 GET은 한 번만 다시 보낸다.
                const recovery = await options.session.recover();
                if (recovery === 'refreshed') return handleDenied(scope, attempt);
                throw error;
            }
            return handleDeniedError(scope, error);
        }
    }

    // 이미지 조회. 조회와 같이 범위가 끝나면 취소되고, 401은 한 번만 복구하며, 403/404는 권한 회수로 알린다.
    async function getBinary(
        scope: Scope,
        path: string,
        accept: string,
        init: { signal?: AbortSignal } = {},
    ): Promise<BinaryResult> {
        const attempt = () =>
            runInScope(scope, async (scopeSignal) => {
                const { signal, dispose } = anySignal([scopeSignal, init.signal]);
                try {
                    return await sendBinary(path, accept, { method: 'GET', signal });
                } finally {
                    dispose();
                }
            });
        try {
            return await attempt();
        } catch (error) {
            if (error instanceof ApiError && error.status === 401 && options.session) {
                const recovery = await options.session.recover();
                if (recovery === 'refreshed') return handleDenied(scope, attempt);
                throw error;
            }
            return handleDeniedError(scope, error);
        }
    }

    /**
     * 서버 이벤트 스트림(SSE). 범위가 끝나거나 호출자가 취소하면 읽는 중인 본문도 끊긴다.
     * 401은 한 번 복구해 다시 열고, 403/404는 접근 거절로 알린다. 끊긴 뒤 다시 여는 것은 호출자가 정한다.
     */
    async function openStream(
        scope: Scope,
        path: string,
        init: { signal?: AbortSignal } = {},
    ): Promise<{ body: ReadableStream<Uint8Array>; release: () => void }> {
        const attempt = async () => {
            if (!scope.isCurrent()) throw new ScopeDisposedError();
            // 본문을 읽는 동안에도 취소가 닿아야 하므로 읽기를 마칠 때(release)까지 합친 signal을 유지한다
            const { signal, dispose } = anySignal([scope.signal, init.signal]);
            try {
                const body = await sendStream(path, { method: 'GET', signal });
                if (!scope.isCurrent()) {
                    await body.cancel().catch(() => undefined);
                    throw new ScopeDisposedError();
                }
                return { body, release: dispose };
            } catch (error) {
                dispose();
                throw error;
            }
        };
        try {
            return await attempt();
        } catch (error) {
            if (error instanceof ApiError && error.status === 401 && options.session) {
                const recovery = await options.session.recover();
                if (recovery === 'refreshed') return handleDenied(scope, attempt);
                throw error;
            }
            return handleDeniedError(scope, error);
        }
    }

    // 바이너리 본문 POST(카메라 웹 연결 프레임 W4, #396). command는 202를 AI Job 접수로 읽고 JSON·FormData만 보내므로
    // 따로 둔다. 요청 키·의도 보관이 없다: 프레임은 같은 순번을 다시 보내지 않고 다음 차례에 새 사진을 보낸다.
    // 401은 한 번만 복구해 같은 본문을 다시 보낸다(서버가 받지 않은 요청이므로 중복이 아니다).
    async function postBlob<T>(
        scope: Scope,
        path: string,
        body: Blob,
        headers: Record<string, string>,
        parse: (data: unknown) => T,
        init: { signal?: AbortSignal } = {},
    ): Promise<T> {
        const attempt = () =>
            runInScope(scope, async (scopeSignal) => {
                const { signal, dispose } = anySignal([scopeSignal, init.signal]);
                try {
                    const { data } = await send<unknown>(path, {
                        method: 'POST',
                        headers: { 'Content-Type': body.type, ...headers },
                        body,
                        signal,
                    });
                    return parse(data);
                } finally {
                    dispose();
                }
            });
        try {
            return await attempt();
        } catch (error) {
            if (error instanceof ApiError && error.status === 401 && options.session) {
                const recovery = await options.session.recover();
                if (recovery === 'refreshed') return handleDenied(scope, attempt);
                throw error;
            }
            return handleDeniedError(scope, error);
        }
    }

    async function handleDenied<T>(scope: Scope, attempt: () => Promise<T>): Promise<T> {
        try {
            return await attempt();
        } catch (error) {
            return handleDeniedError(scope, error);
        }
    }

    function handleDeniedError(scope: Scope, error: unknown): never {
        if (!(error instanceof ScopeDisposedError)) {
            const failure = classifyFailure(error);
            if (failure.kind === 'access-denied') options.onAccessDenied?.(scope, failure);
        }
        throw error;
    }

    async function command<T = unknown>(
        scope: Scope,
        spec: CommandSpec,
        parse: (data: unknown) => T = (data) => data as T,
    ): Promise<CommandOutcome<T>> {
        if (spec.body !== undefined && spec.form)
            throw new TypeError('JSON과 파일 본문을 함께 보낼 수 없습니다.');
        const bodyText = spec.body === undefined ? undefined : JSON.stringify(spec.body);
        // 같은 의도에서 요청 내용이 바뀌면 IntentConflictError로 거절된다(새 의도로 시작해야 한다).
        const recovery =
            spec.idempotency === 'none' ? 'state-query' : (spec.recovery ?? 'state-query');
        const claim = intentKeys.claim(
            scope,
            spec.operation,
            spec.intentId,
            JSON.stringify([
                spec.method,
                spec.path,
                bodyText ?? spec.form?.fingerprint ?? null,
                recovery,
                spec.idempotency ?? 'required',
                ...(spec.response ? [spec.response] : []),
            ]),
        );
        if (claim.status === 'expired') return { status: 'expired' };
        if (claim.status === 'reconcile') return { status: 'reconcile' };
        if (claim.resumed && recovery === 'state-query') return { status: 'reconcile' };

        const headers: Record<string, string> = { ...spec.headers };
        if (spec.idempotency !== 'none') headers['Idempotency-Key'] = claim.key;
        if (bodyText !== undefined) headers['Content-Type'] = 'application/json';

        let outcome: CommandOutcome<T>;
        try {
            // 명령에는 취소 signal을 넘기지 않는다. 전송한 뒤 끊으면 처리 여부를 알 수 없다.
            // 범위가 끝난 뒤의 결과는 runInScope가 ScopeDisposedError로 바꿔 새 화면에 닿지 않게 한다.
            const result = await runInScope(scope, () =>
                send<unknown>(spec.path, {
                    method: spec.method,
                    headers,
                    body: spec.form?.data ?? bodyText,
                }),
            );
            outcome = interpret(result.status, result.data, parse, claim.resumed, spec.response);
        } catch (error) {
            if (error instanceof ScopeDisposedError) throw error;
            outcome = { status: 'failed', failure: classifyFailure(error), resumed: claim.resumed };
            if (outcome.failure.kind === 'access-denied') {
                options.onAccessDenied?.(scope, outcome.failure);
            }
        }

        // 결과가 확정되면 키를 정리한다. 확정되지 않았으면 상태 확인·명시 복구를 위해 유지한다.
        if (
            outcome.status === 'failed' &&
            (outcome.failure.code === 'IDEMPOTENCY_OUTCOME_UNKNOWN' ||
                outcome.failure.kind === 'result-expired' ||
                outcome.failure.kind === 'text-not-retained')
        ) {
            intentKeys.requireQuery(
                scope,
                spec.operation,
                spec.intentId,
                outcome.failure.kind === 'result-expired',
            );
        }
        if (outcome.status !== 'failed' || outcome.failure.intent === 'finished') {
            intentKeys.release(scope, spec.operation, spec.intentId);
        }
        return outcome;
    }

    return { get, getBinary, postBlob, openStream, command };
}

function interpret<T>(
    status: number,
    data: unknown,
    parse: (data: unknown) => T,
    resumed: boolean,
    response?: 'data' | 'job',
): CommandOutcome<T> {
    try {
        if (
            (response === 'data' && (status === 202 || data === undefined)) ||
            (response === 'job' && status !== 202)
        )
            throw new ContractViolationError(
                '응답.status',
                '요청한 완료/접수 형식과 달라 현재 상태 확인이 필요합니다.',
            );
        if (status === 202) {
            // 업무 명령의202는Job 접수다. 로그인 전 일반 인증202는auth-receipts에서 처리한다.
            const body = asRecord(data, '응답');
            const jobId = asUuid(body['jobId'], '응답.jobId');
            const statusUrl = asString(body['statusUrl'], '응답.statusUrl');
            if (
                !statusUrl.startsWith('/api/v1/ai-jobs/') ||
                statusUrl.toLowerCase() !== `/api/v1/ai-jobs/${jobId}`.toLowerCase()
            ) {
                throw new ContractViolationError(
                    '응답.statusUrl',
                    '접수한 Job의 조회 경로여야 합니다.',
                );
            }
            return { status: 'accepted', jobId, statusUrl, data, resumed };
        }
        return { status: 'succeeded', data: data === undefined ? undefined : parse(data), resumed };
    } catch (error) {
        if (error instanceof ContractViolationError) {
            // 2xx를 받았지만 본문이 계약과 다르다. 실패로 단정하지 않고 같은 키로 상태를 확인한다.
            return { status: 'failed', failure: classifyFailure(error), resumed };
        }
        throw error;
    }
}
