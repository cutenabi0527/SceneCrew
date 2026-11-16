import { describe, expect, it, vi } from 'vitest';
import { nativeRefreshStore, refreshStore, RefreshNotAppliedError } from './refresh-store';

const apiBase = 'https://scenecrew-test.invalid/api/v1';
const native = vi.hoisted(() => ({ enabled: false, platform: 'web', register: vi.fn() }));
vi.mock('@capacitor/core', () => ({
    Capacitor: {
        isNativePlatform: () => native.enabled,
        getPlatform: () => native.platform,
    },
    registerPlugin: native.register,
}));

describe('Refresh 확정 실패 구분(#388)', () => {
    it('정식 칸을 바꾸지 않은 실패(NOT_APPLIED)만 따로 알린다', async () => {
        const bridge = {
            take: vi.fn(),
            save: vi.fn(),
            stage: vi.fn(),
            commit: vi.fn(),
            discard: vi.fn(),
            clear: vi.fn(),
        };
        const store = refreshStore(bridge, apiBase);
        bridge.commit.mockRejectedValueOnce(Object.assign(new Error('x'), { code: 'NOT_APPLIED' }));
        await expect(store.commit()).rejects.toBeInstanceOf(RefreshNotAppliedError);
        bridge.commit.mockRejectedValueOnce(new Error('write failed'));
        const other = await store.commit().catch((e: unknown) => e);
        expect(other).not.toBeInstanceOf(RefreshNotAppliedError);
    });
});

describe('Refresh 저장소 bridge', () => {
    it('암호화한 API 환경이 다르면 자격을 쓰지 않고 새 환경에 저장할 때 묶는다', async () => {
        const credential = {
            accountId: '00000000-0000-4000-8000-000000000001',
            refreshToken: 'synthetic',
            apiBase: 'https://old.invalid/api/v1',
        };
        const bridge = {
            take: vi.fn(async () => ({ credential })),
            save: vi.fn(),
            stage: vi.fn(),
            commit: vi.fn(),
            discard: vi.fn(),
            clear: vi.fn(),
        };
        const store = refreshStore(bridge, apiBase);
        expect(await store.take()).toBeNull();
        await store.save({
            accountId: credential.accountId,
            refreshToken: credential.refreshToken,
        });
        expect(bridge.save).toHaveBeenCalledWith({ ...credential, apiBase });
        credential.apiBase = apiBase;
        expect(await store.take()).toEqual({
            accountId: credential.accountId,
            refreshToken: 'synthetic',
        });
    });
    it('WEB/브라우저 Android 미리보기에는 영속 저장을 연결하지 않는다', () => {
        expect(nativeRefreshStore(apiBase)).toBeUndefined();
        native.platform = 'android';
        expect(nativeRefreshStore(apiBase)).toBeUndefined();
        expect(native.register).not.toHaveBeenCalled();
        native.enabled = true;
        expect(nativeRefreshStore(apiBase)).toBeDefined();
        expect(native.register).toHaveBeenCalledWith('RefreshToken');
        native.enabled = false;
    });
    it.each([
        undefined,
        {},
        { accountId: 'bad', refreshToken: 'x' },
        { accountId: '00000000-0000-4000-8000-000000000001', refreshToken: '' },
    ])('잘못된 native 결과를 거절한다: %j', async (credential) => {
        const store = refreshStore(
            {
                take: async () => ({
                    credential:
                        credential && typeof credential === 'object'
                            ? { apiBase, ...credential }
                            : credential,
                }),
                save: vi.fn(),
                stage: vi.fn(),
                commit: vi.fn(),
                discard: vi.fn(),
                clear: vi.fn(),
            },
            apiBase,
        );
        await expect(store.take()).rejects.toThrow();
    });
    it('자격 부재와 저장소 거절을 구분하며 실패를 메모리 성공으로 숨기지 않는다', async () => {
        const bridge = {
            take: vi.fn(async () => ({ credential: null })),
            save: vi.fn(),
            stage: vi.fn(),
            commit: vi.fn(),
            discard: vi.fn(),
            clear: vi.fn(),
        };
        const store = refreshStore(bridge, apiBase);
        expect(await store.take()).toBeNull();
        bridge.take.mockRejectedValueOnce(new Error('unavailable'));
        await expect(store.take()).rejects.toThrow();
        bridge.save.mockRejectedValueOnce(new Error('write failed'));
        await expect(
            store.save({ accountId: 'test', refreshToken: 'synthetic' }),
        ).rejects.toThrow();
    });
});
