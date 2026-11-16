import { createApi, type CommandSpec } from '../../shared/api/api';
import { createAuthReceipts } from '../../shared/api/auth-receipts';
import { createChannelTransport } from '../../shared/api/channel-transport';
import { ApiError, createApiTransport } from '../../shared/api/client';
import { ContractViolationError, asRecord } from '../../shared/api/contract';
import { classifyFailure } from '../../shared/api/failure';
import { SessionGate } from '../../shared/api/session-gate';
import { ScopeDisposedError, type Scope } from '../../shared/scope/scope';
import type { ScopeManager } from '../../shared/scope/scope-manager';
import { RefreshNotAppliedError, type RefreshStore } from './refresh-store';
import {
    CHALLENGE_METHOD_PARAM,
    CHALLENGE_PARAM,
    VERIFIER_FIELD,
    type OAuthProofStore,
} from './android-oauth';
import {
    account,
    credentials,
    event,
    eventPage,
    inviteOptions,
    joinResult,
    participationChanged,
    consents,
    oauthPending,
    type Account,
    type Channel,
    type Consents,
    type OAuthPending,
    type Participation,
    type Tokens,
} from './contract';

/** 인증 결과. 소셜 교환(011 LOGIN)만 미연결 계정의 가입 계속(OAuthPending)을 돌려줄 수 있다(#379). */
export type AuthOutcome =
    { kind: 'authenticated' } | { kind: 'signup-required'; pending: OAuthPending };

export interface SessionState {
    account: Account | null;
    ready: boolean;
    notice?: string;
}
function attemptKey() {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return btoa(String.fromCharCode(...bytes))
        .replaceAll('+', '-')
        .replaceAll('/', '_')
        .replaceAll('=', '');
}

export type SocialProvider = 'GOOGLE' | 'KAKAO' | 'NAVER';

/**
 * 결과가 도착하기 전에 새 소셜 로그인을 시작했다(#388 마지막 시도만 유효).
 * 이전 시도의 결과로는 Refresh 저장·세션 전환·가입 계속을 하지 않는다.
 */
export class OAuthAttemptReplacedError extends Error {
    constructor() {
        super('더 최근에 시작한 소셜 로그인이 있습니다.');
        this.name = 'OAuthAttemptReplacedError';
    }
}

/** Android 소셜 교환에 쓸 시작 증명이 없다(만료·다른 기기·앱 데이터 삭제). 소셜 로그인을 다시 시작해야 한다. */
export class OAuthProofMissingError extends Error {
    constructor() {
        super('소셜 로그인을 시작한 기록이 없습니다.');
        this.name = 'OAuthProofMissingError';
    }
}
/** 제공자별 공식 인증 호스트(backend HttpOAuthProviderClient와 같음). 다른 곳으로는 이동하지 않는다. */
/** 로그아웃 직전 작업을 기다리는 최대 시간 */
const LOGOUT_HOOK_MS = 3000;

export const SOCIAL_HOSTS: Record<SocialProvider, string> = {
    GOOGLE: 'accounts.google.com',
    KAKAO: 'kauth.kakao.com',
    NAVER: 'nid.naver.com',
};

// Access는 메모리에만 두고 native Android의 Refresh만 별도 암호화 저장소에 보관한다.
export class IdentitySession {
    private state: SessionState = { account: null, ready: false };
    private tokens: Tokens | null = null;
    private generation = 0;
    private participationEpoch = 0;
    private authBusy = false;
    private authDone: Promise<void> | null = null;
    private finishAuth: (() => void) | null = null;
    private boot: Promise<void> | null = null;
    private refreshWork: Promise<'refreshed' | 'login-required'> | null = null;
    private restoringAccountId: string | null = null;
    private readonly refreshStore?: RefreshStore;
    private readonly proofStore?: OAuthProofStore;
    /** 소셜 로그인 시작 회차. 시작할 때마다 늘어나며, 교환 결과는 같은 회차일 때만 반영한다(#388). */
    private oauthAttempt = 0;
    /** 로그인의 정식 칸 확정 구간. 이 동안 새 소셜 로그인 시작은 기다린다(#388). */
    private committing: Promise<void> | null = null;
    private listeners = new Set<() => void>();
    private intents = new Map<string, CommandSpec>();
    readonly transport: typeof fetch;
    readonly send: ReturnType<typeof createApiTransport>;
    readonly receipts: ReturnType<typeof createAuthReceipts>;
    readonly gate: SessionGate;
    readonly api: ReturnType<typeof createApi>;

    constructor(
        readonly channel: Channel,
        readonly base: string,
        readonly scopes: ScopeManager,
        fetcher: typeof fetch = fetch,
        refreshStore?: RefreshStore,
        proofStore?: OAuthProofStore,
    ) {
        this.refreshStore = channel === 'ANDROID' ? refreshStore : undefined;
        this.proofStore = channel === 'ANDROID' ? proofStore : undefined;
        this.transport = createChannelTransport(
            channel,
            fetcher,
            () => this.tokens?.accessToken ?? null,
        );
        this.send = createApiTransport(base, this.transport);
        this.receipts = createAuthReceipts(base, this.transport);
        this.gate = new SessionGate(() => this.refresh());
        this.api = createApi({ base, transport: this.transport, scopes, session: this.gate });
    }
    /** Android 실시간 연결의 AUTH 프레임용 Access. WEB은 쿠키를 쓰므로 null이다. URL에 넣지 않는다. */
    liveAccessToken() {
        return this.channel === 'ANDROID' ? (this.tokens?.accessToken ?? null) : null;
    }
    snapshot = () => this.state;
    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    };
    private publish(accountValue: Account | null, ready = true) {
        this.state = { account: accountValue, ready };
        this.listeners.forEach((listener) => listener());
    }
    private assertGeneration(generation: number) {
        if (generation !== this.generation) throw new ScopeDisposedError();
    }
    private async clear() {
        this.generation++;
        this.tokens = null;
        this.intents.clear();
        this.scopes.setAccount(null);
        this.publish(null);
        try {
            await this.refreshStore?.clear();
        } catch {
            this.state = {
                ...this.state,
                notice: '기기 로그인 정보의 삭제를 확인하지 못했습니다. 다시 로그인해주세요.',
            };
            this.listeners.forEach((listener) => listener());
            throw new Error('기기 로그인 정보의 삭제를 확인하지 못했습니다.');
        }
    }
    bootstrap() {
        if (this.state.ready) return Promise.resolve();
        return (this.boot ??= this.restore());
    }
    private async restore() {
        const generation = this.generation;
        try {
            if (this.channel === 'ANDROID') {
                const saved = await this.refreshStore?.take();
                this.assertGeneration(generation);
                if (!saved) return;
                this.restoringAccountId = saved.accountId;
                this.tokens = { accessToken: '', refreshToken: saved.refreshToken };
                if ((await this.gate.recover()) !== 'refreshed') return;
            }
            let result;
            try {
                result = await this.send('/me');
            } catch (error) {
                if (
                    this.channel === 'ANDROID' ||
                    !(error instanceof ApiError) ||
                    error.status !== 401
                )
                    throw error;
                if ((await this.gate.recover()) !== 'refreshed') return;
                result = await this.send('/me');
            }
            this.assertGeneration(generation);
            const value = account(result.data);
            if (this.restoringAccountId && value.id !== this.restoringAccountId)
                throw new ContractViolationError(
                    'account.id',
                    '저장한 세션의 계정과 달라졌습니다.',
                );
            this.establish(value);
        } catch {
            if (this.channel === 'ANDROID' && generation === this.generation) {
                try {
                    await this.clear();
                } catch {
                    // clear가 이미 로컬 범위를 닫고 저장소 실패 안내를 남겼다.
                }
            }
            /* 복구 실패 시 성공한 계정으로 취급하지 않는다. 명시 로그인으로 이어진다. */
        } finally {
            this.restoringAccountId = null;
            if (generation === this.generation && !this.state.ready) this.publish(null);
        }
    }
    private establish(value: Account) {
        if (value.status !== 'ACTIVE')
            throw new ContractViolationError('account.status', '활성 계정이 필요합니다.');
        this.scopes.setAccount(value.id);
        this.publish(value);
    }
    private beginAuth() {
        if (this.authBusy) throw new Error('인증 처리 중입니다.');
        const previousRefresh = this.refreshWork;
        this.authBusy = true;
        this.authDone = new Promise<void>((resolve) => {
            this.finishAuth = resolve;
        });
        return previousRefresh;
    }
    private endAuth() {
        this.authBusy = false;
        this.finishAuth?.();
        this.finishAuth = null;
        this.authDone = null;
    }
    /** Android 소셜 로그인을 쓸 수 있는가(증명 저장소가 있는 Android 앱, #388) */
    get socialSupported() {
        return this.channel === 'WEB' || this.proofStore !== undefined;
    }
    /**
     * 소셜 로그인 시작(#285, BE 009). 목적은 LOGIN, 복귀는 HOME만 쓴다.
     * 받은 주소가 그 제공자의 공식 인증 주소일 때만 돌려준다.
     * WEB은 BE callback이 /login/oauth?ticket=… 으로, Android(#388)는 App Link /app/oauth?ticket=… 으로 돌려보낸다.
     * Android는 새 시작 증명을 만들어 challenge만 보낸다. 이전 시도의 증명은 이때 사라진다.
     */
    async oauthStart(provider: SocialProvider) {
        if (!this.socialSupported)
            throw new Error('앱에서는 아직 소셜 로그인을 지원하지 않습니다.');
        if (!(provider in SOCIAL_HOSTS))
            throw new TypeError('알 수 없는 소셜 로그인 제공자입니다.');
        // 이전 로그인이 정식 칸을 확정하는 중이면 끝난 뒤 시작한다(그 로그인은 이미 채택됐다).
        while (this.committing) await this.committing;
        // 증명을 바꾸기 전에 회차를 먼저 올려, 진행 중이던 이전 교환이 늦게 성공해도 반영되지 않게 한다.
        const isCurrent = this.currentAttempt(++this.oauthAttempt);
        const query = new URLSearchParams({ purpose: 'LOGIN', returnRoute: 'HOME' });
        if (this.proofStore) {
            query.set(CHALLENGE_PARAM, await this.proofStore.begin());
            query.set(CHALLENGE_METHOD_PARAM, 'S256');
        }
        const result = await this.send(`/auth/oauth/${provider}/authorize?${query}`);
        const url = providerUrl(provider, result);
        // 응답을 기다리는 동안 새 시도를 시작했으면 이 시도로는 브라우저를 열거나 이동 경로를 저장하지 않는다.
        if (!isCurrent()) throw new OAuthAttemptReplacedError();
        return url;
    }
    /**
     * 소셜 계정 연결 전 본인 재확인(#227, BE 014). 비밀번호가 맞으면 BE가 5분짜리 재확인 Cookie를 준다.
     * 값은 HttpOnly Cookie라 FE가 보관하지 않는다. WEB만 지원한다.
     */
    async reauthenticate(
        provider: SocialProvider,
        password: string,
        operation: 'LINK_IDENTITY' | 'UNLINK_IDENTITY' = 'LINK_IDENTITY',
    ) {
        if (this.channel !== 'WEB')
            throw new Error('앱에서는 아직 소셜 계정 연결을 지원하지 않습니다.');
        if (!(provider in SOCIAL_HOSTS))
            throw new TypeError('알 수 없는 소셜 로그인 제공자입니다.');
        const result = await this.send('/me/reauthenticate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password, operation, provider }),
        });
        if (result.status !== 200)
            throw new ContractViolationError('status', '재확인 응답 상태가 다릅니다.');
    }
    /**
     * 소셜 계정 연결 해제(#227, BE 016). 해제용 재확인이 먼저 필요하다. 마지막 로그인 수단이면 409다.
     * 지금 그 소셜 계정으로 로그인 중이었다면 이후 요청부터 로그인이 풀린다.
     */
    async unlink(provider: SocialProvider) {
        if (this.channel !== 'WEB')
            throw new Error('앱에서는 아직 소셜 계정 연결을 지원하지 않습니다.');
        if (!(provider in SOCIAL_HOSTS))
            throw new TypeError('알 수 없는 소셜 로그인 제공자입니다.');
        const result = await this.send(`/me/identities/${provider}`, {
            method: 'DELETE',
            headers: { 'Content-Type': 'application/json' },
            body: '{}',
        });
        if (result.status !== 204)
            throw new ContractViolationError('status', '해제 응답 상태가 다릅니다.');
    }
    /**
     * 소셜 계정 연결 시작(#227, BE 015). 돌려받은 제공자 주소로 이동하면 BE callback이 연결을 끝내고
     * /account/profile?link=…&result=… 로 돌려보낸다.
     */
    async linkStart(provider: SocialProvider) {
        if (this.channel !== 'WEB')
            throw new Error('앱에서는 아직 소셜 계정 연결을 지원하지 않습니다.');
        if (!(provider in SOCIAL_HOSTS))
            throw new TypeError('알 수 없는 소셜 로그인 제공자입니다.');
        const result = await this.send(`/me/identities/${provider}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{}',
        });
        return providerUrl(provider, result);
    }
    async authenticate(
        path: '/auth/login' | '/auth/verify-email' | '/auth/oauth/exchange',
        body: unknown,
        options: { allowSignup?: boolean; isCurrent?: () => boolean; queue?: boolean } = {},
    ): Promise<AuthOutcome> {
        // 복구를 인증 잠금 전에 기다린다. 복구 중 401의 회전은 진행 중인 인증이 끝나길 기다리므로,
        // 잠금을 잡은 채 복구를 기다리면 서로 기다리다 멈춘다(WEB 소셜 복귀 화면에서 드러남).
        await this.boot;
        // 소셜 교환(queue)은 진행 중인 인증(임시 값 정리 포함)이 끝날 때까지 줄을 선다(#388).
        // 깨어난 뒤 잠금을 잡기까지 기다림이 없어, 여러 교환이 동시에 깨어나도 하나씩만 들어간다.
        if (options.queue) while (this.authDone) await this.authDone;
        const previousRefresh = this.beginAuth();
        try {
            // 기존 회전 응답의 Cookie 적용이 끝난 뒤 새 인증을 보낸다.
            await previousRefresh;
            const generation = this.generation;
            const result = await this.send(path, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            this.assertGeneration(generation);
            if (result.status !== (path === '/auth/verify-email' ? 201 : 200))
                throw new ContractViolationError('status', '인증 성공 상태가 다릅니다.');
            const v = asRecord(result.data, 'login');
            // 응답을 기다리는 동안 새 소셜 로그인을 시작했으면 Refresh 저장·세션 전환 전에 멈춘다(#388).
            if (options.isCurrent && !options.isCurrent()) throw new OAuthAttemptReplacedError();
            // 가입 계속은 세션이 아니다. 기존 로그인 상태를 바꾸지 않고 화면에만 넘긴다.
            if (options.allowSignup) {
                const pending = oauthPending(v);
                if (pending) return { kind: 'signup-required', pending };
            }
            const nextAccount = account(v['account']);
            if (nextAccount.status !== 'ACTIVE')
                throw new ContractViolationError('account.status', '활성 계정이 필요합니다.');
            const tokens = credentials(v, this.channel, 'LoginResult');
            if (tokens && this.refreshStore) {
                // 새 로그인은 임시 칸에 두고, 채택할 때만 정식 칸으로 옮긴다(#388).
                // 앱이 어느 시점에 종료돼도 다음 실행은 정식 칸(기존 세션 또는 없음)만 복원한다.
                const stored = { accountId: nextAccount.id, refreshToken: tokens.refreshToken };
                try {
                    await this.refreshStore.stage(stored);
                    this.assertGeneration(generation);
                } catch {
                    await this.discardStaged();
                    // 기존 정상 세션과 정식 칸은 그대로 둔다(로그아웃으로 회차가 바뀌었으면 이미 정리됐다).
                    throw new Error(
                        '기기 로그인 정보를 안전하게 저장하지 못했습니다. 다시 로그인해주세요.',
                    );
                }
                // 채택 여부는 여기서 한 번만 정한다. 확인과 확정 시작 사이에 기다림이 없고,
                // 확정 중에 들어온 새 시작은 확정이 끝난 뒤 회차를 올린다(oauthStart).
                if (options.isCurrent && !options.isCurrent()) {
                    await this.discardStaged();
                    throw new OAuthAttemptReplacedError();
                }
                const committed = this.beginCommit();
                try {
                    await this.refreshStore.commit();
                    this.assertGeneration(generation);
                } catch (error) {
                    committed();
                    if (error instanceof RefreshNotAppliedError) {
                        // 정식 칸을 바꾸기 전에 실패했다. 기존 정상 세션은 그대로 둔다.
                        await this.discardStaged();
                        throw new Error(
                            '기기 로그인 정보를 안전하게 저장하지 못했습니다. 다시 로그인해주세요.',
                            { cause: error },
                        );
                    }
                    // 정식 칸 교체 결과를 알 수 없으므로 기기 로그인 정보를 모두 지운다.
                    await this.clear();
                    throw new Error(
                        '기기 로그인 정보를 안전하게 저장하지 못했습니다. 다시 로그인해주세요.',
                        { cause: error },
                    );
                }
                this.applyLogin(nextAccount, tokens);
                committed();
                return { kind: 'authenticated' };
            }
            this.applyLogin(nextAccount, tokens);
            return { kind: 'authenticated' };
        } finally {
            this.endAuth();
        }
    }
    /** 채택한 로그인을 적용한다. 로그인 실패는 기존 세션을 폐기하지 않는다. 성공한 뒤에만 이전 범위를 닫는다. */
    private applyLogin(nextAccount: Account, tokens: Tokens | null) {
        this.generation++;
        this.scopes.setAccount(null);
        this.intents.clear();
        this.tokens = tokens;
        this.gate.reset();
        this.establish(nextAccount);
    }
    /** 정식 칸 확정 구간을 연다. 돌려받은 함수로 닫는다. 그동안 새 소셜 로그인 시작은 기다린다. */
    private beginCommit() {
        let finish!: () => void;
        const done = new Promise<void>((resolve) => {
            finish = resolve;
        });
        this.committing = done;
        return () => {
            if (this.committing === done) this.committing = null;
            finish();
        };
    }
    private async discardStaged() {
        try {
            await this.refreshStore?.discard();
        } catch {
            // 버리지 못한 임시 값은 다음 실행의 take가 읽지 않고 지우며, 다음 로그인이 덮어쓴다.
        }
    }
    /**
     * 011 소셜 교환(LOGIN). 연결된 계정이면 로그인하고, 미연결이면 가입 계속 정보를 돌려준다.
     * Android(#388)는 시작 증명을 함께 보내고, 결과는 지금 회차의 시도일 때만 반영한다.
     * 증명은 로그인·가입을 마쳤을 때만 지운다. 실패해도 지우지 않는 이유는 아래 dropProof를 본다.
     */
    async oauthLogin(ticket: string) {
        const isCurrent = this.currentAttempt();
        const body = await this.withProof({ ticket, action: 'LOGIN' });
        const outcome = await this.authenticate('/auth/oauth/exchange', body, {
            allowSignup: true,
            isCurrent,
            queue: true,
        });
        if (outcome.kind === 'authenticated') await this.dropProof(isCurrent);
        return outcome;
    }
    /**
     * 011 소셜 가입 완료(COMPLETE_SIGNUP, #379). 성공하면 기존 로그인과 같이 세션을 연다.
     * 가입 티켓은 화면 메모리에만 두며, 결과를 모르면 같은 티켓을 다시 쓰지 않는다(D09).
     * 입력 오류(400 INVALID_REQUEST)로 거절돼도 증명은 남아 있어 고쳐서 다시 보낼 수 있다.
     */
    async completeSocialSignup(signupTicket: string, displayName: string, consent: Consents) {
        const isCurrent = this.currentAttempt();
        const body = await this.withProof({
            ticket: signupTicket,
            action: 'COMPLETE_SIGNUP',
            displayName,
            consent,
        });
        const outcome = await this.authenticate('/auth/oauth/exchange', body, {
            isCurrent,
            queue: true,
        });
        await this.dropProof(isCurrent);
        return outcome;
    }
    /** 지금 소셜 로그인 회차가 그대로인지 확인하는 함수 */
    private currentAttempt(attempt = this.oauthAttempt) {
        return () => attempt === this.oauthAttempt;
    }
    /** Android는 보관한 시작 증명을 교환 본문에 더한다. 없으면 다시 시작해야 한다. */
    private async withProof(body: Record<string, unknown>) {
        if (this.channel === 'WEB') return body;
        if (!this.proofStore) throw new Error('앱에서는 아직 소셜 로그인을 지원하지 않습니다.');
        const verifier = await this.proofStore.read();
        if (!verifier) throw new OAuthProofMissingError();
        return { ...body, [VERIFIER_FIELD]: verifier };
    }
    /**
     * 로그인·가입을 마친 시도의 증명만 지운다. 그 사이 새로 시작했으면 새 시도의 증명이므로 두고 간다.
     * 실패한 교환은 증명을 지우지 않는다. 늦게 도착한 이전 시도의 실패가 지금 시도의 증명을 지우면 안 되고,
     * 증명만으로는 교환할 수 없다(티켓은 일회용이고 화면이 같은 티켓을 다시 보내지 않는다).
     * 남은 증명은 다음 시작이 덮어쓰거나 보관 한도(15분)가 지나면 읽지 않는다.
     */
    private async dropProof(isCurrent: () => boolean) {
        if (!isCurrent()) return;
        try {
            await this.proofStore?.clear();
        } catch {
            // 지우지 못해도 보관 한도(15분)가 지나면 읽지 않는다.
        }
    }
    private proof() {
        return this.channel === 'WEB' ? {} : { refreshToken: this.tokens?.refreshToken };
    }
    private async refresh(): Promise<'refreshed' | 'login-required'> {
        const work = this.rotate();
        this.refreshWork = work;
        try {
            return await work;
        } finally {
            if (this.refreshWork === work) this.refreshWork = null;
        }
    }
    private async rotate(): Promise<'refreshed' | 'login-required'> {
        if (this.authDone && !this.restoringAccountId) {
            const generation = this.generation;
            await this.authDone;
            // 인증 도중 도착한 옛 401 때문에 새 로그인 자격을 다시 회전하지 않는다.
            if (generation !== this.generation)
                return this.state.account ? 'refreshed' : 'login-required';
            return this.rotate();
        }
        if (this.channel === 'ANDROID' && !this.tokens) return 'login-required';
        const generation = this.generation;
        try {
            // 서버가 토큰을 소비한 뒤 앱이 종료되면 옛 Refresh를 재전송하지 않아야 한다.
            await this.refreshStore?.clear();
            this.assertGeneration(generation);
            const result = await this.send('/auth/refresh', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-SceneCrew-Refresh-Attempt': attemptKey(),
                },
                body: JSON.stringify(this.proof()),
            });
            this.assertGeneration(generation);
            const tokens = credentials(result.data, this.channel, 'AuthRefreshResult');
            if (tokens && this.refreshStore) {
                const accountId = this.state.account?.id ?? this.restoringAccountId;
                if (!accountId) throw new ScopeDisposedError();
                await this.refreshStore.save({ accountId, refreshToken: tokens.refreshToken });
                this.assertGeneration(generation);
            }
            this.tokens = tokens;
            return 'refreshed';
        } catch {
            // 회전 결과가 불명인 Refresh는 자동 재전송하지 않는다. 옛 응답은 새 로그인을 폐기할 수 없다.
            if (generation === this.generation) await this.clear();
            return 'login-required';
        }
    }
    private logoutHooks = new Set<() => Promise<void>>();
    /** 로그아웃 직전, 아직 세션이 살아 있을 때 할 일(예: 푸시 기기 해제 #241). 실패해도 로그아웃은 계속한다. */
    beforeLogout(hook: () => Promise<void>) {
        this.logoutHooks.add(hook);
        return () => {
            this.logoutHooks.delete(hook);
        };
    }
    async logout() {
        if (this.logoutHooks.size && !this.authBusy) {
            const hooks = Promise.allSettled([...this.logoutHooks].map((hook) => hook()));
            // 서버가 늦어도 로그아웃을 오래 막지 않는다(서버도 세션 만료 시 푸시를 막는다)
            await Promise.race([hooks, new Promise((r) => setTimeout(r, LOGOUT_HOOK_MS))]);
        }
        const previousRefresh = this.beginAuth();
        let localCleared = true;
        let serverClosed = false;
        try {
            await this.boot;
            await previousRefresh;
            const proof = this.proof();
            // 응답보다 먼저 로컬 범위를 닫아 늦은 조회를 무효화한다.
            try {
                await this.clear();
            } catch {
                localCleared = false;
            }
            const result = await this.send('/auth/logout', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-SceneCrew-Logout-Attempt': attemptKey(),
                },
                body: JSON.stringify(proof),
            });
            if (result.status !== 204)
                throw new ContractViolationError('logout', '204여야 합니다.');
            serverClosed = true;
            if (!localCleared) throw new Error('기기 저장소 정리를 확인하지 못했습니다.');
        } catch (error) {
            this.state = {
                ...this.state,
                notice: serverClosed
                    ? '서버에서 로그아웃했지만 기기 저장소 정리를 확인하지 못했습니다. 다시 로그인해주세요.'
                    : localCleared
                      ? '이 기기에서는 로그아웃했습니다. 서버의 세션 종료는 확인하지 못했습니다. 다시 로그인해주세요.'
                      : '서버 세션 종료와 기기 저장소 정리를 확인하지 못했습니다. 다시 로그인해주세요.',
            };
            this.listeners.forEach((listener) => listener());
            throw error;
        } finally {
            this.endAuth();
        }
    }
    private scope() {
        const scope = this.scopes.currentAccount;
        if (!scope) throw new ScopeDisposedError();
        return scope;
    }
    events(cursor?: string) {
        return this.api.get(
            this.scope(),
            `/events${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`,
            eventPage,
        );
    }
    private applyParticipation(eventId: string, p: Participation) {
        const current = this.scopes.participation(eventId);
        if (
            current?.memberId === p.memberId &&
            BigInt(p.participationRevision) < BigInt(current.participationRevision)
        )
            throw new ScopeDisposedError();
        this.participationEpoch++;
        if (p.active) {
            const result = this.scopes.setParticipation({
                eventId,
                memberId: p.memberId,
                participationRevision: p.participationRevision,
            });
            if (result.change === 'stale') throw new ScopeDisposedError();
            if (result.change === 'advanced' || result.change === 'replaced') {
                this.intents.delete(`availability:${eventId}`);
                this.intents.delete(`leave:${eventId}`);
            }
        } else {
            this.scopes.leaveParticipation(eventId);
            this.intents.delete(`availability:${eventId}`);
            this.intents.delete(`leave:${eventId}`);
        }
    }
    async event(eventId: string) {
        const scope = this.scope();
        const epoch = this.participationEpoch;
        try {
            const value = await this.api.get(scope, `/events/${eventId}`, (d) => event(d, 'Event'));
            if (value.id !== eventId)
                throw new ContractViolationError('event.id', '요청한 행사와 같아야 합니다.');
            if (epoch !== this.participationEpoch) {
                // 겹친 조회가 같은 참여를 먼저 적용했으면 결과를 그대로 쓴다. 다른 변화(이탈 등)는 되살리지 않는다.
                const current = this.scopes.participation(eventId);
                const mine = value.myParticipation;
                if (
                    !mine.active ||
                    current?.memberId !== mine.memberId ||
                    current.participationRevision !== mine.participationRevision
                )
                    throw new ScopeDisposedError();
                return value;
            }
            this.applyParticipation(eventId, value.myParticipation);
            return value;
        } catch (error) {
            if (scope.isCurrent() && classifyFailure(error).kind === 'access-denied')
                this.scopes.leaveParticipation(eventId);
            throw error;
        }
    }
    async options(token: string) {
        const epoch = this.participationEpoch;
        const value = await this.api.get(
            this.scope(),
            `/invites/${encodeURIComponent(token)}/options`,
            inviteOptions,
        );
        if (epoch !== this.participationEpoch) throw new ScopeDisposedError();
        if (value.myParticipation) {
            this.applyParticipation(value.eventId, value.myParticipation);
            if (value.myParticipation.active) this.resolveIntent(this.scope(), `join:${token}`);
        } else if (this.scopes.participation(value.eventId)) {
            // 참여가 없어졌을 때만 이전 조회를 무효로 한다. 참여 전 초대 화면의 겹친 조회끼리 서로 버리지 않게 한다(#353).
            this.participationEpoch++;
            this.scopes.leaveParticipation(value.eventId);
        }
        return value;
    }
    // 미확인 의도는 화면을 다시 열어도 유지한다. 성공 또는 확정 거절 뒤에만 다음 의도를 허용한다.
    pending(operation: string) {
        return this.intents.has(operation);
    }
    private resolveIntent(scope: Scope, operation: string) {
        const intent = this.intents.get(operation);
        if (intent) this.scopes.intentKeys.release(scope, operation, intent.intentId);
        this.intents.delete(operation);
    }
    private async command<T>(
        scope: Scope,
        operation: string,
        spec: Omit<CommandSpec, 'operation' | 'intentId'>,
        parse: (data: unknown) => T,
    ) {
        let command = this.intents.get(operation);
        if (!command) {
            command = { ...spec, operation, intentId: crypto.randomUUID() };
            this.intents.set(operation, command);
        }
        const result = await this.api.command(scope, command, parse);
        if (
            result.status === 'succeeded' ||
            (result.status === 'failed' && result.failure.intent === 'finished')
        )
            this.intents.delete(operation);
        return result;
    }
    async join(token: string, eventId: string, body: unknown) {
        const result = await this.command(
            this.scope(),
            `join:${token}`,
            {
                method: 'POST',
                path: `/invites/${encodeURIComponent(token)}/join`,
                body,
                recovery: 'same-key',
            },
            (data) => {
                const value = joinResult(data);
                if (!value.participation.active)
                    throw new ContractViolationError(
                        'participation.active',
                        '참여 성공은 활성 상태여야 합니다.',
                    );
                return value;
            },
        );
        if (result.status === 'succeeded' && result.data)
            this.applyParticipation(eventId, result.data.participation);
        return result;
    }
    /**
     * 022 공연 만들기(#286). 만든 사람은 그 공연의 총괄이 된다. 요청 키를 쓰고, 결과를 모르면 같은 키로만 다시 보낸다.
     * 결과를 모르는 요청이 남아 있으면 그 요청(처음 입력값)을 같은 키로 다시 보낸다. 새 공연이 두 개 생기지 않게 하기 위해서다.
     */
    async createEvent(name: string, description: string) {
        const result = await this.command(
            this.scope(),
            'create-event',
            { method: 'POST', path: '/events', body: { name, description }, recovery: 'same-key' },
            (data) => {
                const value = event(data, 'Event');
                if (!value.myParticipation.active || value.myParticipation.eventRole !== 'CHIEF')
                    throw new ContractViolationError(
                        'myParticipation',
                        '만든 사람은 활성 총괄이어야 합니다.',
                    );
                return value;
            },
        );
        if (result.status === 'succeeded' && result.data)
            this.applyParticipation(result.data.id, result.data.myParticipation);
        return result;
    }
    async availability(
        eventId: string,
        memberId: string,
        available: boolean,
        expectedEventRevision: string,
    ) {
        const scope = this.scopes.participation(eventId);
        if (!scope || scope.memberId !== memberId) throw new ScopeDisposedError();
        const result = await this.command(
            scope,
            `availability:${eventId}`,
            {
                method: 'PATCH',
                path: `/members/${memberId}/availability`,
                body: { available, expectedEventRevision },
                idempotency: 'none',
            },
            (data) => {
                const value = participationChanged(data, 'ParticipationChanged');
                if (value.participation.memberId !== memberId)
                    throw new ContractViolationError('memberId', '본인의 참여여야 합니다.');
                return value;
            },
        );
        if (result.status === 'succeeded' && result.data)
            this.applyParticipation(eventId, result.data.participation);
        return result;
    }
    async leave(eventId: string) {
        const scope = this.scopes.participation(eventId);
        if (!scope) throw new ScopeDisposedError();
        const result = await this.command(
            scope,
            `leave:${eventId}`,
            { method: 'POST', path: `/events/${eventId}/leave`, recovery: 'same-key' },
            (data) => {
                const value = participationChanged(data, 'DepartureReceipt');
                if (value.participation.active || value.participation.memberId !== scope.memberId)
                    throw new ContractViolationError(
                        'participation',
                        '본인의 참여 종료여야 합니다.',
                    );
                return value;
            },
        );
        if (result.status === 'succeeded' && result.data)
            this.applyParticipation(eventId, result.data.participation);
        return result;
    }
    async updateName(displayName: string) {
        const result = await this.command(
            this.scope(),
            'profile',
            { method: 'PATCH', path: '/me', body: { displayName }, idempotency: 'none' },
            (data) => {
                const value = account(data);
                if (value.id !== this.scope().accountId)
                    throw new ContractViolationError('account.id', '현재 계정이어야 합니다.');
                return value;
            },
        );
        if (result.status === 'succeeded' && result.data) this.establish(result.data);
        return result;
    }
    /**
     * 탈퇴(#334, BE 018, V9-D39). 탈퇴가 곧 개인정보 삭제 요청이다. 현재 비밀번호로 본인을 확인한다.
     * 성공하면 서버가 모든 세션을 막는다. 로컬 로그인 상태는 접수 화면이 연 뒤 forgetAfterDeactivation으로 지운다
     * (먼저 지우면 계정 화면 보호가 로그인 화면으로 보낸다). 자동 재전송하지 않는다.
     */
    async deactivate(password: string) {
        const result = await this.send('/me/deactivate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password }),
        });
        if (result.status !== 200)
            throw new ContractViolationError('status', '탈퇴 응답 상태가 다릅니다.');
        return deactivationReceipt(result.data);
    }
    /** 탈퇴 접수 화면이 연 뒤 로컬 로그인 상태를 지운다. 서버 세션은 이미 막혔다. */
    async forgetAfterDeactivation() {
        try {
            await this.clear();
        } catch {
            // clear가 저장소 실패 안내를 남겼다.
        }
    }
    /** 내 동의와 약관 버전·이력(#334·#229, BE GET /me/consents). 이 기능 전 가입자는 버전이 null이고 이력이 비어 있다. */
    async consentRecord(): Promise<ConsentRecord> {
        const result = await this.send('/me/consents');
        if (result.status !== 200)
            throw new ContractViolationError('status', '동의 조회 응답 상태가 다릅니다.');
        return consentRecord(result.data);
    }
    /** 탈퇴 처리 상태(V9-D40). 로그인 없이 접수 번호로 본다. 없는 번호는 404(ApiError)다. */
    async deletionStatus(receiptNo: string): Promise<DeletionStatus> {
        if (!/^DEL-[0-9]{8}-[0-9A-F]{8}$/.test(receiptNo))
            throw new TypeError('접수 번호 형식이 올바르지 않습니다.');
        const result = await this.send(`/deletion-requests/${receiptNo}`);
        if (result.status !== 200)
            throw new ContractViolationError('status', '처리 상태 응답 상태가 다릅니다.');
        const v = asRecord(result.data, 'deletionStatus');
        if (
            Object.keys(v).sort().join(',') !== 'deletionDueAt,receiptNo,status' ||
            v['receiptNo'] !== receiptNo ||
            (v['status'] !== 'PROCESSING' && v['status'] !== 'COMPLETED') ||
            typeof v['deletionDueAt'] !== 'string'
        )
            throw new ContractViolationError('deletionStatus', '처리 상태 응답 형식이 다릅니다.');
        return { receiptNo, status: v['status'], deletionDueAt: v['deletionDueAt'] };
    }
    updateConsents(body: Partial<Consents>) {
        return this.command(
            this.scope(),
            'consents',
            { method: 'PATCH', path: '/me/consents', body, idempotency: 'none' },
            consents,
        );
    }
    async resetPassword(recoveryProof: string, password: string) {
        const result = await this.send('/auth/password-reset/confirm', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ recoveryProof, password }),
        });
        if (result.status !== 204) throw new ContractViolationError('response', '204여야 합니다.');
    }
}

export type DeletionStatus = {
    receiptNo: string;
    status: 'PROCESSING' | 'COMPLETED';
    deletionDueAt: string;
};
export type ConsentHistoryEntry = {
    at: string;
    version: string;
    source: 'SIGNUP' | 'CHANGE';
    values: Partial<Consents>;
};
export type ConsentRecord = {
    consents: Consents;
    version: string | null;
    history: ConsentHistoryEntry[];
};

const CONSENT_KEYS = ['service', 'voice', 'push', 'externalAi'] as const;

function consentRecord(data: unknown): ConsentRecord {
    const v = asRecord(data, 'consentRecord');
    if (Object.keys(v).sort().join(',') !== 'consents,history,version')
        throw new ContractViolationError('consentRecord', '동의 응답 형식이 다릅니다.');
    const current = consents({ consents: v['consents'] });
    const version = v['version'];
    if (version !== null && typeof version !== 'string')
        throw new ContractViolationError('version', '동의 버전 형식이 다릅니다.');
    if (!Array.isArray(v['history']))
        throw new ContractViolationError('history', '동의 이력이 배열이어야 합니다.');
    const history = v['history'].map((item: unknown) => {
        const e = asRecord(item, 'history');
        const values = asRecord(e['values'], 'history.values');
        const picked: Partial<Consents> = {};
        for (const key of CONSENT_KEYS) {
            if (key in values) {
                if (typeof values[key] !== 'boolean')
                    throw new ContractViolationError(
                        'history.values',
                        '동의 값은 true/false여야 합니다.',
                    );
                picked[key] = values[key];
            }
        }
        if (
            typeof e['at'] !== 'string' ||
            typeof e['version'] !== 'string' ||
            (e['source'] !== 'SIGNUP' && e['source'] !== 'CHANGE')
        )
            throw new ContractViolationError('history', '동의 이력 형식이 다릅니다.');
        const source: ConsentHistoryEntry['source'] = e['source'];
        return { at: e['at'], version: e['version'], source, values: picked };
    });
    return { consents: current, version, history };
}

export type DeactivationReceipt = {
    receiptNo: string;
    deactivatedAt: string;
    deletionDueAt: string;
};

function deactivationReceipt(data: unknown): DeactivationReceipt {
    const v = asRecord(data, 'deactivation');
    const keys = Object.keys(v).sort().join(',');
    if (keys !== 'deactivatedAt,deletionDueAt,receiptNo,status' || v['status'] !== 'DISABLED')
        throw new ContractViolationError('deactivation', '탈퇴 응답 형식이 다릅니다.');
    for (const key of ['receiptNo', 'deactivatedAt', 'deletionDueAt'] as const)
        if (typeof v[key] !== 'string' || !v[key])
            throw new ContractViolationError(key, '탈퇴 응답 값이 필요합니다.');
    return {
        receiptNo: v['receiptNo'] as string,
        deactivatedAt: v['deactivatedAt'] as string,
        deletionDueAt: v['deletionDueAt'] as string,
    };
}

/** 시작 응답이 그 제공자의 공식 인증 주소일 때만 돌려준다(로그인·연결 공통). */
function providerUrl(provider: SocialProvider, result: { status: number; data: unknown }) {
    if (result.status !== 200)
        throw new ContractViolationError('status', '시작 응답 상태가 다릅니다.');
    const v = asRecord(result.data, 'oauthStart');
    if (Object.keys(v).length !== 1 || typeof v['authorizationUrl'] !== 'string')
        throw new ContractViolationError('authorizationUrl', '인증 주소가 필요합니다.');
    let url: URL;
    try {
        url = new URL(v['authorizationUrl']);
    } catch {
        throw new ContractViolationError('authorizationUrl', '인증 주소 형식이 올바르지 않습니다.');
    }
    if (
        url.protocol !== 'https:' ||
        url.host !== SOCIAL_HOSTS[provider] ||
        url.username ||
        url.password
    )
        throw new ContractViolationError('authorizationUrl', '제공자의 공식 인증 주소가 아닙니다.');
    return url.href;
}
