import { QueryClient } from '@tanstack/react-query';

export function createQueryClient() {
    return new QueryClient({
        defaultOptions: {
            queries: { retry: false, staleTime: 30_000 },
            // offline 상태에서 보류한 명령을 reconnect 시 자동 실행하지 않는다.
            // 실패/결과 불명 이후의 확인·재시도는 업무별 동일 의도 복구 절차가 맡는다.
            mutations: { retry: false, networkMode: 'always' },
        },
    });
}
