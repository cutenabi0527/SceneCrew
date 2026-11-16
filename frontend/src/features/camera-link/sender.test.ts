import { describe, expect, it, vi } from 'vitest';
import { FrameSender, isBlank, type SenderStatus } from './sender';

// #396 브라우저 카메라 보내기 규칙(V9-D58·D60, BE13): 2초 차례, 겹치면 건너뜀, 검은 사진 안 보냄,
// 열 때마다 새 세션·순번 1부터, 403·409면 즉시 멈춤.

const jpeg = new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' });

function setup(opts: { grab?: () => Promise<Blob | null>; send?: () => Promise<unknown> } = {}) {
    const sent: { session: string; sequence: number }[] = [];
    const statuses: SenderStatus[] = [];
    let sessions = 0;
    const send = vi.fn(async (f: { session: string; sequence: number }) => {
        sent.push({ session: f.session, sequence: f.sequence });
        return opts.send ? opts.send() : {};
    });
    const sender = new FrameSender({
        capture: { grab: opts.grab ?? (async () => jpeg) },
        send,
        stopReason: (e) => (e instanceof Error && e.message === 'TAKEN' ? '넘겨받힘' : null),
        onStatus: (s) => statuses.push(s),
        ticker: () => ({ stop: () => undefined }), // 차례는 시험이 tick()으로 직접 부른다
        newSession: () => `s${++sessions}`,
    });
    return { sender, sent, statuses, send };
}

describe('브라우저 카메라 보내기(#396)', () => {
    it('가장 밝은 점이 5 이하이면 검은 사진이다', () => {
        expect(isBlank([5, 5, 5, 255, 0, 0, 0, 255])).toBe(true);
        expect(isBlank([5, 6, 5, 255])).toBe(false);
        expect(isBlank([0, 0, 0, 255, 200, 10, 10, 255])).toBe(false);
    });

    it('시작하면 바로 한 장을 보내고, 순번은 1부터 오른다', async () => {
        const { sender, sent } = setup();
        sender.start();
        await vi.waitFor(() => expect(sent).toHaveLength(1));
        await sender.tick();
        await sender.tick();
        expect(sent).toEqual([
            { session: 's1', sequence: 1 },
            { session: 's1', sequence: 2 },
            { session: 's1', sequence: 3 },
        ]);
        sender.stop();
    });

    it('앞 전송이 끝나지 않았으면 그 차례를 건너뛰고 몰아 보내지 않는다', async () => {
        let release!: () => void;
        const gate = new Promise<void>((r) => (release = r));
        const { sender, sent, statuses } = setup({ send: () => gate });
        sender.start();
        await vi.waitFor(() => expect(sent).toHaveLength(1));
        await sender.tick(); // 보내는 중 → 건너뜀
        await sender.tick(); // 또 건너뜀
        expect(sent).toHaveLength(1);
        const last = statuses.at(-1);
        expect(last).toMatchObject({ kind: 'sending', skipped: 2, sent: 0 });
        release();
        await vi.waitFor(() => expect(statuses.at(-1)).toMatchObject({ sent: 1 }));
        sender.stop();
    });

    it('검은 사진은 보내지 않는다', async () => {
        const { sender, send } = setup({ grab: async () => null });
        sender.start();
        await sender.tick();
        expect(send).not.toHaveBeenCalled();
        sender.stop();
    });

    it('일시 오류 뒤에는 다음 순번으로 이어 보내고(응답을 잃어도 OUTDATED에 갇히지 않음), 넘겨받힘은 즉시 멈춘다', async () => {
        let fail: string | null = 'TEMP';
        const { sender, sent, statuses } = setup({
            send: async () => {
                if (fail) {
                    const e = new Error(fail);
                    fail = null;
                    throw e;
                }
                return {};
            },
        });
        sender.start();
        await vi.waitFor(() => expect(sent).toHaveLength(1));
        await sender.tick();
        expect(sent.map((s) => s.sequence)).toEqual([1, 2]); // 실패한 1번 대신 2번으로 보냈다
        fail = 'TAKEN';
        await sender.tick();
        expect(statuses.at(-1)).toEqual({ kind: 'stopped', reason: '넘겨받힘' });
        expect(sender.running).toBe(false);
        await sender.tick();
        expect(sent).toHaveLength(3); // 멈춘 뒤에는 보내지 않는다
    });

    it('다시 시작하면 새 스트림 세션과 순번 1부터', async () => {
        const { sender, sent } = setup();
        sender.start();
        await vi.waitFor(() => expect(sent).toHaveLength(1));
        sender.stop();
        sender.start();
        await vi.waitFor(() => expect(sent).toHaveLength(2));
        expect(sent[1]).toEqual({ session: 's2', sequence: 1 });
        sender.stop();
    });
});
