import { useMemo } from 'react';
import {
    changeStaleReasons,
    currentPhase,
    diffRows,
    openTasksOf,
    responseQueues,
    tasksOf,
    unreadNotices,
    viewer,
    visibleRooms,
} from './selectors';
import { useDemoState } from './store-context';

// 데이터 조회 공유 계층. WEB/APP 화면은 모두 이 훅만 부르고, 서버 연동 시 내부만 TanStack Query로 바꾼다.

export function useSession() {
    const s = useDemoState();
    return { me: s.me, eventId: s.eventId, events: s.events };
}

export function useEvent() {
    const s = useDemoState();
    const ev = s.events.find((e) => e.id === (s.eventId ?? 'e1')) ?? s.events[0];
    return ev;
}

export function useData() {
    return useDemoState().data;
}

export function useViewer() {
    const s = useDemoState();
    return useMemo(() => viewer(s.data, s.me ?? 'm1'), [s.data, s.me]);
}

export function useMyTasks() {
    const s = useDemoState();
    const me = s.me ?? '';
    return useMemo(
        () => ({ all: tasksOf(s.data, me), open: openTasksOf(s.data, me) }),
        [s.data, me],
    );
}

export function useTask(id: string | undefined) {
    const d = useData();
    return d.tasks.find((t) => t.id === id);
}

export function useResponseQueues() {
    const s = useDemoState();
    return useMemo(() => responseQueues(s.data, s.me ?? ''), [s.data, s.me]);
}

export function useIssue(id: string | undefined) {
    const d = useData();
    return d.issues.find((i) => i.id === id);
}

/** 변경안 + 전후 행 + 낡음 여부. 표(WEB 검토)와 카드(APP 본인 확인)가 같은 결과를 그린다. */
export function useChangeReview(id: string | undefined) {
    const s = useDemoState();
    const ev = useEvent();
    return useMemo(() => {
        const change = s.data.changes.find((c) => c.id === id);
        if (!change) return null;
        // 승인 직전 검사와 같은 이유 목록. 하나라도 있으면 낡은 변경안이다
        const reasons = changeStaleReasons(s.data, ev, change);
        return {
            change,
            rows: diffRows(s.data, ev, change),
            reasons,
            stale: reasons.length > 0,
        };
    }, [s.data, ev, id]);
}

export function usePhase() {
    const ev = useEvent();
    return useMemo(() => currentPhase(ev), [ev]);
}

export function useRooms() {
    const s = useDemoState();
    const me = s.me ?? '';
    return useMemo(
        () =>
            visibleRooms(s.data, me).map((r) => {
                const msgs = s.data.messages.filter((m) => m.room === r.id);
                return { room: r, last: msgs[msgs.length - 1] };
            }),
        [s.data, me],
    );
}

export function useRoomMessages(roomId: string | undefined) {
    const d = useData();
    return useMemo(
        () => d.messages.filter((m) => m.room === roomId).sort((a, b) => a.seq - b.seq),
        [d.messages, roomId],
    );
}

export function useNotices() {
    const s = useDemoState();
    return useMemo(
        () =>
            s.data.notices
                .filter((n) => n.to === s.me)
                .slice()
                .reverse(),
        [s.data.notices, s.me],
    );
}

/** 탭·메뉴 배지 숫자. 채팅은 개인 읽음 커서가 없어 숫자를 세지 않는다. */
export function useBadges() {
    const s = useDemoState();
    const q = useResponseQueues();
    const me = s.me ?? '';
    return {
        notices: unreadNotices(s.data, me),
        response: q.news.length + q.approve.length,
        today: s.data.notices.filter((n) => n.to === me && !n.ack).length,
    };
}

/** 내 참여 정보(그룹·예비·최초 배정 상태). 초대만 받은 계정은 member가 없다. */
export function useMe() {
    const s = useDemoState();
    const member = s.data.members.find((m) => m.id === s.me);
    const invitee = s.data.invitees.find((x) => x.id === s.me);
    const group = s.data.groups.find((g) => g.id === member?.groupId);
    return { member, invitee, group };
}
