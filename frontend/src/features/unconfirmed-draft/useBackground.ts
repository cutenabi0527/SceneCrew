import { useEffect, useState } from 'react';
import type { Crop } from './layout';

// V9-D62: 평면도 배경 그림(PDF 평면도 쪽 PNG)을 받아 화면에서 쓸 임시 주소로 바꾼다. crop은 그 안의 평면도 영역이다.
// key가 바뀌면 다시 받고, 이전 주소는 해제한다. 못 받으면(404 등) null이라 화면은 배경 없이 도형만 그린다.
export type Background = { src: string; crop: Crop | null };
export type BackgroundFile = { blob: Blob; crop: Crop | null };

export function useBackground(
    key: string | null,
    load: ((signal: AbortSignal) => Promise<BackgroundFile>) | null,
): Background | null {
    const [got, setGot] = useState<{ key: string; background: Background } | null>(null);
    useEffect(() => {
        if (!key || !load) return;
        const controller = new AbortController();
        let current: string | null = null;
        load(controller.signal).then(
            ({ blob, crop }) => {
                if (controller.signal.aborted) return;
                current = URL.createObjectURL(blob);
                setGot({ key, background: { src: current, crop } });
            },
            () => undefined, // 그림이 없으면 배경 없이 그린다
        );
        return () => {
            controller.abort();
            if (current) URL.revokeObjectURL(current);
        };
        // load는 key가 같으면 같은 그림을 받는다고 본다(호출마다 새 함수라 의존성에서 뺀다)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [key]);
    // 열쇠가 바뀌었거나 없으면 이전 그림을 쓰지 않는다
    return got && got.key === key ? got.background : null;
}
