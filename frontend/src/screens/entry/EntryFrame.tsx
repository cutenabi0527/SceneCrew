import type { ReactNode } from 'react';
import { uiVariant } from '../../app/variant';
import { LoginLanding } from './LoginLanding';
import { LoginWordmark } from './LoginWordmark';
import styles from './entry.module.css';

/**
 * 행사 밖 화면(로그인·행사 목록)의 바깥 틀만 가른다.
 * WEB·APP 모두 파란 배경 + 흰 카드. APP은 폭이 좁아 카드가 화면 폭을 채우고 여백만 줄인다.
 * APP 로그인은 카드 없이 워드마크 아래에 입력을 바로 놓고, 맨 아래 링크가 화면 바닥에 붙는다.
 */
export function EntryFrame({
    title,
    sub,
    landing,
    action,
    children,
}: {
    title: string;
    sub?: string;
    /** 제목 줄 오른쪽 끝에 놓는 작은 버튼(예: 새로고침 아이콘) */
    action?: ReactNode;
    /** 로그인 화면: WEB은 서비스 소개 페이지까지, APP은 배경 워드마크만 함께 보인다 */
    landing?: boolean;
    children: ReactNode;
}) {
    if (uiVariant === 'app') {
        return (
            <div
                className={`${styles.app} ${landing ? styles.appLanding : ''}`}
                data-ui="app"
                data-platform="android"
            >
                {/* 로그인은 머리줄 없이 입체 워드마크가 서비스 이름을 대신한다(#402) */}
                {!landing && (
                    <header className={styles.appTop}>
                        <b className={styles.wordmark}>SceneCrew</b>
                    </header>
                )}
                {landing && <LoginWordmark compact />}
                <main id="main" className={styles.appBody} tabIndex={-1}>
                    <section className={styles.appCard}>
                        {landing ? (
                            <h1 className="sr-only">{title}</h1>
                        ) : (
                            <CardTitle title={title} action={action} />
                        )}
                        {sub && !landing && <p className="small muted">{sub}</p>}
                        {children}
                    </section>
                </main>
            </div>
        );
    }
    if (landing) {
        return (
            <LoginLanding title={title} sub={sub}>
                {children}
            </LoginLanding>
        );
    }
    return (
        <div className={styles.web} data-ui="web" data-platform="web">
            <header className={styles.webTop}>
                <b className={styles.wordmark}>SceneCrew</b>
            </header>
            <main id="main" className={styles.webMain} tabIndex={-1}>
                <section className={styles.webCard}>
                    <CardTitle title={title} action={action} />
                    {sub && <p className="small muted">{sub}</p>}
                    {children}
                </section>
            </main>
        </div>
    );
}

function CardTitle({ title, action }: { title: string; action?: ReactNode }) {
    if (!action) return <h1 className="h2">{title}</h1>;
    return (
        <div className={styles.cardHead}>
            <h1 className="h2">{title}</h1>
            {action}
        </div>
    );
}
