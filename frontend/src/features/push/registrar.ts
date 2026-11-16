import { ContractViolationError } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';
import { ScopeDisposedError } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';

// 푸시 기기 등록(SC-API-019, #307, Android 앱 전용).
// - deviceId와 deviceProof(최초 등록 비밀값, V9-D20-1)는 네이티브 안전 저장소에서 받는다. URL·로그에 남기지 않는다.
// - 알림 권한은 사용자가 푸시 수신에 동의할 때만 묻는다(enable). 앱을 다시 열면 이미 허용된 경우에만 조용히 다시 등록한다(resume).
// - 019는 키를 무시하는 연산이라 키 없이 보낸다. 같은 계정·기기·토큰은 한 번만 보낸다.
// - 알림을 누르면 data의 eventId·notificationId(형식만 검사)로 그 공연의 알림을 연다(#307). data가 없거나
//   형식이 다르면 내 알림 화면을 연다. 알림 내용은 믿지 않고 서버 목록을 다시 읽어 찾는다.
// - Firebase 설정 없이 빌드한 앱은 등록하면 네이티브에서 종료되므로 등록하지 않는다(unavailable).
// - 로그아웃 직전에 기기를 해제한다(020, #241). 실패해도 로그아웃은 계속하고 서버가 세션 만료로 푸시를 막는다.
// - 해제·재로그인·재참여·푸시 재동의 뒤에는 같은 토큰이어도 다시 등록한다(서버가 연결을 끊었을 수 있다).

export interface PushNative {
    checkPermissions(): Promise<{ receive: string }>;
    requestPermissions(): Promise<{ receive: string }>;
    register(): Promise<void>;
    onToken(listener: (token: string) => void): Promise<() => void>;
    onError(listener: (message: string) => void): Promise<() => void>;
    onOpen(listener: (data: Record<string, unknown>) => void): Promise<() => void>;
    deviceSecret(): Promise<{ deviceId: string; deviceProof: string }>;
    /** 이 빌드에서 FCM을 쓸 수 있는지(Firebase 초기화 여부) */
    available(): Promise<boolean>;
}

/** 푸시를 눌렀을 때 열 알림. 서버가 보낸 data의 형식만 검사한다(UUID가 아니면 버린다). */
export interface PushTarget {
    eventId: string;
    notificationId: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function pushTarget(data: Record<string, unknown>): PushTarget | null {
    const { eventId, notificationId } = data;
    if (typeof eventId !== 'string' || typeof notificationId !== 'string') return null;
    if (!UUID.test(eventId) || !UUID.test(notificationId)) return null;
    return { eventId, notificationId };
}

export type PushStatus =
    'off' | 'needs-permission' | 'denied' | 'registering' | 'registered' | 'failed' | 'unavailable';

export class PushRegistrar {
    private status: PushStatus = 'off';
    private listeners = new Set<(s: PushStatus) => void>();
    private sent: string | null = null;
    private attached = false;

    constructor(
        private readonly session: IdentitySession,
        private readonly native: PushNative,
        private readonly openNotifications: (target: PushTarget | null) => void,
    ) {}

    onStatus(listener: (s: PushStatus) => void) {
        this.listeners.add(listener);
        listener(this.status);
        return () => {
            this.listeners.delete(listener);
        };
    }
    private set(status: PushStatus) {
        this.status = status;
        this.listeners.forEach((l) => l(status));
    }

    private async attach() {
        if (this.attached) return;
        this.attached = true;
        await this.native.onToken((token) => void this.send(token));
        await this.native.onError(() => this.set('failed'));
        await this.native.onOpen((data) => this.openNotifications(pushTarget(data)));
    }

    /** Firebase 설정이 없는 빌드는 등록하지 않는다(네이티브에서 앱이 종료된다). */
    private async registerNative() {
        const available = await this.native.available().catch(() => false);
        if (!available) return this.set('unavailable');
        this.set('registering');
        await this.native.register();
    }

    /** 사용자가 푸시 수신에 동의했다. 권한을 묻고 등록한다(같은 토큰이어도 다시 보낸다). */
    async enable() {
        this.sent = null;
        await this.attach();
        // Firebase 설정이 없는 빌드면 알림 권한부터 묻지 않는다(허용한 뒤 '쓸 수 없음'을 보이지 않게)
        if (!(await this.native.available().catch(() => false))) return this.set('unavailable');
        let { receive } = await this.native.checkPermissions();
        if (receive === 'prompt' || receive === 'prompt-with-rationale')
            receive = (await this.native.requestPermissions()).receive;
        if (receive !== 'granted') return this.set('denied');
        await this.registerNative();
    }

    /** 앱을 열거나 로그인했다. 이미 허용된 경우에만 묻지 않고 다시 등록한다. */
    async resume() {
        await this.attach();
        const { receive } = await this.native.checkPermissions();
        if (receive !== 'granted')
            return this.set(receive === 'denied' ? 'denied' : 'needs-permission');
        await this.registerNative();
    }

    /** 계정이 바뀌거나 사라졌다(세션 만료 포함). 다음 로그인에서 같은 토큰도 새 세션으로 다시 등록한다. */
    forget() {
        this.sent = null;
    }

    /** 공연에 새로 참여했다. 이미 허용된 경우에만 같은 토큰이어도 다시 등록한다. */
    async refresh() {
        this.sent = null;
        await this.resume();
    }

    /** 로그아웃 직전. 아직 세션이 있을 때 이 기기의 수신 연결을 해제한다. 결과와 무관하게 등록 기록을 지운다. */
    async unregister() {
        this.sent = null;
        const scope = this.session.scopes.currentAccount;
        if (!scope) return;
        let secret: { deviceId: string; deviceProof: string };
        try {
            secret = await this.native.deviceSecret();
        } catch {
            return;
        }
        const operation = 'push:unregister';
        const intentId = crypto.randomUUID();
        try {
            await this.session.api.command(
                scope,
                {
                    operation,
                    intentId,
                    method: 'DELETE',
                    path: `/me/devices/${encodeURIComponent(secret.deviceId)}`,
                    headers: { 'X-Device-Proof': secret.deviceProof },
                    idempotency: 'none',
                },
                () => undefined,
            );
        } catch {
            // 해제 실패는 로그아웃을 막지 않는다
        } finally {
            this.session.scopes.intentKeys.release(scope, operation, intentId);
            this.set('off');
        }
    }

    private async send(token: string) {
        const scope = this.session.scopes.currentAccount;
        if (!scope) return this.set('needs-permission');
        let secret: { deviceId: string; deviceProof: string };
        try {
            secret = await this.native.deviceSecret();
        } catch {
            return this.set('failed');
        }
        const marker = `${scope.accountId}:${secret.deviceId}:${token}`;
        if (this.sent === marker) return this.set('registered');
        const operation = 'push:register';
        const intentId = crypto.randomUUID();
        try {
            const outcome = await this.session.api.command(
                scope,
                {
                    operation,
                    intentId,
                    method: 'PUT',
                    path: `/me/devices/${encodeURIComponent(secret.deviceId)}`,
                    body: { token, deviceProof: secret.deviceProof },
                    idempotency: 'none',
                    response: 'data',
                },
                (d) => {
                    const r = parseV9<{ deviceId: string; registered: true }>(
                        'DeviceRegisteredDraft',
                        d,
                    );
                    if (r.deviceId !== secret.deviceId)
                        throw new ContractViolationError('deviceId', '요청한 기기여야 합니다.');
                    return r;
                },
            );
            this.session.scopes.intentKeys.release(scope, operation, intentId);
            if (outcome.status === 'succeeded' && outcome.data) {
                this.sent = marker;
                this.set('registered');
            } else this.set('failed');
        } catch (error) {
            if (!(error instanceof ScopeDisposedError)) this.set('failed');
        }
    }
}
