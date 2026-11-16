import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Browser } from '@capacitor/browser';
import { Link, useLocation, useNavigate } from 'react-router';
import { classifyFailure } from '../../shared/api/failure';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { useIdentity } from './context';
import { emptyConsents, signupNameError, trimSpaces } from './consent';
import { ConsentInputs } from './consent-inputs';
import type { Consents, OAuthPending } from './contract';
import { OAuthAttemptReplacedError, OAuthProofMissingError, type SocialProvider } from './session';
import { PROVIDER_NAMES, linkResultText } from './link-result';
import { saveTarget, takeTarget } from './social-target';
import { ANDROID_OAUTH_RETURN_PATH } from './android-oauth';
import { SocialLogo } from './social-logos';
import { loginFailureCause } from './login-error';
import { uiVariant } from '../../app/variant';
import styles from './social.module.css';

// 소셜 로그인(#285). BE 009 시작 → 제공자 → BE 010 callback → /login/oauth?ticket=… → 011 교환.
// - Android(#388)는 외부 브라우저(Custom Tabs)로 인증하고 App Link /app/oauth?ticket=… 로 앱에 돌아온다.
//   시작할 때 만든 증명을 세션이 교환에 더한다(android-oauth.ts). BE가 열기 전 빌드에서는 막아 둔다.
// - BE의 WEB 복귀지는 HOME 하나뿐이다. 원래 가려던 화면은 social-target.ts가 잠시 보관한다.
// - 연결된 소셜 계정은 바로 로그인된다. 미연결이면 011이 가입 계속(OAuthPending)을 돌려주고,
//   이름·필수 동의만 받아 가입한다(#379 SocialSignup). 기존 계정 연결은 내 정보 화면의 SocialLink(#227)에서 한다.

const PROVIDERS: { id: SocialProvider; label: string }[] = [
    { id: 'GOOGLE', label: 'Google로 계속하기' },
    { id: 'KAKAO', label: '카카오로 계속하기' },
    { id: 'NAVER', label: '네이버로 계속하기' },
];
/** APP 간편 로그인: 카카오·Google·네이버 순서, 동그라미 아래 이름(#402) */
const APP_PROVIDERS: { id: SocialProvider; name: string }[] = [
    { id: 'KAKAO', name: '카카오' },
    { id: 'GOOGLE', name: 'Google' },
    { id: 'NAVER', name: '네이버' },
];

export function SocialLogin({ target, disabled }: { target: string; disabled: boolean }) {
    const session = useIdentity();
    const [busy, setBusy] = useState<SocialProvider | null>(null);
    const [message, setMessage] = useState('');
    const inApp = session.channel !== 'WEB';
    // 이 화면이 아직 떠 있는가. 시작 응답이 늦게 와도 떠난 화면에서 브라우저를 열지 않는다.
    const alive = useRef(true);
    useEffect(() => {
        alive.current = true;
        return () => {
            alive.current = false;
        };
    }, []);
    // 사용자가 인증 없이 브라우저를 닫고 돌아오면 다시 누를 수 있게 한다.
    useEffect(() => {
        if (!inApp || !session.socialSupported) return;
        const handle = Browser.addListener('browserFinished', () => setBusy(null));
        return () => {
            void handle.then((listener) => listener.remove());
        };
    }, [inApp, session]);
    if (!session.socialSupported)
        return (
            <p className="small muted">
                앱에서는 아직 소셜 로그인을 지원하지 않습니다. 이메일로 로그인하거나 웹에서 소셜
                로그인을 이용해 주세요.
            </p>
        );
    async function start(provider: SocialProvider) {
        if (busy) return;
        setBusy(provider);
        setMessage('');
        try {
            const url = await session.oauthStart(provider);
            // 기다리는 동안 다른 화면으로 넘어갔으면(예: 이전 시도의 App Link 도착) 브라우저를 열지 않는다.
            if (!alive.current) return;
            saveTarget(target);
            // 앱은 WebView 안이 아니라 외부 브라우저에서 인증한다(제공자가 WebView 인증을 막는다).
            if (inApp) await Browser.open({ url });
            else window.location.assign(url);
        } catch (error) {
            if (!alive.current) return;
            // 그 사이 시작한 새 시도가 진행 중이다. 이 시도는 조용히 끝낸다.
            if (error instanceof OAuthAttemptReplacedError) return;
            setMessage(loginFailureCause(error, 'social'));
            setBusy(null);
        }
    }
    if (uiVariant === 'app') {
        const label = (id: SocialProvider) => PROVIDERS.find((p) => p.id === id)?.label ?? id;
        return (
            <section className={styles.app} aria-label="소셜 로그인">
                <p className={styles.divider}>간편 로그인</p>
                <div className={styles.appProviders}>
                    {APP_PROVIDERS.map((p) => (
                        <button
                            key={p.id}
                            type="button"
                            className={styles.appProvider}
                            aria-label={busy === p.id ? '이동 중입니다' : label(p.id)}
                            aria-busy={busy === p.id}
                            disabled={disabled || busy !== null}
                            onClick={() => void start(p.id)}
                        >
                            <span className={`${styles.circle} ${styles[p.id]}`}>
                                <SocialLogo provider={p.id} />
                            </span>
                            <span aria-hidden="true">{p.name}</span>
                        </button>
                    ))}
                </div>
                <p className={styles.appGuide}>
                    처음이면 이름과 필수 동의만 입력하면 바로 가입됩니다
                </p>
                <LoginError cause={message} />
            </section>
        );
    }
    return (
        <section className="stack" aria-label="소셜 로그인">
            <div className={styles.providers}>
                {PROVIDERS.map((p) => (
                    <button
                        key={p.id}
                        type="button"
                        className={`${styles.provider} ${styles[p.id]}`}
                        aria-label={busy === p.id ? '이동 중입니다' : p.label}
                        title={p.label}
                        aria-busy={busy === p.id}
                        disabled={disabled || busy !== null}
                        onClick={() => void start(p.id)}
                    >
                        <SocialLogo provider={p.id} />
                    </button>
                ))}
            </div>
            <LoginError cause={message} />
        </section>
    );
}

/**
 * 로그인 실패를 "!로그인 불가 - 원인" 빨간 한 줄로 보인다. 원인이 없으면 아무것도 그리지 않는다.
 * text를 주면 그 문구를 같은 모양으로 보인다(예: "!비밀번호 오류 2회").
 */
export function LoginError({ cause, text: given }: { cause: string; text?: string }) {
    if (!cause) return null;
    const text = given ?? `!로그인 불가 - ${cause}`;
    return (
        <p className={styles.loginError} role="alert" title={text}>
            {text}
        </p>
    );
}

// 같은 티켓을 두 번 교환하지 않는다(개발 모드의 effect 이중 실행·뒤로가기로 다시 열린 경우).
const consumed = new Set<string>();

type ReturnState =
    | { kind: 'working' }
    | { kind: 'failed'; text: string; recheck: boolean }
    | { kind: 'signup'; pending: OAuthPending; target: string };

/**
 * 소셜 복귀 화면의 라우트. 이 화면이 떠 있는 동안 새 복귀 링크(다른 티켓·오류)가 오면 화면을 새로 시작한다(#388).
 * 화면은 처음 열린 주소만 읽고 곧 주소창에서 지우므로, 새로 시작하지 않으면 뒤에 온 링크(예: 늦은 이전 시도의
 * 실패 뒤에 도착한 현재 시도의 성공)를 교환하지 않는다. 주소창을 비우는 이동(빈 쿼리)으로는 새로 시작하지 않는다.
 */
export function OAuthReturnRoute() {
    const { search } = useLocation();
    const [seen, setSeen] = useState({ search, round: 0 });
    if (search && search !== seen.search) setSeen({ search, round: seen.round + 1 });
    return <OAuthReturnScreen key={seen.round} />;
}

export function OAuthReturnScreen() {
    const session = useIdentity();
    const navigate = useNavigate();
    const location = useLocation();
    // 처음 열린 주소의 결과만 쓴다. 아래 effect가 곧 주소창에서 지운다.
    const [initial] = useState(() => {
        const params = new URLSearchParams(location.search);
        return { ticket: params.get('ticket'), error: params.get('error') };
    });
    const inApp = session.channel !== 'WEB';
    const [state, setState] = useState<ReturnState>(() =>
        initial.ticket && !initial.error
            ? { kind: 'working' }
            : {
                  kind: 'failed',
                  recheck: false,
                  text:
                      initial.error === 'access_denied'
                          ? '소셜 로그인을 취소했거나 제공자가 허용하지 않았습니다.'
                          : '소셜 로그인 결과를 받지 못했습니다. 로그인 화면에서 다시 시작해 주세요.',
              },
    );

    useEffect(() => {
        const { ticket, error } = initial;
        // 티켓을 주소창·방문 기록에 남기지 않는다.
        if (location.search) navigate(location.pathname, { replace: true });
        // 실패·취소는 원래 가려던 화면을 지우지 않는다. 진행 중인 다른 시도가 쓸 수 있다(#388).
        if (error || !ticket) return;
        if (consumed.has(ticket)) return;
        consumed.add(ticket);
        session.oauthLogin(ticket).then(
            (outcome) => {
                // 이 시도의 결과로 쓸 때만 꺼낸다. 가입으로 이어지면 화면 상태로 들고 간다.
                const target = takeTarget();
                if (outcome.kind === 'authenticated') navigate(target, { replace: true });
                else setState({ kind: 'signup', pending: outcome.pending, target });
            },
            (e: unknown) => {
                const failure = classifyFailure(e);
                if (e instanceof OAuthAttemptReplacedError)
                    setState({
                        kind: 'failed',
                        recheck: false,
                        text: '이전에 시작한 소셜 로그인의 결과라 반영하지 않았습니다. 마지막으로 시작한 로그인을 마쳐 주세요.',
                    });
                else if (e instanceof OAuthProofMissingError)
                    setState({
                        kind: 'failed',
                        recheck: false,
                        text: '이 앱에서 시작한 소셜 로그인 기록이 없거나 시간이 지났습니다. 로그인 화면에서 다시 시작해 주세요.',
                    });
                // 응답을 잃었으면 같은 티켓을 다시 쓰지 않고 현재 로그인 상태만 다시 확인하게 한다(D09).
                // 앱은 Cookie 세션이 없어 확인할 상태가 없으므로 다시 시작하게 한다.
                else if (
                    !inApp &&
                    (failure.kind === 'unknown-outcome' || failure.kind === 'unavailable')
                )
                    setState({
                        kind: 'failed',
                        recheck: true,
                        text: '로그인 결과를 확인하지 못했습니다. 현재 로그인 상태를 다시 확인해 주세요.',
                    });
                else if (failure.kind === 'unauthenticated')
                    setState({
                        kind: 'failed',
                        recheck: false,
                        text: '로그인 시간이 지났거나 이미 처리된 요청입니다. 로그인 화면에서 소셜 로그인을 다시 시작해 주세요.',
                    });
                else
                    setState({
                        kind: 'failed',
                        recheck: false,
                        text: inApp
                            ? '소셜 로그인을 마치지 못했습니다. 로그인 화면에서 다시 시작해 주세요.'
                            : '소셜 로그인을 마치지 못했습니다. 로그인을 시작한 브라우저에서 다시 시도해 주세요.',
                    });
            },
        );
        // 처음 열렸을 때 한 번만 처리한다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (state.kind === 'signup')
        return <SocialSignup pending={state.pending} target={state.target} />;
    return (
        <EntryFrame title="소셜 로그인">
            {state.kind === 'working' ? (
                <p className="small" role="status">
                    로그인을 마무리하고 있습니다.
                </p>
            ) : (
                <div className="stack">
                    <p className="small" role="status">
                        {state.text}
                    </p>
                    {state.recheck && (
                        <button
                            type="button"
                            className="btn primary block"
                            onClick={() => window.location.assign('/account/events')}
                        >
                            로그인 상태 확인
                        </button>
                    )}
                    <Link to="/login">로그인 화면으로</Link>
                </div>
            )}
        </EntryFrame>
    );
}

/**
 * 앱 복귀 주소(/app/oauth)가 앱 대신 브라우저에 열렸을 때의 WEB 화면(#388).
 * App Link 확인 실패나, 제공자가 사용자 조작 없이 바로 넘겨 Chrome이 앱을 열지 않은 경우다.
 * 티켓은 앱의 시작 증명 없이는 교환되지 않으므로 여기서는 교환하지 않고, 버튼으로 앱을 다시 연다.
 * 버튼을 누르는 조작이 있어야 Chrome이 App Link로 앱을 연다.
 */
export function AppReturnScreen() {
    const navigate = useNavigate();
    const location = useLocation();
    // 처음 열린 주소로 앱 복귀 링크를 만든다. 티켓·오류 외의 값은 옮기지 않는다.
    const [appUrl] = useState(() => {
        const params = new URLSearchParams(location.search);
        const next = new URLSearchParams();
        for (const key of ['ticket', 'error'] as const) {
            const value = params.get(key);
            if (value) next.set(key, value);
        }
        const query = next.toString();
        return `${window.location.origin}${ANDROID_OAUTH_RETURN_PATH}${query ? `?${query}` : ''}`;
    });
    useEffect(() => {
        // 티켓을 주소창·방문 기록에 남기지 않는다. 버튼 주소는 위에서 따로 들고 있다.
        if (location.search) navigate(ANDROID_OAUTH_RETURN_PATH, { replace: true });
        // 처음 열렸을 때 한 번만 정리한다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return (
        <EntryFrame title="앱으로 돌아가기">
            <div className="stack">
                <p className="small" role="status">
                    SceneCrew 앱에서 시작한 소셜 로그인입니다. 아래 버튼을 눌러 앱으로 돌아가
                    로그인을 마쳐 주세요.
                </p>
                <a className="btn primary block" href={appUrl}>
                    앱으로 돌아가기
                </a>
                <p className="small muted">
                    앱이 열리지 않으면 앱에서 소셜 로그인을 다시 시작해 주세요. 이 화면에서는
                    로그인되지 않습니다.
                </p>
            </div>
        </EntryFrame>
    );
}

/** 로그인 화면 주소. 초대 링크로 가던 중이면 이메일 로그인 뒤에도 그 화면으로 돌아간다. */
function loginPath(target: string) {
    return target.startsWith('/invites/')
        ? `/login?returnTo=${encodeURIComponent(target)}`
        : '/login';
}

type SignupState =
    { kind: 'form'; message: string } | { kind: 'sending' } | { kind: 'ended'; text: string };

/**
 * 미연결 소셜 계정의 가입 계속 화면(#379). 이름·필수 동의만 받아 가입하고, 재로그인 없이 원래 화면으로 간다.
 * 기존 계정 연결에는 이메일 정보를 쓰지 않는다(D09). 기존 계정으로 로그인한 뒤 내 정보에서 연결하도록 안내한다.
 */
function SocialSignup({ pending, target }: { pending: OAuthPending; target: string }) {
    const session = useIdentity();
    const navigate = useNavigate();
    const [name, setName] = useState('');
    const [consent, setConsent] = useState<Consents>(emptyConsents);
    const [state, setState] = useState<SignupState>({ kind: 'form', message: '' });
    const provider = pending.provider ? PROVIDER_NAMES[pending.provider] : '소셜';
    const canSignup = pending.availableActions.includes('COMPLETE_SIGNUP');
    const canLogin = pending.availableActions.includes('LOGIN_EXISTING');
    const until = pending.signupExpiresAt
        ? new Date(pending.signupExpiresAt).toLocaleTimeString('ko-KR', {
              hour: '2-digit',
              minute: '2-digit',
          })
        : null;

    async function submit(e: FormEvent) {
        e.preventDefault();
        if (state.kind !== 'form') return;
        const nameError = signupNameError(name);
        if (nameError) return setState({ kind: 'form', message: nameError });
        if (!consent.service)
            return setState({ kind: 'form', message: '서비스 이용 동의는 필수입니다.' });
        setState({ kind: 'sending' });
        try {
            await session.completeSocialSignup(pending.signupTicket, trimSpaces(name), consent);
            navigate(target, { replace: true });
        } catch (error) {
            const failure = classifyFailure(error);
            // 입력 오류(400 INVALID_REQUEST)는 서버가 티켓을 쓰기 전에 거절한 것이라 고쳐서 같은 티켓으로
            // 다시 보낼 수 있다. 그 밖의 400(인증 Cookie 중복·채널 오류)은 고쳐 보내도 같으므로 다시 시작하게 한다.
            if (
                failure.kind === 'rejected' &&
                failure.status === 400 &&
                failure.code === 'INVALID_REQUEST'
            )
                setState({ kind: 'form', message: '이름과 필수 동의를 다시 확인해 주세요.' });
            else if (failure.kind === 'rejected' && failure.status === 400)
                setState({
                    kind: 'ended',
                    text: '로그인 정보를 확인하지 못했습니다. 로그인 화면에서 소셜 로그인을 다시 시작해 주세요.',
                });
            else if (error instanceof OAuthAttemptReplacedError)
                setState({
                    kind: 'ended',
                    text: '그 사이 새 소셜 로그인을 시작해 이 가입은 마칠 수 없습니다. 마지막으로 시작한 로그인을 마쳐 주세요.',
                });
            else if (error instanceof OAuthProofMissingError)
                setState({
                    kind: 'ended',
                    text: '이 앱에서 시작한 소셜 로그인 기록이 없거나 시간이 지났습니다. 로그인 화면에서 소셜 로그인을 다시 시작해 주세요.',
                });
            else if (failure.kind === 'rate-limited')
                setState({ kind: 'form', message: '요청이 많습니다. 잠시 뒤 다시 시도해 주세요.' });
            else if (failure.kind === 'unauthenticated')
                setState({
                    kind: 'ended',
                    text: '가입 시간이 지났거나 이미 처리된 요청입니다. 로그인 화면에서 소셜 로그인을 다시 시작해 주세요.',
                });
            else if (failure.kind === 'rejected' && failure.status === 409)
                setState({
                    kind: 'ended',
                    text: '이 소셜 계정은 이미 SceneCrew 계정에 연결되어 있습니다. 로그인 화면에서 같은 소셜 계정으로 다시 로그인해 주세요.',
                });
            else
                setState({
                    kind: 'ended',
                    text: '가입 결과를 확인하지 못했습니다. 로그인 화면에서 같은 소셜 계정으로 다시 로그인해 주세요. 가입이 끝났다면 바로 로그인됩니다.',
                });
        }
    }

    if (state.kind === 'ended')
        return (
            <EntryFrame title="SceneCrew 가입">
                <div className="stack">
                    <p className="small" role="status">
                        {state.text}
                    </p>
                    <Link to={loginPath(target)}>로그인 화면으로</Link>
                </div>
            </EntryFrame>
        );
    return (
        <EntryFrame title="SceneCrew 가입">
            <div className="stack">
                <p className="small">
                    {provider} 계정이 아직 SceneCrew 계정에 연결되어 있지 않습니다.
                    {until && ` ${until}까지 가입을 마쳐 주세요.`}
                </p>
                {canSignup && (
                    <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
                        <label className="field">
                            <span>이름</span>
                            <input
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                autoComplete="name"
                                required
                            />
                        </label>
                        <ConsentInputs value={consent} change={setConsent} />
                        <p className="small muted">서비스 이용 동의만 필수입니다.</p>
                        {state.kind === 'form' && state.message && (
                            <p className="small" role="alert">
                                {state.message}
                            </p>
                        )}
                        <button
                            type="submit"
                            className="btn primary block"
                            disabled={state.kind === 'sending'}
                        >
                            {state.kind === 'sending' ? '가입하는 중입니다' : '가입하고 시작하기'}
                        </button>
                    </form>
                )}
                {canLogin && (
                    <section className="stack" aria-label="기존 계정">
                        <b className="small">이미 SceneCrew 계정이 있나요?</b>
                        <p className="small muted">
                            기존 계정으로 로그인한 뒤 내 정보에서 {provider} 계정을 연결하면
                            다음부터 {provider} 로그인으로 바로 들어올 수 있습니다.
                        </p>
                        <Link to={loginPath(target)}>기존 계정으로 로그인</Link>
                    </section>
                )}
            </div>
        </EntryFrame>
    );
}

// 소셜 계정 연결(#227). 내 정보 화면에서 비밀번호로 본인 확인(BE 014) → 연결 시작(BE 015) → 제공자 →
// BE callback이 연결을 끝내고 /account/profile?link=…&result=… 로 돌려보낸다. WEB만 지원한다.
type Pending = { provider: SocialProvider; action: 'link' | 'unlink' };

/** 재확인·연결·해제 실패 안내. 서버 오류 코드만 보고 고정 문구를 고른다. */
function failureText(error: unknown, action: Pending['action']) {
    const failure = classifyFailure(error);
    const code = 'code' in failure ? failure.code : undefined;
    if (code === 'INVALID_CREDENTIALS') return '비밀번호가 맞지 않습니다.';
    if (code === 'LAST_IDENTITY')
        return '마지막 로그인 수단이라 해제할 수 없습니다. 다른 로그인 수단을 먼저 연결해 주세요.';
    if (code === 'NOT_FOUND') return '연결되어 있지 않은 소셜 계정입니다.';
    if (failure.kind === 'rate-limited') return '시도가 많습니다. 잠시 뒤 다시 시도해 주세요.';
    if (failure.kind === 'unauthenticated')
        return '로그인이 만료되었습니다. 다시 로그인한 뒤 시도해 주세요.';
    return action === 'link'
        ? '연결을 시작하지 못했습니다. 잠시 뒤 다시 시도해 주세요.'
        : '연결을 해제하지 못했습니다. 잠시 뒤 다시 시도해 주세요.';
}

export function SocialLink() {
    const session = useIdentity();
    const navigate = useNavigate();
    const location = useLocation();
    // 처음 열린 주소의 결과만 쓰고 주소창에서는 지운다.
    const [message, setMessage] = useState(() => {
        const params = new URLSearchParams(location.search);
        return linkResultText(params.get('link'), params.get('result'));
    });
    const [pending, setPending] = useState<Pending | null>(null);
    const [busy, setBusy] = useState(false);
    useEffect(() => {
        if (new URLSearchParams(location.search).has('link'))
            navigate(location.pathname, { replace: true });
        // 처음 열렸을 때 한 번만 정리한다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    if (session.channel !== 'WEB')
        return (
            <p className="small muted">
                앱에서는 아직 소셜 계정 연결을 지원하지 않습니다. 웹의 내 정보 화면에서 연결해
                주세요.
            </p>
        );
    async function submit({ provider, action }: Pending, password: string) {
        if (busy) return;
        setBusy(true);
        setMessage('');
        try {
            if (action === 'link') {
                await session.reauthenticate(provider, password, 'LINK_IDENTITY');
                window.location.assign(await session.linkStart(provider));
                return;
            }
            await session.reauthenticate(provider, password, 'UNLINK_IDENTITY');
            await session.unlink(provider);
            setPending(null);
            setMessage(
                `${PROVIDER_NAMES[provider]} 연결을 해제했습니다. 이제 ${PROVIDER_NAMES[provider]}로는 로그인할 수 없습니다.`,
            );
        } catch (error) {
            setMessage(failureText(error, action));
        }
        setBusy(false);
    }
    const name = pending ? PROVIDER_NAMES[pending.provider] : '';
    return (
        <section className={`stack ${styles.linkList}`} aria-label="소셜 계정 연결">
            {pending === null ? (
                (Object.keys(PROVIDER_NAMES) as SocialProvider[]).map((p) => (
                    // 한 줄: 제공자 로고 · 연결 · 해제(버튼 이름은 읽기 도구에 제공자까지 읽힌다)
                    <div className={styles.linkRow} key={p}>
                        <span
                            className={`${styles.linkLogo} ${styles[p]}`}
                            role="img"
                            aria-label={PROVIDER_NAMES[p]}
                            title={PROVIDER_NAMES[p]}
                        >
                            <SocialLogo provider={p} />
                        </span>
                        <button
                            type="button"
                            className="btn"
                            aria-label={`${PROVIDER_NAMES[p]} 계정 연결`}
                            onClick={() => {
                                setMessage('');
                                setPending({ provider: p, action: 'link' });
                            }}
                        >
                            연결
                        </button>
                        <button
                            type="button"
                            className="btn"
                            aria-label={`${PROVIDER_NAMES[p]} 연결 해제`}
                            onClick={() => {
                                setMessage('');
                                setPending({ provider: p, action: 'unlink' });
                            }}
                        >
                            해제
                        </button>
                    </div>
                ))
            ) : (
                <form
                    className="stack"
                    onSubmit={(e) => {
                        e.preventDefault();
                        const password = String(new FormData(e.currentTarget).get('linkPassword'));
                        void submit(pending, password);
                    }}
                >
                    <div className="field">
                        <label htmlFor="linkPassword">
                            {pending.action === 'link'
                                ? `${name} 연결을 위해 비밀번호 확인`
                                : `${name} 연결 해제를 위해 비밀번호 확인`}
                        </label>
                        <input
                            id="linkPassword"
                            name="linkPassword"
                            type="password"
                            autoComplete="current-password"
                            required
                        />
                    </div>
                    <button className="btn primary block" disabled={busy}>
                        {busy
                            ? '처리 중입니다'
                            : pending.action === 'link'
                              ? `${name}로 이동해 연결`
                              : `${name} 연결 해제`}
                    </button>
                    <button
                        type="button"
                        className="btn block"
                        disabled={busy}
                        onClick={() => setPending(null)}
                    >
                        취소
                    </button>
                </form>
            )}
            {message && (
                <p className="small" role="status">
                    {message}
                </p>
            )}
        </section>
    );
}
