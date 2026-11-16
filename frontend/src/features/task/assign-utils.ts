import { EVENT_DATE } from '../../domain/seed';
import { toMin } from '../../domain/selectors';
import type { Member, Task } from '../../domain/types';

/** 공연 계획 큐시트 배정이 쓰는 도우미 */

/** 끌어 놓기로 옮기는 업무 id를 싣는 형식 */
export const MIME = 'application/x-scenecrew-task';

export const dayOf = (t: Task) => t.date ?? EVENT_DATE;

/** 끝났거나 취소된 업무는 다시 배정하지 않는다 */
export const movable = (t: Task) => t.status !== 'done' && t.status !== 'canceled';

/** 같은 날 시간이 겹치는 그 사람의 다른 업무 */
export const overlaps = (tasks: Task[], t: Task, memberId: string) =>
    tasks.filter(
        (x) =>
            x.id !== t.id &&
            x.assignee === memberId &&
            movable(x) &&
            dayOf(x) === dayOf(t) &&
            toMin(x.start) < toMin(t.end) &&
            toMin(t.start) < toMin(x.end),
    );

/** 팀원 보조 설명: 예비 / 지금 불가 / 가용 시간 */
export function memberSub(m: Member) {
    return [m.isReserve ? '예비' : '', m.available ? '' : '지금 불가', m.avail]
        .filter(Boolean)
        .join(' / ');
}
