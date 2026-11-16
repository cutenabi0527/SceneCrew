// /live/events/:eventId?next=… 로 공연을 연 뒤 갈 운영 화면(#362·#307).
// next=plan-draft&tab=pdf|direct|info|upload는 계획 입력의 하위 탭이다(V9-D65). direct는 정보 등록 탭으로 연다.
// next=notice&n=<알림 id>는 푸시를 누른 경우다. 알림함에서 그 알림을 찾아 연다(변경이면 변경 확인, 보고면 이슈).
// 알림 id는 형식만 검사한다. 찾지 못하면 알림함에 머문다.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function enterPath(
    next: string | null,
    notice: string | null,
    tab: string | null = null,
): string {
    // 계획 입력의 하위 탭(V9-D65). 정해진 두 값만 넘긴다.
    if (next === 'plan-draft')
        return tab === 'pdf' || tab === 'direct' || tab === 'info' || tab === 'upload'
            ? `/e/plan/draft?tab=${tab}`
            : '/e/plan/draft';
    if (next === 'notice')
        return notice && UUID.test(notice)
            ? `/e/notices?open=${encodeURIComponent(notice)}`
            : '/e/notices';
    return '/e/home';
}
