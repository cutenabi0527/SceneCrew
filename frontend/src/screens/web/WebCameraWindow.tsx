import { useParams } from 'react-router';
import { useData, useEvent, useViewer } from '../../domain/hooks';
import { cameraViewable } from '../../domain/selectors';
import { CameraFeed, CameraWall } from '../../features/safety/CameraWall';
import { Empty } from '../../shared/ui/atoms';
import styles from './web.module.css';

/**
 * 카메라 한 대를 따로 띄운 새 창(W05·총괄). 메뉴 없이 그 카메라의 현재 미리보기만 크게 보인다.
 * 총괄·운영 중이 아니면 열지 않고, 창을 닫으면 세션이 끝난다(녹화·다운로드 없음).
 */
export function WebCameraWindow() {
    const { cameraId } = useParams();
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const cam = d.cameras.find((c) => c.id === cameraId);
    let body;
    if (!v.isChief) body = <Empty icon="lock">카메라 화면은 총괄만 볼 수 있습니다</Empty>;
    else if (!cameraViewable(ev.state))
        body = <Empty icon="lock">참여 모집 중이거나 운영 중인 행사에서만 볼 수 있습니다</Empty>;
    else if (!cam) body = <Empty>카메라를 찾을 수 없습니다</Empty>;
    else body = <CameraFeed camera={cam} large />;
    return (
        <main className={styles.cameraWindow} data-ui="web">
            <p className="xs muted">{ev.name}</p>
            {body}
        </main>
    );
}

/** 모든 카메라 새 창(/cameras): 분할 화면 + 카메라 목록. 총괄·운영 중일 때만 연다 */
export function WebCameraWallWindow() {
    const ev = useEvent();
    const v = useViewer();
    if (!v.isChief) return <Empty icon="lock">카메라 화면은 총괄만 볼 수 있습니다</Empty>;
    if (!cameraViewable(ev.state))
        return <Empty icon="lock">참여 모집 중이거나 운영 중인 행사에서만 볼 수 있습니다</Empty>;
    return (
        <div data-ui="web">
            <CameraWall />
        </div>
    );
}
