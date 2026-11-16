import { runPdf, type PdfAnswers, type PdfDeps, type PdfOutcome, type PdfStep } from './pipeline';

// PDF 탭 자동 진행의 실행 상태. 화면(컴포넌트)과 떨어져 공연·참여 회차마다 하나만 돈다.
// 화면을 다시 그리거나(StrictMode의 두 번 마운트 포함) 잠깐 다른 화면에 다녀와도 진행이 끊기거나 두 번 돌지 않는다.
// 페이지를 새로 고치면 사라지고, 그때는 이 탭이 기억하는 작업(job memory)으로 다시 이어 간다.

export type RunnerState = {
    running: boolean;
    step: PdfStep | null;
    outcome: PdfOutcome | null;
};

const IDLE: RunnerState = { running: false, step: null, outcome: null };

const flagKey = (key: string) => `${key}:pdf-quick`;

export class PdfRunner {
    private state: RunnerState = IDLE;
    private listeners = new Set<() => void>();
    /** 이 실행기에서 받은 답(멈출 때마다 묻은 칸). 다음 이어 가기에도 함께 넘긴다(#410 리뷰) */
    private answers: Required<PdfAnswers> = { basics: {}, conditions: {} };

    constructor(private readonly key: string) {}

    /** 이 탭에서 PDF 자동 진행을 시작한 적이 있는지. 있을 때만 새로고침 뒤 저절로 이어 간다. */
    started() {
        try {
            return sessionStorage.getItem(flagKey(this.key)) === '1';
        } catch {
            return false;
        }
    }

    private mark() {
        try {
            sessionStorage.setItem(flagKey(this.key), '1');
        } catch {
            // 저장하지 못하면 새로고침 뒤 이어서 진행을 직접 누른다
        }
    }

    snapshot = () => this.state;

    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    };

    private set(next: Partial<RunnerState>) {
        this.state = { ...this.state, ...next };
        for (const listener of this.listeners) listener();
    }

    /** 이미 돌고 있으면 아무것도 하지 않는다(두 번 보내지 않는다) */
    start(deps: PdfDeps, options: { files?: File[]; answers?: PdfAnswers }) {
        if (this.state.running) return false;
        this.mark();
        // 새 문서면 앞의 답을 버린다. 답은 누적해 이어 가기마다 함께 보낸다
        if (options.files?.length) this.answers = { basics: {}, conditions: {} };
        this.answers = {
            basics: { ...this.answers.basics, ...options.answers?.basics },
            conditions: { ...this.answers.conditions, ...options.answers?.conditions },
        };
        const answered =
            Object.keys(this.answers.basics).length + Object.keys(this.answers.conditions).length;
        this.set({ running: true, outcome: null });
        void runPdf(
            deps,
            {
                files: options.files,
                answers: answered ? this.answers : undefined,
                // 새 문서·새 답이 없으면 기억한 계획 생성 결과부터 쓴다
                reuse: !options.files?.length && !options.answers,
            },
            (step) => this.set({ step }),
        ).then(
            (outcome) => this.set({ running: false, outcome }),
            (error: unknown) =>
                this.set({
                    running: false,
                    outcome: {
                        kind: 'failed',
                        step: this.state.step ?? 'upload',
                        message:
                            error instanceof Error && error.message
                                ? error.message
                                : '진행을 멈췄습니다.',
                    },
                }),
        );
        return true;
    }
}

const runners = new Map<string, PdfRunner>();

/** key: 공연·참여 회차(job memory 키). 회차가 바뀌면 새 실행기다. */
export function pdfRunner(key: string) {
    let runner = runners.get(key);
    if (!runner) {
        runner = new PdfRunner(key);
        runners.set(key, runner);
    }
    return runner;
}
