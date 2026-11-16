import type { ReactNode } from 'react';
import { Icon } from '../../shared/ui/Icon';
import styles from './entry-page.module.css';

/**
 * 공연 밖 APP 화면(공연 목록·초대 참여·회원가입·비밀번호 찾기)의 틀: 파란 머리줄 + 흰 본문 + 아래 고정 줄.
 * back이 있으면 머리줄은 뒤로 + 제목, 없으면 서비스 이름만 둔다. 공연 안 화면의 AppShell 머리줄과 같은 높이·색이다.
 */
export function AppEntryPage({
    title,
    back,
    head,
    foot,
    children,
}: {
    /** 머리줄 제목(back과 함께). 없으면 서비스 이름 */
    title?: string;
    back?: () => void;
    /** 머리줄 오른쪽 끝 */
    head?: ReactNode;
    /** 아래 고정 줄(주 버튼 등) */
    foot?: ReactNode;
    children: ReactNode;
}) {
    return (
        <div className={styles.screen} data-ui="app" data-platform="android">
            <header className={styles.top}>
                {back && (
                    <button type="button" className={styles.back} aria-label="뒤로" onClick={back}>
                        <Icon name="back" />
                    </button>
                )}
                {back && title ? (
                    <h1 className={styles.title}>{title}</h1>
                ) : (
                    <b className={styles.brand}>SceneCrew</b>
                )}
                {head}
            </header>
            <main id="main" className={styles.main} tabIndex={-1}>
                {children}
            </main>
            {foot && <div className={styles.foot}>{foot}</div>}
        </div>
    );
}
