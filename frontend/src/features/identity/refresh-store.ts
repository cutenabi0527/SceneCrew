import { Capacitor, registerPlugin } from '@capacitor/core';
import { asRecord, asString, asUuid, ContractViolationError } from '../../shared/api/contract';

export interface StoredRefresh {
    accountId: string;
    refreshToken: string;
}

// 정식 칸 1개와 로그인 임시 칸 1개(#388).
// - take는 정식 칸만 읽고, 반환 전에 영속 복사본을 지운다. 회전 중 종료돼도 소비했을 수 있는 토큰을 재사용하지 않는다.
//   남아 있는 임시 칸은 채택되지 않은 로그인이므로 함께 버린다.
// - save는 지금 세션의 Refresh 회전 결과를 정식 칸에 바로 쓴다.
// - 새 로그인은 stage로 임시 칸에 두었다가, 채택할 때 commit으로 정식 칸에 원자적으로 옮긴다.
//   commit 전에 앱이 종료되면 다음 실행은 기존 정식 칸(이전 세션 또는 없음)으로 시작한다.
export interface RefreshStore {
    take(): Promise<StoredRefresh | null>;
    save(value: StoredRefresh): Promise<void>;
    stage(value: StoredRefresh): Promise<void>;
    commit(): Promise<void>;
    discard(): Promise<void>;
    clear(): Promise<void>;
}

interface NativeVault {
    take(): Promise<{ credential: unknown }>;
    save(value: StoredRefresh & { apiBase: string }): Promise<void>;
    stage(value: StoredRefresh & { apiBase: string }): Promise<void>;
    commit(): Promise<void>;
    discard(): Promise<void>;
    clear(): Promise<void>;
}

/** 정식 칸을 건드리기 전에 실패한 확정(native NOT_APPLIED). 기존 정식 자격은 그대로다. */
export class RefreshNotAppliedError extends Error {
    constructor() {
        super('기기 로그인 정보를 확정하지 못했습니다.');
        this.name = 'RefreshNotAppliedError';
    }
}

export function refreshStore(vault: NativeVault, apiBase: string): RefreshStore {
    return {
        async take() {
            const { credential } = await vault.take();
            if (credential === null) return null;
            const value = asRecord(credential, 'stored session');
            // 앱 업데이트로 API 환경이 달라졌다면 이전 서버의 증명을 새 서버로 보내지 않는다.
            if (value['apiBase'] !== apiBase) return null;
            const accountId = asUuid(value['accountId'], 'stored account');
            const refreshToken = asString(value['refreshToken'], 'stored credential');
            if (!refreshToken)
                throw new ContractViolationError('stored credential', '빈 값입니다.');
            return { accountId, refreshToken };
        },
        save: (value) => vault.save({ ...value, apiBase }),
        stage: (value) => vault.stage({ ...value, apiBase }),
        async commit() {
            try {
                await vault.commit();
            } catch (error) {
                if (
                    error &&
                    typeof error === 'object' &&
                    'code' in error &&
                    error.code === 'NOT_APPLIED'
                )
                    throw new RefreshNotAppliedError();
                throw error;
            }
        },
        discard: () => vault.discard(),
        clear: () => vault.clear(),
    };
}

export function nativeRefreshStore(apiBase: string): RefreshStore | undefined {
    // WEB·Android 자산의 브라우저 미리보기는 Cookie/메모리를 사용한다. Web Storage 대체 저장은 없다.
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return undefined;
    return refreshStore(registerPlugin<NativeVault>('RefreshToken'), apiBase);
}
