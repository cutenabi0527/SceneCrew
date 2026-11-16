/**
 * 그룹 순서로 색을 정한다. 색상환을 황금각만큼 돌려 그룹이 늘어나도 서로 다른 색이 된다.
 * 업무 조정의 시간표와 업무 목록이 같은 색을 쓴다.
 */
export function groupColor(groups: { id: string }[], groupId: string) {
    const index = groups.findIndex((g) => g.id === groupId);
    return index < 0 ? 'var(--muted)' : `hsl(${(210 + index * 137.5) % 360} 62% 40%)`;
}
