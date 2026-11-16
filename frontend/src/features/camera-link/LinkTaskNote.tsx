import { useContext, useEffect, useState } from 'react';
import { LiveContext } from '../../live/context';
import { Note } from '../../shared/ui/atoms';
import { CameraLinkDesk } from './client';

/**
 * APP 업무 상세(M05)의 카메라 연결 담당 안내(#396). 휴대폰으로는 보내지 않고, 노트북 WEB의 내 연결에서 연결한다.
 * 내 연결 목록(W1)에 이 업무를 연결 담당으로 둔 카메라가 있을 때만 보인다. 데모·읽기 실패면 아무것도 보이지 않는다.
 */
export function LinkTaskNote({ taskId }: { taskId: string }) {
    const client = useContext(LiveContext)?.client ?? null;
    const [cameras, setCameras] = useState<string[]>([]);

    useEffect(() => {
        if (!client) return;
        const ac = new AbortController();
        new CameraLinkDesk(client.session, client.scope)
            .myLinks(ac.signal)
            .then((r) =>
                setCameras(r.cameras.filter((c) => c.linkTask?.id === taskId).map((c) => c.name)),
            )
            .catch(() => undefined);
        return () => ac.abort();
    }, [client, taskId]);

    if (cameras.length === 0) return null;
    return (
        <Note>
            카메라 연결 담당 업무입니다({cameras.join(', ')}). 노트북 WEB의 내 연결에서 연결하세요.
        </Note>
    );
}
