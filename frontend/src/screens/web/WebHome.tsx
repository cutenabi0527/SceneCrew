import type { ReactNode } from 'react';
import { useMyTasks, usePhase, useViewer } from '../../domain/hooks';
import { GroupSummary, MyStatusCard } from '../../features/member/MemberParts';
import { MemberTree } from '../../features/member/MemberTree';
import { TaskClock } from '../../features/task/TaskClock';
import { Chip } from '../../shared/ui/atoms';
import styles from './web.module.css';

/**
 * W02 운영 현황(총괄): 네비 바 아래를 구성도 캔버스로 꽉 채운다(탭 없음).
 * 구성도(총괄 → 직무 그룹 → 팀원)는 평면 분류이며 팀장·그룹 승인 계층이 아니다.
 * 팀원을 다른 그룹으로 끌어 소속을 옮기고, 그룹 카드의 +·맨 밑 빈자리로 인원을 초대한다.
 * 팀원 WEB: 내 참여 상태 + 내 업무(M01과 같은 시계) + 그룹별 집계.
 */
export function WebHome() {
    const v = useViewer();
    const { now, next } = usePhase();
    if (!v.isChief)
        return (
            <WorkerHome
                phase={
                    <div className="row">
                        {now && <Chip kind="accent">진행 중 / {now.name}</Chip>}
                        {next && (
                            <Chip kind="blue">
                                다음 {next.start} / {next.name}
                            </Chip>
                        )}
                    </div>
                }
            />
        );
    return <MemberTree />;
}

function WorkerHome({ phase }: { phase: ReactNode }) {
    const { all } = useMyTasks();
    return (
        <div className={styles.homeGrid}>
            <div className="stack">
                {phase}
                {/* 내 소속·안내는 달력 제목과 시계/목록 전환 사이 줄에 둔다 */}
                <TaskClock tasks={all} header={<MyStatusCard bare />} />
            </div>
            {/* 왼쪽 긴 세로줄로 내 업무 영역과 나눈다 */}
            <section className={`stack s6 ${styles.homeSide}`}>
                <b>그룹별 현황</b>
                <GroupSummary flat />
            </section>
        </div>
    );
}
