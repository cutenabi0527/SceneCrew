// 브라우저 카메라 보내기(#396, V9-D58·D60, BE13 캡처 규칙). camera/webcam.py가 하던 일을 브라우저가 한다.
// - 2초마다 JPEG 1장(품질 0.8). 앞 전송이 끝나지 않았으면 그 차례를 건너뛴다(몰아 보내지 않음).
// - 가장 밝은 점이 5 이하인 검은 사진은 보내지 않는다(webcam.py BLANK_LEVEL).
// - 카메라를 열 때마다 새 스트림 세션·순번 1부터.
// - 타이머는 Web Worker에서 돌린다(숨긴 탭에서 메인 스레드 타이머가 늦춰지는 것을 줄인다). 화면 꺼짐 방지를 요청한다.
// - 403·409(자격 상실·넘겨받힘·해제·사용 안 함)를 받으면 즉시 멈추고 이유를 알린다(stopReason).

export const FRAME_INTERVAL_MS = 2000;
export const JPEG_QUALITY = 0.8;
export const BLANK_LEVEL = 5;
export const PREFERRED_SIZE = { width: 1280, height: 960 };

/** RGBA 픽셀에서 가장 밝은 채널 값이 level 이하이면 검은 사진이다 */
export function isBlank(rgba: ArrayLike<number>, level = BLANK_LEVEL): boolean {
    for (let i = 0; i < rgba.length; i += 4) {
        if (rgba[i] > level || rgba[i + 1] > level || rgba[i + 2] > level) return false;
    }
    return true;
}

export interface Ticker {
    stop(): void;
}

/**
 * interval마다 onTick을 부른다. Worker를 쓸 수 있으면 Worker 안의 setInterval로, 아니면(시험 환경 등) 메인 스레드 타이머로.
 */
export function startTicker(intervalMs: number, onTick: () => void): Ticker {
    if (
        typeof Worker !== 'undefined' &&
        typeof Blob !== 'undefined' &&
        typeof URL.createObjectURL === 'function'
    ) {
        try {
            const source = `let t=setInterval(()=>postMessage(0),${intervalMs});onmessage=()=>clearInterval(t);`;
            const url = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
            const worker = new Worker(url);
            URL.revokeObjectURL(url);
            worker.onmessage = () => onTick();
            return {
                stop() {
                    worker.postMessage('stop');
                    worker.terminate();
                },
            };
        } catch {
            // CSP 등으로 Worker를 못 만들면 아래 타이머를 쓴다
        }
    }
    const t = setInterval(onTick, intervalMs);
    return { stop: () => clearInterval(t) };
}

export interface Capture {
    /** 지금 화면을 JPEG로. 검은 사진이면 null */
    grab(): Promise<Blob | null>;
}

export type SenderStatus =
    | { kind: 'sending'; sent: number; lastSentAt: number | null; skipped: number }
    | { kind: 'stopped'; reason: string | null };

export interface FrameSenderOptions {
    capture: Capture;
    send: (frame: { session: string; sequence: number; jpeg: Blob }) => Promise<unknown>;
    /** 실패를 멈춤 이유로 바꾼다. null이면 다음 차례에 다시 보낸다 */
    stopReason: (error: unknown) => string | null;
    onStatus: (status: SenderStatus) => void;
    intervalMs?: number;
    ticker?: (intervalMs: number, onTick: () => void) => Ticker;
    newSession?: () => string;
}

/**
 * 연결 하나의 보내기. start 때 새 스트림 세션을 만들고 순번 1부터 보낸다.
 * 한 번에 한 장만 보내며, 보내는 중에 다음 차례가 오면 건너뛴다(skipped).
 */
export class FrameSender {
    private ticker: Ticker | null = null;
    private inflight = false;
    private sequence = 0;
    private session = '';
    private sent = 0;
    private skipped = 0;
    private lastSentAt: number | null = null;
    private stopped = true;

    constructor(private readonly o: FrameSenderOptions) {}

    get running() {
        return !this.stopped;
    }

    start() {
        if (!this.stopped) return;
        this.stopped = false;
        this.session = (this.o.newSession ?? (() => crypto.randomUUID()))();
        this.sequence = 0;
        this.sent = 0;
        this.skipped = 0;
        this.lastSentAt = null;
        this.report();
        void this.tick();
        this.ticker = (this.o.ticker ?? startTicker)(this.o.intervalMs ?? FRAME_INTERVAL_MS, () => {
            void this.tick();
        });
    }

    stop(reason: string | null = null) {
        if (this.stopped) return;
        this.stopped = true;
        this.ticker?.stop();
        this.ticker = null;
        this.o.onStatus({ kind: 'stopped', reason });
    }

    /** 한 차례. 보내는 중이면 건너뛴다(시험에서도 부른다) */
    async tick() {
        if (this.stopped) return;
        if (this.inflight) {
            this.skipped += 1;
            this.report();
            return;
        }
        this.inflight = true;
        try {
            const jpeg = await this.o.capture.grab();
            if (!jpeg || this.stopped) return;
            // 순번은 보낼 때마다 오른다. 서버는 받은 순번 이하만 거절하므로(빈 번호 허용),
            // 응답만 잃은 사진의 번호를 다시 쓰면 계속 OUTDATED가 되는 것을 막는다
            const sequence = ++this.sequence;
            await this.o.send({ session: this.session, sequence, jpeg });
            this.sent += 1;
            this.lastSentAt = Date.now();
            this.report();
        } catch (error) {
            // 일시 오류면 이 사진은 버리고 다음 차례에 새 사진·새 순번으로 보낸다
            const reason = this.o.stopReason(error);
            if (reason) this.stop(reason);
        } finally {
            this.inflight = false;
        }
    }

    private report() {
        if (this.stopped) return;
        this.o.onStatus({
            kind: 'sending',
            sent: this.sent,
            lastSentAt: this.lastSentAt,
            skipped: this.skipped,
        });
    }
}

/** 카메라 영상(video)에서 한 장을 잡는다. 검은 사진은 작은 크기로 줄여 판정한다 */
export function videoCapture(video: HTMLVideoElement, quality = JPEG_QUALITY): Capture {
    const canvas = document.createElement('canvas');
    const probe = document.createElement('canvas');
    probe.width = 64;
    probe.height = 48;
    return {
        async grab() {
            const w = video.videoWidth;
            const h = video.videoHeight;
            if (!w || !h) return null;
            const p = probe.getContext('2d', { willReadFrequently: true });
            if (p) {
                p.drawImage(video, 0, 0, probe.width, probe.height);
                if (isBlank(p.getImageData(0, 0, probe.width, probe.height).data)) return null;
            }
            canvas.width = w;
            canvas.height = h;
            canvas.getContext('2d')?.drawImage(video, 0, 0, w, h);
            return new Promise<Blob | null>((resolve) =>
                canvas.toBlob((b) => resolve(b), 'image/jpeg', quality),
            );
        },
    };
}

/** 카메라 열기. 1280x960 이상을 우선 요청하고, 고른 기기가 있으면 그 기기를 연다 */
export function openCamera(deviceId?: string): Promise<MediaStream> {
    return navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
            ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
            width: { ideal: PREFERRED_SIZE.width },
            height: { ideal: PREFERRED_SIZE.height },
        },
    });
}

/** 영상 입력 기기 목록(권한을 받은 뒤라야 이름이 보인다) */
export async function videoDevices(): Promise<MediaDeviceInfo[]> {
    const all = await navigator.mediaDevices.enumerateDevices();
    return all.filter((d) => d.kind === 'videoinput');
}

/** 화면 꺼짐 방지. 지원하지 않거나 거절되면 null(경고 문구는 늘 보인다) */
export async function keepAwake(): Promise<{ release(): void } | null> {
    const nav = navigator as Navigator & {
        wakeLock?: { request(type: 'screen'): Promise<{ release(): Promise<void> }> };
    };
    try {
        const lock = await nav.wakeLock?.request('screen');
        return lock ? { release: () => void lock.release().catch(() => undefined) } : null;
    } catch {
        return null;
    }
}
