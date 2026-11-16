/**
 * 그룹별 초대 링크: 공연 초대 링크에 group을 붙인다. 같은 그룹이면 늘 같은 링크이고, 받은 사람의 참여 화면은
 * 그 그룹을 골라 둔 채로 열린다(서버 초대 토큰은 공연 하나에 하나라 토큰은 같다).
 */
export function groupInviteUrl(url: string, groupId: string) {
    try {
        const u = new URL(url, window.location.origin);
        u.searchParams.set('group', groupId);
        return u.toString();
    } catch {
        return url;
    }
}
