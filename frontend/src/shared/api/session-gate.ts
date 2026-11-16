// 401 뒤 세션 복구를 한 번에 하나만 수행하는 게이트(FE 소비 기준, 제안: fe-be-contract).
//  - 같은 시점의 여러 401은 하나의 refresh 결과를 공유한다(single-flight).
//  - refresh 응답이 오지 않았거나 결과를 알 수 없으면 같은 refresh를 다시 보내지 않고 재로그인으로 안내한다.
//    재로그인이 끝나 reset()을 부르기 전에는 refresh를 다시 시도하지 않는다.
// refresh 엔드포인트, 쿠키/Bearer, 토큰 저장은 이 게이트가 알지 못한다. 합의된 transport가 refresh 함수로 주입한다.

export type RefreshResult = 'refreshed' | 'login-required';
export type Recovery = RefreshResult;

export class SessionGate {
    private inflight: Promise<Recovery> | null = null;
    private needsLogin = false;

    constructor(private readonly refresh: () => Promise<RefreshResult>) {}

    // 401을 받은 호출자가 부른다. 'refreshed'면 GET을 한 번만 다시 보낸다.
    recover(): Promise<Recovery> {
        if (this.needsLogin) return Promise.resolve('login-required');
        if (this.inflight) return this.inflight;
        const attempt = this.run().finally(() => {
            this.inflight = null;
        });
        this.inflight = attempt;
        return attempt;
    }

    // 사용자가 다시 로그인했다.
    reset() {
        this.needsLogin = false;
    }

    get loginRequired() {
        return this.needsLogin;
    }

    private async run(): Promise<Recovery> {
        try {
            const result = await this.refresh();
            if (result === 'login-required') this.needsLogin = true;
            return result;
        } catch {
            // 응답 유실·네트워크 오류 등 결과를 알 수 없다. 이전 토큰을 다시 보내지 않는다.
            this.needsLogin = true;
            return 'login-required';
        }
    }
}
