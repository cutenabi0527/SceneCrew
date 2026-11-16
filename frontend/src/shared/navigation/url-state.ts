import { useCallback } from 'react';
import { useSearchParams } from 'react-router';

// 화면 안에서 고른 탭·필터·열린 팝업처럼 메모리에만 있던 선택을 주소 검색값(?key=값)에 둔다.
// 브라우저 새로고침(또는 주소 공유) 뒤에도 같은 화면 상태로 다시 연다.
// - 기본값이면 주소에서 지워 주소를 깔끔하게 둔다.
// - 기록을 쌓지 않게 replace로 바꾼다(탭을 바꿀 때마다 뒤로 가기가 늘지 않게).
// - 같은 경로 안의 검색값 변경이라 이동 확인(저장하지 않은 편집 경고)을 띄우지 않는다(guarded-history).
// - 주소의 값이 허용 목록(allowed)에 없으면 기본값으로 본다.

export function useUrlState<T extends string>(
    key: string,
    fallback: T,
    allowed?: readonly T[],
): [T, (next: T) => void] {
    const [params, setParams] = useSearchParams();
    const raw = params.get(key);
    const value =
        raw !== null && (!allowed || (allowed as readonly string[]).includes(raw))
            ? (raw as T)
            : fallback;
    const set = useCallback(
        (next: T) =>
            setParams(
                (old) => {
                    const p = new URLSearchParams(old);
                    if (next === fallback) p.delete(key);
                    else p.set(key, next);
                    return p;
                },
                { replace: true },
            ),
        [key, fallback, setParams],
    );
    return [value, set];
}
