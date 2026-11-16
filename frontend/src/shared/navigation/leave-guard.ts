// 이탈 보호와 뒤로가기 우선순위. 라우터·화면·Capacitor를 알지 못하는 순수 로직이다.
//
// Back 우선순위: 확인 창 닫기 → 모달 닫기 → (녹음/미저장) 이탈 확인 → 화면 이력 뒤로 → 루트 최소화.
// 이탈 확인은 한 번에 하나만 열고, 확인("나가기")하면 보류한 이동을 정확히 한 번 실행한다.

export type ProtectionReason = 'unsaved' | 'recording';

export interface LeaveCopy {
    title: string;
    message: string;
    stay: string;
    leave: string;
}

// 기본 문구는 임시다. 보호 문구는 신기주 디자인 검토 대상이며 화면이 copy로 덮어쓴다.
const DEFAULT_COPY: Record<ProtectionReason, LeaveCopy> = {
    unsaved: {
        title: '작성 중인 내용이 있어요',
        message: '지금 나가면 입력한 내용이 사라질 수 있어요.',
        stay: '계속 작성하기',
        leave: '나가기',
    },
    recording: {
        title: '녹음 중이에요',
        message: '지금 나가면 녹음이 중단될 수 있어요.',
        stay: '계속 녹음하기',
        leave: '나가기',
    },
};

export interface ProtectionOptions {
    reason: ProtectionReason;
    copy?: Partial<LeaveCopy>;
}

export interface PromptView {
    reason: ProtectionReason;
    copy: LeaveCopy;
}

export interface GuardSnapshot {
    protected: boolean;
    prompt: PromptView | null;
}

export type BackOutcome =
    'dismissed-prompt' | 'closed-modal' | 'prompted' | 'went-back' | 'minimized';

export interface BackPorts {
    // 화면 이력을 한 단계 뒤로 간다. 반드시 보호가 적용되는 history를 통해 이동해야 한다.
    goBack(): void;
    minimize(): void;
}

interface Protection extends ProtectionOptions {
    token: symbol;
}

export class LeaveGuardController {
    private readonly modals: { token: symbol; close: () => void }[] = [];
    private readonly protections: Protection[] = [];
    private pending: { run: () => void; view: PromptView } | null = null;
    private bypass = 0;
    private listeners = new Set<() => void>();
    private snapshot: GuardSnapshot = { protected: false, prompt: null };

    // useSyncExternalStore용
    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    };
    getSnapshot = () => this.snapshot;

    isProtected() {
        return this.protections.length > 0;
    }

    hasPrompt() {
        return this.pending !== null;
    }

    // 보호가 일시적으로 풀린 상태에서 실행되는 중인지(확인 뒤 이동 실행 중)
    isBypassing() {
        return this.bypass > 0;
    }

    // 모달/바텀시트가 열려 있는 동안 등록한다. Back은 가장 늦게 열린 모달부터 닫는다.
    registerModal(close: () => void) {
        const entry = { token: Symbol('modal'), close };
        this.modals.push(entry);
        return () => {
            const index = this.modals.findIndex((item) => item.token === entry.token);
            if (index >= 0) this.modals.splice(index, 1);
        };
    }

    // 미저장 입력/녹음이 있는 동안 등록하고 끝나면 반드시 해제한다. 해제는 여러 번 해도 안전하다.
    registerProtection(options: ProtectionOptions) {
        const entry: Protection = { ...options, token: Symbol('protection') };
        this.protections.push(entry);
        this.publish();
        return () => {
            const index = this.protections.findIndex((item) => item.token === entry.token);
            if (index < 0) return;
            this.protections.splice(index, 1);
            // 보호가 모두 사라졌는데 확인이 열려 있다면 보류를 버린다(더 이상 막을 이유가 없다).
            if (this.protections.length === 0 && this.pending) this.pending = null;
            this.publish();
        };
    }

    // 이탈을 시도한다. 보호가 없으면 즉시 실행한다. 있으면 확인을 열고 실행을 보류한다.
    // 확인이 이미 열려 있으면 먼저 보류한 요청을 유지한다(두 번째 요청은 무시).
    request(run: () => void): 'ran' | 'prompted' {
        if (this.bypass > 0 || !this.isProtected()) {
            run();
            return 'ran';
        }
        if (!this.pending) {
            this.pending = { run, view: this.currentView() };
            this.publish();
        }
        return 'prompted';
    }

    // 사용자가 이미 고른 이동(보내기·취소 등)은 확인 없이 실행한다. 보호 등록은 그대로 두며 화면이 사라질 때 풀린다.
    proceed(run: () => void) {
        this.bypass++;
        try {
            run();
        } finally {
            this.bypass--;
        }
    }

    // "나가기". 보류한 이동을 한 번만 실행한다.
    leave() {
        const pending = this.pending;
        if (!pending) return;
        this.pending = null;
        this.bypass++;
        try {
            pending.run();
        } finally {
            this.bypass--;
            this.publish();
        }
    }

    // "계속 작성하기"(또는 Esc/Back). 보류한 이동을 버린다.
    stay() {
        if (!this.pending) return;
        this.pending = null;
        this.publish();
    }

    // Android 하드웨어 Back 한 번을 처리한다.
    handleBack(event: { canGoBack: boolean }, ports: BackPorts): BackOutcome {
        if (this.pending) {
            this.stay();
            return 'dismissed-prompt';
        }
        const modal = this.modals[this.modals.length - 1];
        if (modal) {
            modal.close();
            return 'closed-modal';
        }
        const proceed = () => (event.canGoBack ? ports.goBack() : ports.minimize());
        if (this.request(proceed) === 'prompted') return 'prompted';
        return event.canGoBack ? 'went-back' : 'minimized';
    }

    private currentView(): PromptView {
        // 녹음 중이면 녹음 안내를 우선한다. 같은 종류 안에서는 가장 늦게 등록한 것의 문구를 쓴다.
        const picked =
            [...this.protections].reverse().find((item) => item.reason === 'recording') ??
            this.protections[this.protections.length - 1];
        // 스프레드는 undefined 값도 기본값 위에 덮어쓰므로 항목별로 고른다.
        const base = DEFAULT_COPY[picked.reason];
        const copy = picked.copy;
        return {
            reason: picked.reason,
            copy: {
                title: copy?.title ?? base.title,
                message: copy?.message ?? base.message,
                stay: copy?.stay ?? base.stay,
                leave: copy?.leave ?? base.leave,
            },
        };
    }

    private publish() {
        const next: GuardSnapshot = {
            protected: this.isProtected(),
            prompt: this.pending?.view ?? null,
        };
        if (next.protected === this.snapshot.protected && next.prompt === this.snapshot.prompt) {
            return;
        }
        this.snapshot = next;
        for (const listener of [...this.listeners]) listener();
    }
}
