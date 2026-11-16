import { useRef, type MouseEvent, type ReactNode } from 'react';
import { LoginWordmark } from './LoginWordmark';
import styles from './landing.module.css';

const FEATURES = [
    [
        '장비 복구 대안',
        '고장 난 장비의 대안을 계산하고, 시간표가 어떻게 바뀌는지 충돌까지 미리 비교합니다.',
    ],
    [
        '빠른 보고 / 음성 보고',
        '고정 문구나 30초 음성으로 현장 상황을 보고합니다. 음성은 전사를 확인한 뒤 제출합니다.',
    ],
    [
        'AI 대응안 / 변경 비교',
        '보고와 계획을 바탕으로 조정안을 만들고, 전후 값과 영향 인원을 확인한 뒤 총괄이 확정합니다.',
    ],
    [
        '변경사항 확인 기록',
        '알림 수신과 본인 확인을 따로 기록해, 누가 바뀐 계획을 숙지했는지 바로 압니다.',
    ],
];
const NUMBERS = [
    ['31', '하루 운영 팀 최대 인원'],
    ['200', '관리 가능한 업무 수'],
    ['5', '협업하는 전문 AI Agent'],
    ['30s', '음성 보고 최대 길이'],
];

/**
 * WEB 로그인 겸 서비스 소개 페이지: 맨 위 입체 워드마크와 로그인 카드, 아래로 소개·기능·화면·숫자.
 * 로그인 입력은 children으로 받아 실제 계정·데모 화면이 같은 틀을 쓴다. 화면 미리보기 값은 설명용 예시다.
 */
export function LoginLanding({
    title,
    sub,
    children,
}: {
    title: string;
    sub?: string;
    children: ReactNode;
}) {
    const tilt = useRef<HTMLDivElement>(null);
    const onTilt = (e: MouseEvent<HTMLElement>) => {
        const r = e.currentTarget.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5;
        const py = (e.clientY - r.top) / r.height - 0.5;
        tilt.current?.style.setProperty('--tilt-x', `${Math.round(-py * 22)}deg`);
        tilt.current?.style.setProperty('--tilt-y', `${Math.round(px * 28)}deg`);
    };
    const onTiltEnd = () => {
        tilt.current?.style.setProperty('--tilt-x', '0deg');
        tilt.current?.style.setProperty('--tilt-y', '0deg');
    };

    return (
        <div className={styles.page} data-ui="web" data-platform="web">
            <header className={styles.top}>
                <b className={styles.brand}>SceneCrew</b>
                <nav aria-label="소개 메뉴" className={styles.nav}>
                    <a href="#about">About</a>
                    <a href="#features">Features</a>
                    <a href="#screens">Screens</a>
                    <a href="#numbers">Numbers</a>
                </nav>
                <a href="#login" className={styles.pill}>
                    로그인
                </a>
            </header>

            <main id="main" tabIndex={-1}>
                <section
                    id="login"
                    aria-label="로그인"
                    className={styles.hero}
                    onMouseMove={onTilt}
                    onMouseLeave={onTiltEnd}
                >
                    <LoginWordmark ref={tilt} />
                    <section className={styles.card}>
                        <h1 className="h2">{title}</h1>
                        {sub && <p className="small muted">{sub}</p>}
                        {children}
                    </section>
                    <div aria-hidden="true" className={styles.heroFoot}>
                        <span>Concert operations, in sync</span>
                        <span>Scroll ↓</span>
                        <span>Seoul / 2026</span>
                    </div>
                </section>

                <section id="about" aria-label="소개" className={styles.about}>
                    <span className={`${styles.tag} ${styles.rv}`}>(01) About</span>
                    <h2 className={styles.aboutTitle}>
                        <span className={styles.clip}>공연은 계획대로</span>
                        <span className={styles.clip}>흘러가지 않습니다.</span>
                        <span className={styles.clip}>
                            그래서 <em>바뀐 것만</em>
                        </span>
                        <span className={styles.clip}>필요한 사람에게.</span>
                    </h2>
                    <p className={`${styles.aboutA} ${styles.rv}`}>
                        씬크루는 공연 계획 / 직무별 업무 / 장비 / 안전 상황을 하나의 공연 공간에
                        둡니다. 현장 보고가 들어오면 AI 대응안을 만들고, 영향받는 사람에게만 변경을
                        보내고, 확인까지 기록합니다.
                    </p>
                    <p className={`${styles.aboutB} ${styles.rv}`}>
                        판단은 사람이 합니다. AI의 대안은 제안일 뿐이고, 총괄이 전후 값과 영향을
                        보고 승인하기 전에는 계획도 담당도 바뀌지 않습니다.
                    </p>
                </section>

                <section id="features" aria-label="기능" className={styles.features}>
                    <div className={styles.featuresHead}>
                        <span className={`${styles.tag} ${styles.rv}`}>(02) Features</span>
                        <span className={`${styles.display} ${styles.rv}`}>What it does</span>
                    </div>
                    {FEATURES.map(([name, text], k) => (
                        <a key={name} href="#screens" className={`${styles.feature} ${styles.rv}`}>
                            <span className={styles.num}>0{k + 1}</span>
                            <span className={styles.featureName}>{name}</span>
                            <span className={styles.featureText}>{text}</span>
                            <span className={styles.arrow} aria-hidden="true">
                                →
                            </span>
                        </a>
                    ))}
                </section>

                <section id="screens" aria-label="화면 미리보기" className={styles.screens}>
                    <div className={styles.screensPin}>
                        <div className={styles.screensHead}>
                            <span className={styles.tagBlue}>(03) Screens</span>
                            <span className={styles.display}>One space, every role</span>
                        </div>
                        <div className={styles.trackView}>
                            <div className={styles.track}>
                                <article className={styles.shot} data-tone="blue">
                                    <div className={styles.shotHead}>
                                        <span>01 / 장비 운영</span>
                                        <span>총괄</span>
                                    </div>
                                    <div className={styles.shotDark}>
                                        <div className={styles.lane}>
                                            <span>메인 마이크 A</span>
                                            <div className={styles.laneTrack}>
                                                <i style={{ left: '8%', width: '30%' }}>1곡 준비</i>
                                                <i style={{ left: '40%', width: '50%' }} data-on>
                                                    1, 2곡
                                                </i>
                                            </div>
                                        </div>
                                        <div className={styles.lane}>
                                            <span>예비 마이크 C</span>
                                            <div className={styles.laneTrack}>
                                                <i style={{ left: '30%', width: '34%' }} data-new>
                                                    + 교체 준비
                                                </i>
                                            </div>
                                        </div>
                                        <div className={styles.lane}>
                                            <span>메인 믹서</span>
                                            <div className={styles.laneTrack}>
                                                <i style={{ left: '0%', width: '100%' }}>
                                                    전체 공연
                                                </i>
                                            </div>
                                        </div>
                                    </div>
                                    <b className={styles.shotCaption}>복구 대안을 시간표로 비교</b>
                                </article>

                                <article className={styles.shot}>
                                    <div className={styles.shotHead}>
                                        <span>02 / 보고/대응</span>
                                        <span>총괄</span>
                                    </div>
                                    <div className={styles.shotList}>
                                        <div>
                                            <b>메인 마이크 소리가 나오지 않아요</b>
                                            <small data-kind="urgent">긴급</small>
                                        </div>
                                        <div>
                                            <span>스테이지 앞 관객이 몰려요</span>
                                            <small data-kind="normal">일반</small>
                                        </div>
                                        <div>
                                            <span>입장 동선 정리 완료</span>
                                            <small>해결</small>
                                        </div>
                                    </div>
                                    <b className={styles.shotCaption}>
                                        보고에서 확정까지 한 공간에서
                                    </b>
                                </article>

                                <article className={styles.shot} data-tone="blue">
                                    <div className={styles.shotHead}>
                                        <span>03 / 변경 비교</span>
                                        <span>현재 → 변경 후</span>
                                    </div>
                                    <div className={styles.shotDiff}>
                                        <div>
                                            <span>예비 마이크 교체</span>
                                            <span>
                                                18:25 → <b>18:29</b>
                                            </span>
                                        </div>
                                        <div>
                                            <span>1곡 음향 점검</span>
                                            <span>
                                                18:20 → <b>18:28</b>
                                            </span>
                                        </div>
                                        <div data-new>
                                            <span>예비 장비 회수</span>
                                            <span>신규 / 최도윤</span>
                                        </div>
                                    </div>
                                    <b className={styles.shotCaption}>바뀐 것만, 3명에게</b>
                                </article>

                                <article className={styles.shot}>
                                    <div className={styles.shotHead}>
                                        <span>04 / 안전</span>
                                        <span>구역별 추정</span>
                                    </div>
                                    <div className={styles.shotZones}>
                                        <div data-kind="over">
                                            <b>객석 A</b>
                                            <span>기준 초과</span>
                                        </div>
                                        <div>
                                            <b>객석 B</b>
                                            <span>추정 7명</span>
                                        </div>
                                        <div>
                                            <b>객석 C</b>
                                            <span>추정 3명</span>
                                        </div>
                                        <div data-kind="old">
                                            <b>입구</b>
                                            <span>낡은 관측</span>
                                        </div>
                                    </div>
                                    <b className={styles.shotCaption}>개인이 아닌 구역 단위로</b>
                                </article>
                            </div>
                        </div>
                    </div>
                </section>

                <section id="numbers" aria-label="숫자로 보는 씬크루" className={styles.numbers}>
                    <span className={`${styles.tag} ${styles.numbersTag} ${styles.rv}`}>
                        (04) Numbers
                    </span>
                    {NUMBERS.map(([value, label]) => (
                        <div key={label} className={`${styles.number} ${styles.scale}`}>
                            <b>{value}</b>
                            <span>{label}</span>
                        </div>
                    ))}
                </section>

                <section aria-label="시작하기" className={styles.start}>
                    <div aria-hidden="true" className={styles.spin}>
                        ✳ SCENECREW ✳
                    </div>
                    <span className={`${styles.tag} ${styles.rv}`}>(05) Get started</span>
                    <h2 className={`${styles.startTitle} ${styles.clipBlock}`}>
                        Ready
                        <br />
                        to crew?
                    </h2>
                    <a href="#login" className={styles.pillBig}>
                        로그인하러 가기 ↑
                    </a>
                    <div className={styles.foot}>
                        <span>© 2026 SceneCrew / A202</span>
                        <span>SSAFY 15기</span>
                        <a href="#login">Back to top ↑</a>
                    </div>
                </section>
            </main>
        </div>
    );
}
