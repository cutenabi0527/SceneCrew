import { MutationObserver, onlineManager } from '@tanstack/react-query';
import { expect, it, vi } from 'vitest';
import { createQueryClient } from './query-client';

it('오프라인 명령을 보류했다가 연결 복구 시 자동 실행하지 않는다', async () => {
    const wasOnline = onlineManager.isOnline();
    const client = createQueryClient();
    const send = vi.fn().mockRejectedValue(new TypeError('offline'));
    onlineManager.setOnline(false);
    client.mount();
    const observer = new MutationObserver(client, { mutationFn: send });
    const result = observer.mutate(undefined).catch(() => undefined);
    try {
        await new Promise((resolve) => setTimeout(resolve, 10));
        const beforeReconnect = {
            calls: send.mock.calls.length,
            paused: observer.getCurrentResult().isPaused,
        };
        onlineManager.setOnline(true);
        await result;
        expect(beforeReconnect).toEqual({ calls: 1, paused: false });
        expect(send).toHaveBeenCalledTimes(1);
    } finally {
        onlineManager.setOnline(true);
        await result;
        client.unmount();
        client.clear();
        onlineManager.setOnline(wasOnline);
    }
});
