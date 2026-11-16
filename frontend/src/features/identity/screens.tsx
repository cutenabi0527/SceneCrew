import { useEffect, useId, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import type { CommandOutcome } from '../../shared/api/api';
import { asUuid } from '../../shared/api/contract';
import { classifyFailure } from '../../shared/api/failure';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { usePush } from '../push/context';
import type { PushStatus } from '../push/registrar';
import { useIdentity } from './context';
import type { Consents, EventSummary } from './contract';
import { EVENT_STATE_LABEL } from '../../domain/selectors';
import { consentLabels, emptyConsents } from './consent';
import { ConsentInputs } from './consent-inputs';
import { observe } from './observe';
import type { DeactivationReceipt, DeletionStatus } from './session';
import { LoginError, SocialLink, SocialLogin } from './social';
import styles from './profile.module.css';
import { LOGIN_MISMATCH, loginFailureCause } from './login-error';
import { EntryDialog } from './entry-dialog';
import { Icon } from '../../shared/ui/Icon';
import { Chip, Tabs } from '../../shared/ui/atoms';
import { handFiles, provisionalName } from '../pdf-quick/handoff';
import { scheduleLine } from './event-schedule';
import entryStyles from '../../screens/entry/entry.module.css';
import { uiVariant } from '../../app/variant';
import { AppEntryPage } from '../../screens/app/AppEntryPage';
import page from '../../screens/app/entry-page.module.css';
import appStyles from './app-entry.module.css';

import { isNative, platform } from '../../platform/runtime';
import { InviteAppGate } from '../distribution/InviteAppGate';
import { isAndroidBrowser } from '../distribution/open-in-app';

function failureMessage(error: unknown) {
    const failure = classifyFailure(error);
    if (failure.kind === 'cancelled') return '';
    if (failure.kind === 'unauthenticated') return '로그인 정보를 확인하거나 다시 로그인해주세요.';
    if (failure.kind === 'access-denied')
        return '현재 접근할 수 없습니다. 초대 또는 참여 상태를 다시 확인해주세요.';
    if (failure.kind === 'rate-limited') return '요청이 많습니다. 잠시 뒤 직접 다시 시도해주세요.';
    if (failure.kind === 'version-conflict')
        return '공연 정보가 바뀌었습니다. 최신 정보를 조회하고 다시 확인해주세요.';
    if (failure.intent === 'pending')
        return '처리 결과를 확인하지 못했습니다. 현재 상태를 조회해주세요. 자동으로 다시 보내지 않습니다.';
    return '요청을 처리하지 못했습니다. 입력과 현재 상태를 확인해주세요.';
}
function outcome<T>(value: CommandOutcome<T>) {
    if (value.status === 'succeeded') return value.data;
    if (value.status === 'failed') throw Object.assign(new Error('명령 실패'), { outcome: value });
    throw new Error('현재 상태 조회가 필요합니다.');
}
function actionMessage(error: unknown) {
    const value = (error as { outcome?: { failure: { kind: string } } })?.outcome;
    if (value?.failure.kind === 'version-conflict')
        return '공연 정보가 바뀌었습니다. 최신 정보를 조회하고 다시 확인해주세요.';
    if (value)
        return '처리 결과와 현재 참여 상태를 다시 조회해주세요. 같은 요청은 자동 재전송하지 않습니다.';
    return failureMessage(error);
}
function useAction(describe: (error: unknown) => string = actionMessage) {
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const pending = useRef(false);
    const mounted = useRef(true);
    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
        };
    }, []);
    async function run<T>(work: () => Promise<T>, success?: (value: T) => void) {
        if (pending.current) return;
        pending.current = true;
        setBusy(true);
        setMessage('');
        try {
            const value = await work();
            if (mounted.current) success?.(value);
        } catch (error) {
            if (mounted.current) setMessage(describe(error));
        } finally {
            pending.current = false;
            if (mounted.current) setBusy(false);
        }
    }
    return { busy, message, setMessage, run };
}
function Notice({ children }: { children: ReactNode }) {
    return (
        <p className="small" role="status">
            {children}
        </p>
    );
}
function Input({
    name,
    label,
    type = 'text',
    ...rest
}: { name: string; label: string } & React.InputHTMLAttributes<HTMLInputElement>) {
    // 로그인 폼과 가입·비밀번호 변경 팝업이 같은 이름의 입력을 함께 띄우므로 id를 겹치지 않게 만든다.
    const id = `${name}-${useId()}`;
    return (
        <div className="field">
            <label htmlFor={id}>{label}</label>
            <input id={id} name={name} type={type} required {...rest} />
        </div>
    );
}
function useAccount() {
    const session = useIdentity();
    return useSyncExternalStore(session.subscribe, session.snapshot);
}
function nextPath(value: string | null) {
    return value && /^\/invites\/[^/?#]+$/.test(value) ? value : '/account/events';
}
export function AccountGuard({ children }: { children: ReactNode }) {
    const { ready, account } = useAccount();
    if (!ready)
        return (
            <EntryFrame title="로그인 확인">
                <Notice>현재 계정을 확인하고 있습니다.</Notice>
            </EntryFrame>
        );
    return account ? <div key={account.id}>{children}</div> : <Navigate to="/login" replace />;
}
export function AccountLogin() {
    const session = useIdentity();
    const state = useAccount();
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const target = nextPath(params.get('returnTo'));
    const action = useAction(loginFailureCause);
    // 이메일·비밀번호 불일치 횟수. 매번 "!비밀번호 오류 N회"로 알리고,
    // 3번째부터는 비밀번호 재설정 팝업을 열고 다시 열 수 있는 버튼을 보인다.
    const [misses, setMisses] = useState(0);
    const [recovery, setRecovery] = useState<string | null>(null);
    // APP 로그인: 이름표 대신 칸 안 안내 글, 회원가입·비밀번호 찾기 링크, 맨 아래 작은 링크(#402)
    const app = uiVariant === 'app';
    const back = `returnTo=${encodeURIComponent(target)}`;
    function login(email: string, password: string) {
        void action.run(
            async () => {
                try {
                    return await session.authenticate('/auth/login', { email, password });
                } catch (error) {
                    // 서버는 가입 안 된 이메일과 틀린 비밀번호를 구분하지 않는다(401).
                    // 팝업 없이 아래 빨간 줄로 횟수만 알리고, 3번째 불일치부터 비밀번호 재설정 팝업을 연다.
                    if (classifyFailure(error).kind === 'unauthenticated') {
                        const next = misses + 1;
                        setMisses(next);
                        if (next >= 3) setRecovery(email);
                    }
                    throw error;
                }
            },
            () => navigate(target, { replace: true }),
        );
    }
    return (
        <EntryFrame landing title="로그인">
            {state.notice && <Notice>{state.notice}</Notice>}
            {state.account && (
                <p>
                    현재 계정: {state.account.displayName} <Link to={target}>계속하기</Link>
                </p>
            )}
            <form
                className={app ? appStyles.loginForm : 'stack'}
                onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    login(String(f.get('email') ?? ''), String(f.get('password') ?? ''));
                }}
            >
                <Input
                    name="email"
                    label="이메일"
                    type="email"
                    autoComplete="username"
                    placeholder={app ? '이메일' : undefined}
                />
                <Input
                    name="password"
                    label="비밀번호"
                    type="password"
                    minLength={8}
                    maxLength={64}
                    pattern="[!-~]+"
                    autoComplete="current-password"
                    placeholder={app ? '비밀번호' : undefined}
                />
                <button
                    className={app ? appStyles.loginBtn : 'btn primary block'}
                    disabled={action.busy || !state.ready}
                >
                    {app ? '이메일로 로그인' : '이메일로 계속하기'}
                </button>
                {action.busy ? (
                    <Notice>로그인 중입니다.</Notice>
                ) : (
                    <LoginError
                        cause={action.message}
                        text={
                            misses > 0 && action.message === LOGIN_MISMATCH
                                ? `!비밀번호 오류 ${misses}회`
                                : undefined
                        }
                    />
                )}
                {misses >= 3 && (
                    <button
                        type="button"
                        className="btn block"
                        onClick={(e) => {
                            const form = e.currentTarget.form;
                            const email = form ? String(new FormData(form).get('email') ?? '') : '';
                            setRecovery(email);
                        }}
                    >
                        비밀번호 재설정
                    </button>
                )}
            </form>
            {app && (
                <nav aria-label="계정" className={appStyles.links}>
                    <Link to={`/account/register?${back}`}>회원가입</Link>
                    <Link to={`/account/recovery?${back}`}>비밀번호 찾기</Link>
                </nav>
            )}
            {recovery !== null && (
                <EntryDialog title="비밀번호 재설정" onClose={() => setRecovery(null)}>
                    <RecoveryForm email={recovery} />
                </EntryDialog>
            )}
            <SocialLogin target={target} disabled={action.busy || !state.ready} />
            {/* 앱 안에서는 앱 받기가 필요 없다. 탈퇴 상태·데모는 맨 아래 작게 */}
            {app ? (
                <nav aria-label="그 밖의 메뉴" className={appStyles.footLinks}>
                    {/* 휴대폰 브라우저의 앱 화면(#402). Android면 앱 설치로 이어 준다 */}
                    {platform === 'web' && isAndroidBrowser(navigator.userAgent) && (
                        <Link to="/download">Android 앱 받기</Link>
                    )}
                    <Link to="/account/deletion-status">탈퇴 처리 상태</Link>
                    <Link to="/demo/login">데모 둘러보기</Link>
                </nav>
            ) : (
                <>
                    {!isNative && <Link to="/download">Android 앱 받기</Link>}
                    <Link to="/demo/login">데모 화면 둘러보기</Link>
                </>
            )}
        </EntryFrame>
    );
}
const PUSH_LABEL: Record<PushStatus, string> = {
    off: '확인 전',
    'needs-permission': '푸시 수신에 동의하면 알림 권한을 묻습니다',
    denied: '알림 권한이 꺼져 있습니다. 휴대폰 설정에서 켜 주세요',
    registering: '등록 중',
    registered: '받을 수 있음',
    failed: '등록하지 못했습니다. 잠시 뒤 다시 열어 주세요',
    unavailable: '이 앱 빌드에는 푸시 설정이 없어 알림을 받을 수 없습니다',
};
export function RegisterScreen() {
    const [params] = useSearchParams();
    const target = nextPath(params.get('returnTo'));
    const [title, setTitle] = useState('회원가입');
    if (uiVariant === 'app') return <AppRegister target={target} />;
    return (
        <EntryFrame title={title}>
            <RegisterForm target={target} onReceipt={() => setTitle('이메일 확인')} />
            <Link to={`/login?returnTo=${encodeURIComponent(target)}`}>로그인으로</Link>
        </EntryFrame>
    );
}
/** 이메일 회원가입 접수 → 확인 코드 입력. 회원가입 화면과 로그인 화면의 가입 팝업이 함께 쓴다. */
export function RegisterForm({
    target,
    email,
    onReceipt,
}: {
    target: string;
    /** 로그인 화면에서 입력한 이메일을 미리 채운다 */
    email?: string;
    onReceipt?: () => void;
}) {
    const session = useIdentity();
    const navigate = useNavigate();
    const [receipt, setReceipt] = useState<{ requestId: string; expiresAt: string } | null>(null);
    const [consent, setConsent] = useState<Consents>(emptyConsents);
    const action = useAction();
    return (
        <div className="stack">
            {!receipt ? (
                <form
                    className="stack"
                    onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        void action.run(
                            () =>
                                session.receipts.register({
                                    email: f.get('email'),
                                    password: f.get('password'),
                                    displayName: f.get('displayName'),
                                    consent,
                                }),
                            (r) => {
                                setReceipt(r);
                                onReceipt?.();
                            },
                        );
                    }}
                >
                    <Input
                        name="displayName"
                        label="표시 이름"
                        maxLength={20}
                        autoComplete="nickname"
                    />
                    <Input
                        name="email"
                        label="이메일"
                        type="email"
                        autoComplete="email"
                        defaultValue={email}
                    />
                    <Input
                        name="password"
                        label="비밀번호"
                        type="password"
                        minLength={8}
                        maxLength={64}
                        pattern="[!-~]+"
                        autoComplete="new-password"
                    />
                    <ConsentInputs value={consent} change={setConsent} />
                    <button className="btn primary" disabled={action.busy}>
                        가입 접수
                    </button>
                </form>
            ) : (
                <form
                    className="stack"
                    onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        void action.run(
                            () =>
                                session.authenticate('/auth/verify-email', {
                                    requestId: receipt.requestId,
                                    code: f.get('code'),
                                }),
                            () => navigate(target, { replace: true }),
                        );
                    }}
                >
                    <Notice>
                        가입 요청을 접수했습니다. 이메일의 확인 코드를 입력해주세요. 접수는 로그인
                        완료와 다릅니다.
                    </Notice>
                    <Input
                        name="code"
                        label="6자리 확인 코드"
                        inputMode="numeric"
                        pattern="[0-9]{6}"
                        maxLength={6}
                        autoComplete="one-time-code"
                    />
                    <button className="btn primary" disabled={action.busy}>
                        이메일 확인
                    </button>
                    <button
                        type="button"
                        className="btn"
                        disabled={action.busy}
                        onClick={() => {
                            void action.run(
                                () =>
                                    session.receipts.resendVerification({
                                        requestId: receipt.requestId,
                                    }),
                                (r) => {
                                    setReceipt(r);
                                    action.setMessage('확인 코드 재발송을 접수했습니다.');
                                },
                            );
                        }}
                    >
                        확인 코드 재발송
                    </button>
                </form>
            )}
            <Notice>{action.message}</Notice>
        </div>
    );
}
export function RecoveryScreen() {
    const [params] = useSearchParams();
    const navigate = useNavigate();
    if (uiVariant === 'app')
        return (
            <AppRecovery
                back={() =>
                    navigate(
                        `/login?returnTo=${encodeURIComponent(nextPath(params.get('returnTo')))}`,
                    )
                }
            />
        );
    return (
        <EntryFrame title="비밀번호 재설정">
            <RecoveryForm />
            <Link to={`/login?returnTo=${encodeURIComponent(nextPath(params.get('returnTo')))}`}>
                로그인으로
            </Link>
        </EntryFrame>
    );
}
/** 복구 이메일 요청 → 복구 증명으로 새 비밀번호 설정. 재설정 화면과 로그인 화면의 팝업이 함께 쓴다. */
export function RecoveryForm({ email }: { email?: string }) {
    const session = useIdentity();
    const action = useAction();
    return (
        <div className="stack">
            <form
                className="stack"
                onSubmit={(e) => {
                    e.preventDefault();
                    const email = new FormData(e.currentTarget).get('email');
                    void action.run(
                        () => session.receipts.requestPasswordReset({ email }),
                        () =>
                            action.setMessage(
                                '복구 요청을 접수했습니다. 이메일을 확인해주세요. 계정 존재 여부는 표시하지 않습니다.',
                            ),
                    );
                }}
            >
                <Input
                    name="email"
                    label="이메일"
                    type="email"
                    autoComplete="email"
                    defaultValue={email}
                />
                <button className="btn" disabled={action.busy}>
                    복구 이메일 요청
                </button>
            </form>
            <form
                className="stack"
                onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    const proof = String(f.get('recoveryProof'));
                    const password = String(f.get('password'));
                    void action.run(
                        () => session.resetPassword(proof, password),
                        () => action.setMessage('비밀번호를 재설정했습니다. 다시 로그인해주세요.'),
                    );
                }}
            >
                <Input name="recoveryProof" label="이메일의 복구 증명" autoComplete="off" />
                <Input
                    name="password"
                    label="새 비밀번호"
                    type="password"
                    minLength={8}
                    maxLength={64}
                    pattern="[!-~]+"
                    autoComplete="new-password"
                />
                <button className="btn primary" disabled={action.busy}>
                    비밀번호 변경
                </button>
            </form>
            <Notice>{action.message}</Notice>
        </div>
    );
}
/** APP 입력 칸: 이름표 14 · 칸 52 · 도움말 한 줄(#402) */
function AppField({
    name,
    label,
    hint,
    type = 'text',
    ...rest
}: { name: string; label: string; hint?: string } & React.InputHTMLAttributes<HTMLInputElement>) {
    const id = `${name}-${useId()}`;
    return (
        <div className={page.field}>
            <label htmlFor={id} className={page.label}>
                {label}
            </label>
            <input id={id} name={name} type={type} required className={page.input} {...rest} />
            {hint && <span className={page.hint}>{hint}</span>}
        </div>
    );
}
/** APP 가입 동의: 맨 위 '모두 동의'와 동의 4개. 필수/선택 표시는 정책 확인 뒤 붙인다(#402) */
function AppConsents({ value, change }: { value: Consents; change: (value: Consents) => void }) {
    const keys = Object.keys(value) as (keyof Consents)[];
    const all = keys.every((key) => value[key]);
    return (
        <fieldset className={appStyles.consents}>
            <legend className="sr-only">동의</legend>
            <label className={appStyles.consentAll}>
                <input
                    type="checkbox"
                    checked={all}
                    onChange={(e) =>
                        change(
                            Object.fromEntries(
                                keys.map((key) => [key, e.target.checked]),
                            ) as Consents,
                        )
                    }
                />
                모두 동의
            </label>
            <div className={appStyles.consentList}>
                {keys.map((key) => (
                    <label className={appStyles.consent} key={key}>
                        <input
                            type="checkbox"
                            checked={value[key]}
                            onChange={(e) => change({ ...value, [key]: e.target.checked })}
                        />
                        {consentLabels[key]}
                    </label>
                ))}
            </div>
        </fieldset>
    );
}
/** APP 회원가입: 가입 접수 → 확인 코드. 요청은 웹 RegisterForm과 같고, '로그인으로'는 뒤로 버튼이 대신한다 */
function AppRegister({ target }: { target: string }) {
    const session = useIdentity();
    const navigate = useNavigate();
    const [receipt, setReceipt] = useState<{ requestId: string; expiresAt: string } | null>(null);
    const [consent, setConsent] = useState<Consents>(emptyConsents);
    const action = useAction();
    return (
        <AppEntryPage
            title={receipt ? '이메일 확인' : '회원가입'}
            back={() => navigate(`/login?returnTo=${encodeURIComponent(target)}`)}
            foot={
                <button
                    type="submit"
                    form={receipt ? 'register-code' : 'register-form'}
                    className={page.primary}
                    disabled={action.busy}
                >
                    {receipt ? '이메일 확인' : '가입하기'}
                </button>
            }
        >
            {!receipt ? (
                <form
                    id="register-form"
                    className={appStyles.form}
                    onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        void action.run(
                            () =>
                                session.receipts.register({
                                    email: f.get('email'),
                                    password: f.get('password'),
                                    displayName: f.get('displayName'),
                                    consent,
                                }),
                            setReceipt,
                        );
                    }}
                >
                    <AppField
                        name="displayName"
                        label="이름"
                        hint="20자까지 · 팀원에게 이 이름으로 보입니다"
                        placeholder="현장에서 부를 이름"
                        maxLength={20}
                        autoComplete="nickname"
                    />
                    <AppField
                        name="email"
                        label="이메일"
                        type="email"
                        placeholder="name@example.com"
                        autoComplete="email"
                    />
                    <AppField
                        name="password"
                        label="비밀번호"
                        type="password"
                        placeholder="8~64자"
                        minLength={8}
                        maxLength={64}
                        pattern="[!-~]+"
                        autoComplete="new-password"
                    />
                    <AppConsents value={consent} change={setConsent} />
                </form>
            ) : (
                <form
                    id="register-code"
                    className={appStyles.form}
                    onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        void action.run(
                            () =>
                                session.authenticate('/auth/verify-email', {
                                    requestId: receipt.requestId,
                                    code: f.get('code'),
                                }),
                            () => navigate(target, { replace: true }),
                        );
                    }}
                >
                    <p className={page.notice}>
                        가입 요청을 접수했습니다. 이메일의 확인 코드를 입력해주세요. 접수는 로그인
                        완료와 다릅니다.
                    </p>
                    <AppField
                        name="code"
                        label="6자리 확인 코드"
                        inputMode="numeric"
                        pattern="[0-9]{6}"
                        maxLength={6}
                        autoComplete="one-time-code"
                    />
                    <button
                        type="button"
                        className={page.secondary}
                        disabled={action.busy}
                        onClick={() => {
                            void action.run(
                                () =>
                                    session.receipts.resendVerification({
                                        requestId: receipt.requestId,
                                    }),
                                (r) => {
                                    setReceipt(r);
                                    action.setMessage('확인 코드 재발송을 접수했습니다.');
                                },
                            );
                        }}
                    >
                        확인 코드 재발송
                    </button>
                </form>
            )}
            <Notice>{action.message}</Notice>
        </AppEntryPage>
    );
}
/**
 * APP 비밀번호 찾기: 1. 메일 받기 → 2. 새 비밀번호 두 단계로 나눠 보인다(#402).
 * 요청·재설정은 웹 RecoveryForm과 같다. 메일을 이미 받았으면 2단계로 바로 간다.
 */
function AppRecovery({ back }: { back: () => void }) {
    const session = useIdentity();
    const action = useAction();
    const [step, setStep] = useState<1 | 2>(1);
    const go = (next: 1 | 2) => {
        action.setMessage('');
        setStep(next);
    };
    return (
        <AppEntryPage
            title="비밀번호 찾기"
            back={back}
            foot={
                step === 1 ? (
                    <>
                        <button
                            type="submit"
                            form="recovery-mail"
                            className={page.primary}
                            disabled={action.busy}
                        >
                            메일 받기
                        </button>
                        <p className={page.footNote}>메일이 안 오면 스팸함을 확인해 주세요</p>
                    </>
                ) : (
                    <button
                        type="submit"
                        form="recovery-reset"
                        className={page.primary}
                        disabled={action.busy}
                    >
                        비밀번호 변경
                    </button>
                )
            }
        >
            <ol className={appStyles.steps} aria-label={`2단계 중 ${step}단계`}>
                <li aria-current={step === 1 ? 'step' : undefined}>1. 메일 받기</li>
                <li aria-current={step === 2 ? 'step' : undefined}>2. 새 비밀번호</li>
            </ol>
            {step === 1 ? (
                <form
                    id="recovery-mail"
                    className={appStyles.form}
                    onSubmit={(e) => {
                        e.preventDefault();
                        const email = new FormData(e.currentTarget).get('email');
                        void action.run(
                            () => session.receipts.requestPasswordReset({ email }),
                            () => {
                                setStep(2);
                                action.setMessage(
                                    '복구 요청을 접수했습니다. 이메일을 확인해주세요. 계정 존재 여부는 표시하지 않습니다.',
                                );
                            },
                        );
                    }}
                >
                    <div className={appStyles.lead}>
                        <b>가입한 이메일을 입력하세요</b>
                        <span>비밀번호를 다시 정할 수 있는 메일을 보내 드립니다.</span>
                    </div>
                    <AppField
                        name="email"
                        label="이메일"
                        type="email"
                        placeholder="name@example.com"
                        autoComplete="email"
                    />
                    <button type="button" className={appStyles.textLink} onClick={() => go(2)}>
                        이미 메일을 받았어요
                    </button>
                </form>
            ) : (
                <form
                    id="recovery-reset"
                    className={appStyles.form}
                    onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        const proof = String(f.get('recoveryProof'));
                        const password = String(f.get('password'));
                        void action.run(
                            () => session.resetPassword(proof, password),
                            () =>
                                action.setMessage(
                                    '비밀번호를 재설정했습니다. 다시 로그인해주세요.',
                                ),
                        );
                    }}
                >
                    <div className={appStyles.lead}>
                        <b>새 비밀번호를 정하세요</b>
                        <span>메일에 적힌 복구 증명을 함께 입력합니다.</span>
                    </div>
                    <AppField name="recoveryProof" label="이메일의 복구 증명" autoComplete="off" />
                    <AppField
                        name="password"
                        label="새 비밀번호"
                        type="password"
                        placeholder="8~64자"
                        minLength={8}
                        maxLength={64}
                        pattern="[!-~]+"
                        autoComplete="new-password"
                    />
                    <button type="button" className={appStyles.textLink} onClick={() => go(1)}>
                        메일 다시 받기
                    </button>
                </form>
            )}
            <Notice>{action.message}</Notice>
        </AppEntryPage>
    );
}
export function AccountEvents() {
    const session = useIdentity();
    const state = useAccount();
    const action = useAction();
    const [page, setPage] = useState<{ items: EventSummary[]; nextCursor: string | null } | null>(
        null,
    );
    const { setMessage } = action;
    useEffect(
        () =>
            observe(
                () => session.events(),
                setPage,
                (error) => setMessage(failureMessage(error)),
            ),
        [session, setMessage],
    );
    return (
        <EntryFrame
            title={`${state.account?.displayName ?? ''} 님의 공연`}
            action={
                <div className={entryStyles.cardActions}>
                    <button
                        type="button"
                        className={entryStyles.iconBtn}
                        aria-label="목록 새로고침"
                        title="목록 새로고침"
                        aria-busy={action.busy}
                        disabled={action.busy}
                        onClick={() => {
                            void action.run(() => session.events(), setPage);
                        }}
                    >
                        <Icon name="refresh" />
                    </button>
                    <Link
                        to="/account/notifications"
                        className={entryStyles.iconBtn}
                        aria-label="알림"
                        title="알림"
                    >
                        <Icon name="bell" />
                    </Link>
                </div>
            }
        >
            <div className={entryStyles.events}>
                {/* 데모 목록과 같은 카드. 참여 중이면 누르는 즉시 운영 화면으로 간다(#362).
                    참여가 끝난 공연은 참여 정보 화면을 연다. */}
                {page?.items.map((e) => (
                    <Link
                        key={e.id}
                        className={entryStyles.event}
                        to={
                            !e.myParticipation.active
                                ? `/account/events/${e.id}`
                                : // 작성 중인 공연은 총괄이 바로 계획 입력(W01-DRAFT)으로 간다(#362).
                                  // 운영 화면 셸에는 계획 입력으로 가는 단추가 없다(!327).
                                  e.myParticipation.eventRole === 'CHIEF' && e.state === 'DRAFT'
                                  ? `/live/events/${e.id}?next=plan-draft`
                                  : `/live/events/${e.id}`
                        }
                    >
                        <div className="row between nw">
                            <b>{e.name}</b>
                            <Chip kind={e.state === 'LIVE' ? 'ok' : ''}>
                                {e.myParticipation.active
                                    ? EVENT_STATE_LABEL[e.state]
                                    : '참여 종료'}
                            </Chip>
                        </div>
                        <span className="small">{scheduleLine(e)}</span>
                    </Link>
                ))}
            </div>
            {page?.items.length === 0 && (
                <Notice>
                    참여한 공연이 없습니다. 초대 링크로 참여하거나 새 공연을 만들어주세요.
                </Notice>
            )}
            <CreateEvent />
            {page?.nextCursor && (
                <button
                    className="btn"
                    disabled={action.busy}
                    onClick={() => {
                        void action.run(
                            () => session.events(page.nextCursor!),
                            (next) => setPage({ ...next, items: [...page.items, ...next.items] }),
                        );
                    }}
                >
                    더 보기
                </button>
            )}
            <Notice>{action.message}</Notice>
            <LogoutButton />
        </EntryFrame>
    );
}
/**
 * 새 공연 만들기(W00, 022). 만든 사람이 총괄이 되고, 운영 화면의 계획 입력(W01-DRAFT)으로 바로 간다(#362).
 * 하위 탭(V9-D65): PDF로 만들기는 문서만 고르고 추출을 누르면 공연을 만들고(임시 이름 = 첫 파일 이름)
 * 계획 입력의 PDF 탭에서 공개 직전까지 자동으로 진행한다. 직접 입력은 이름·설명을 받는 지금 흐름이다.
 */
function CreateEvent() {
    const session = useIdentity();
    const navigate = useNavigate();
    const action = useAction();
    const [open, setOpen] = useState(false);
    const [mode, setMode] = useState<'pdf' | 'direct'>('pdf');
    const [files, setFiles] = useState<File[]>([]);
    if (!open)
        return (
            <button className="btn" type="button" onClick={() => setOpen(true)}>
                새 공연 만들기
            </button>
        );
    const cancel = (
        <button className="btn" type="button" disabled={action.busy} onClick={() => setOpen(false)}>
            취소
        </button>
    );
    return (
        <div className="stack">
            <Tabs<'pdf' | 'direct'>
                label="공연 만드는 방법"
                value={mode}
                onChange={setMode}
                items={[
                    ['pdf', 'PDF로 만들기'],
                    ['direct', '직접 입력'],
                ]}
            />
            {mode === 'pdf' ? (
                <form
                    className="stack"
                    aria-label="PDF로 새 공연 만들기"
                    onSubmit={(e) => {
                        e.preventDefault();
                        if (!files.length) return;
                        void action.run(
                            async () =>
                                outcome(await session.createEvent(provisionalName(files), '')),
                            (created) => {
                                if (!created) return;
                                handFiles(created.id, files);
                                navigate(`/live/events/${created.id}?next=plan-draft&tab=pdf`);
                            },
                        );
                    }}
                >
                    <label className="field">
                        <span>공연 문서(PDF/TXT/MD 최대 3개)</span>
                        <input
                            type="file"
                            accept=".pdf,.txt,.md"
                            multiple
                            onChange={(e) => setFiles([...(e.target.files ?? [])])}
                        />
                    </label>
                    <p className="small">
                        만든 사람이 이 공연의 총괄이 됩니다. 추출을 누르면 문서의 공연
                        이름·날짜·장소·구역·직무·업무 일정으로 계획까지 만들고, 공개 버튼만
                        남깁니다. 문서에 없는 값만 물어봅니다.
                    </p>
                    <button className="btn primary" disabled={action.busy || !files.length}>
                        추출 시작
                    </button>
                    {cancel}
                    <Notice>{action.message}</Notice>
                </form>
            ) : (
                <form
                    className="stack"
                    aria-label="새 공연 만들기"
                    onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        const name = String(f.get('eventName')).trim();
                        const description = String(f.get('eventDescription') ?? '');
                        void action.run(
                            async () => outcome(await session.createEvent(name, description)),
                            (created) => {
                                if (created)
                                    navigate(
                                        `/live/events/${created.id}?next=plan-draft&tab=direct`,
                                    );
                            },
                        );
                    }}
                >
                    <Input name="eventName" label="공연 이름(20자 이내)" maxLength={20} />
                    <div className="field">
                        <label htmlFor="eventDescription">공연 설명(선택)</label>
                        <textarea id="eventDescription" name="eventDescription" />
                    </div>
                    <p className="small">
                        만든 사람이 이 공연의 총괄이 됩니다. 다음 화면에서 날짜/장소와 공연 조건을
                        직접 입력하거나 공연 자료(PDF 등)를 올려 계획을 만듭니다.
                    </p>
                    <button className="btn primary" disabled={action.busy}>
                        만들기
                    </button>
                    {cancel}
                    <Notice>{action.message}</Notice>
                </form>
            )}
        </div>
    );
}
function LogoutButton() {
    const session = useIdentity();
    const action = useAction();
    return (
        <>
            <button
                className="btn"
                disabled={action.busy}
                onClick={() => {
                    void action.run(() => session.logout());
                }}
            >
                로그아웃
            </button>
            <Notice>{action.message}</Notice>
        </>
    );
}
const INVITE_WEB_KEY = 'scenecrew-invite-web';
function readInviteWeb() {
    try {
        return sessionStorage.getItem(INVITE_WEB_KEY) === '1';
    } catch {
        return false;
    }
}
function saveInviteWeb() {
    try {
        sessionStorage.setItem(INVITE_WEB_KEY, '1');
    } catch {
        // 저장하지 못하면 다음에 다시 묻는다.
    }
}
export function InviteScreen() {
    const { token = '' } = useParams();
    const [params] = useSearchParams();
    const state = useAccount();
    // Android 브라우저로 연 초대(QR)는 로그인 전에 앱 열기·설치부터 안내한다(#402)
    const [android] = useState(() => platform === 'web' && isAndroidBrowser(navigator.userAgent));
    // 웹으로 계속을 고르면 로그인하고 돌아와도 같은 탭에서는 다시 묻지 않는다
    const [web, setWeb] = useState(() => readInviteWeb());
    if (android && !web)
        return (
            <InviteAppGate
                token={token}
                group={params.get('group')}
                onWeb={() => {
                    saveInviteWeb();
                    setWeb(true);
                }}
            />
        );
    return <InviteContent key={`${state.account?.id ?? ''}:${token}`} />;
}
function InviteContent() {
    const push = usePush();
    const { token = '' } = useParams();
    const session = useIdentity();
    const state = useAccount();
    const action = useAction();
    const [options, setOptions] = useState<Awaited<ReturnType<typeof session.options>> | null>(
        null,
    );
    // 그룹별 초대 링크(?group=)로 들어오면 그 그룹을 골라 둔다. 이 초대에서 고를 수 없는 그룹이면 고르지 않은 것으로 본다
    const [params] = useSearchParams();
    const [picked, setGroup] = useState(() => params.get('group') ?? '');
    const group = options?.groups.some((g) => g.workGroupId === picked && g.selectable)
        ? picked
        : '';
    const [available, setAvailable] = useState(true);
    const [reserve, setReserve] = useState(false);
    const [joined, setJoined] = useState('');
    const reload = () => session.options(token);
    const { setMessage } = action;
    const accountId = state.account?.id;
    useEffect(() => {
        if (!accountId) return;
        return observe(
            () => session.options(token),
            setOptions,
            (error) => setMessage(failureMessage(error)),
        );
    }, [session, token, accountId, setMessage]);
    if (!state.ready)
        return (
            <EntryFrame title="초대 확인">
                <Notice>로그인을 확인하고 있습니다.</Notice>
            </EntryFrame>
        );
    if (!state.account)
        return (
            <Navigate
                to={`/login?returnTo=${encodeURIComponent(`/invites/${encodeURIComponent(token)}`)}`}
                replace
            />
        );
    const participating = options?.myParticipation?.active;
    return (
        <EntryFrame title="공연 초대" sub="직무 그룹을 확인하고 직접 참여해주세요.">
            <button
                className="btn"
                disabled={action.busy}
                onClick={() => {
                    void action.run(reload, setOptions);
                }}
            >
                참여 상태 다시 조회
            </button>
            {participating && (
                <>
                    <Notice>
                        현재 참여 중입니다. 초대를 다시 열어도 그룹 이동이나 새 배정을 실행하지
                        않습니다.
                    </Notice>
                    {/* 참여 뒤 바로 운영 화면(앱은 내 업무 탭)으로 이어 간다(#373) */}
                    <Link className="btn primary" to={`/live/events/${options.eventId}`}>
                        운영 화면 열기
                    </Link>
                    <Link to={`/account/events/${options.eventId}`}>참여한 공연 열기</Link>
                </>
            )}
            {options && !participating && (
                <form
                    className="stack"
                    onSubmit={(e) => {
                        e.preventDefault();
                        void action.run(
                            async () =>
                                outcome(
                                    await session.join(token, options.eventId, {
                                        workGroupId: group,
                                        available,
                                        isReserve: reserve,
                                        expectedEventRevision: options.revision,
                                    }),
                                ),
                            (result) => {
                                if (result) {
                                    // 새 공연 알림을 받도록 이 기기를 다시 등록한다(#241)
                                    void push?.refresh().catch(() => undefined);
                                    setJoined(result.initialAssignment);
                                    setOptions({
                                        ...options,
                                        revision: result.eventRevision,
                                        myParticipation: result.participation,
                                    });
                                }
                            },
                        );
                    }}
                >
                    <fieldset disabled={action.busy || session.pending(`join:${token}`)}>
                        <legend>직무 그룹</legend>
                        {options.groups.map((g) => (
                            <label className="check" key={g.workGroupId}>
                                <input
                                    type="radio"
                                    name="group"
                                    value={g.workGroupId}
                                    disabled={!g.selectable}
                                    checked={group === g.workGroupId}
                                    onChange={() => setGroup(g.workGroupId)}
                                />{' '}
                                {g.name} / {g.taskSummary}
                                {!g.selectable && ' / 선택 불가'}
                            </label>
                        ))}
                    </fieldset>
                    <label className="check">
                        <input
                            type="checkbox"
                            checked={available}
                            disabled={action.busy || session.pending(`join:${token}`)}
                            onChange={(e) => setAvailable(e.target.checked)}
                        />{' '}
                        지금 활동할 수 있습니다
                    </label>
                    <label className="check">
                        <input
                            type="checkbox"
                            checked={reserve}
                            disabled={action.busy || session.pending(`join:${token}`)}
                            onChange={(e) => setReserve(e.target.checked)}
                        />{' '}
                        예비 인원으로 참여
                    </label>
                    <button
                        className="btn primary"
                        disabled={action.busy || (!group && !session.pending(`join:${token}`))}
                    >
                        {session.pending(`join:${token}`)
                            ? '같은 참여 요청 확인'
                            : '이 그룹으로 참여'}
                    </button>
                </form>
            )}
            {joined && (
                <Notice>
                    참여가 처리되었습니다. 최초 업무 배정 상태:{' '}
                    {
                        {
                            PENDING: '대기 중',
                            ASSIGNED: '배정됨',
                            NO_CANDIDATE: '배정할 업무 없음',
                            FAILED: '배정 실패',
                            UNKNOWN: '확인 필요',
                        }[joined]
                    }
                    . 참여 성공과 업무 배정은 별개입니다.
                </Notice>
            )}
            <Notice>{action.message}</Notice>
            <Link to="/account/events">내 공연 목록</Link>
            <Link
                to={`/login?returnTo=${encodeURIComponent(`/invites/${encodeURIComponent(token)}`)}`}
            >
                다른 계정으로 로그인
            </Link>
        </EntryFrame>
    );
}
export function ParticipationScreen() {
    const { eventId = '' } = useParams();
    return <ParticipationContent key={eventId} />;
}
function ParticipationContent() {
    const { eventId = '' } = useParams();
    const session = useIdentity();
    const action = useAction();
    const [value, setValue] = useState<EventSummary | null>(null);
    let valid = true;
    try {
        asUuid(eventId, 'eventId');
    } catch {
        valid = false;
    }
    const { setMessage } = action;
    useEffect(() => {
        if (!valid) return;
        return observe(
            () => session.event(eventId),
            (value) => {
                setValue(value);
                void session.scopes.revalidate();
            },
            (error) => {
                setValue(null);
                setMessage(failureMessage(error));
            },
        );
    }, [session, eventId, valid, setMessage]);
    return (
        <EntryFrame title={value?.name ?? '공연 참여'}>
            {!valid ? (
                <Notice>공연 주소를 확인해주세요.</Notice>
            ) : (
                <button
                    className="btn"
                    disabled={action.busy}
                    onClick={() => {
                        void action.run(() => session.event(eventId), setValue);
                    }}
                >
                    현재 참여 조회
                </button>
            )}
            {value && (
                <>
                    <Notice>
                        {value.myParticipation.active ? '참여 중' : '참여 종료'} /{' '}
                        {value.myParticipation.eventRole === 'CHIEF' ? '총괄' : '팀원'} / 참여 회차{' '}
                        {value.myParticipation.participationRevision}
                    </Notice>
                    {value.myParticipation.active && (
                        <div className="stack">
                            <Link to={`/account/events/${eventId}/plan`}>공연 계획 열기</Link>
                            <Link to={`/account/events/${eventId}/tasks`}>내 업무 열기</Link>
                            <Link to={`/account/events/${eventId}/issues`}>
                                {value.myParticipation.eventRole === 'CHIEF'
                                    ? '보고/대응 열기'
                                    : '상황 보고 열기'}
                            </Link>
                            <Link to={`/account/events/${eventId}/changes`}>변경 확인 열기</Link>
                            <Link to={`/live/events/${eventId}`}>운영 화면으로 열기</Link>
                            <Link to={`/account/events/${eventId}/chat`}>채팅 열기</Link>
                            {/* 그룹의 조회 허용 여부는 공개 계약에 아직 없어 서버가 판정한다(V9-D13) */}
                            <Link to={`/account/events/${eventId}/equipment`}>
                                장비 현황 열기 (총괄/장비 담당 그룹)
                            </Link>
                            {value.myParticipation.eventRole === 'CHIEF' && (
                                <Link to={`/account/events/${eventId}/manage`}>
                                    공연 관리 열기 (기본정보/공개/시작/최초 배정)
                                </Link>
                            )}
                            {value.myParticipation.eventRole === 'CHIEF' && (
                                <Link to={`/account/events/${eventId}/safety`}>안전 지도 열기</Link>
                            )}
                            {value.myParticipation.eventRole === 'CHIEF' && (
                                <Link to={`/account/events/${eventId}/members`}>
                                    참여 인원 열기
                                </Link>
                            )}
                            {value.myParticipation.eventRole === 'CHIEF' && (
                                <Link to={`/account/events/${eventId}/end`}>
                                    공연 종료/요약 열기
                                </Link>
                            )}
                            <button
                                className="btn"
                                disabled={action.busy}
                                onClick={() => {
                                    void action.run(async () => {
                                        outcome(
                                            await session.availability(
                                                eventId,
                                                value.myParticipation.memberId,
                                                true,
                                                value.revision,
                                            ),
                                        );
                                        return session.event(eventId);
                                    }, setValue);
                                }}
                            >
                                활동 가능으로 변경
                            </button>
                            <button
                                className="btn"
                                disabled={action.busy}
                                onClick={() => {
                                    void action.run(async () => {
                                        outcome(
                                            await session.availability(
                                                eventId,
                                                value.myParticipation.memberId,
                                                false,
                                                value.revision,
                                            ),
                                        );
                                        return session.event(eventId);
                                    }, setValue);
                                }}
                            >
                                활동 불가로 변경
                            </button>
                            <button
                                className="btn"
                                disabled={action.busy}
                                onClick={() => {
                                    void action.run(
                                        async () => outcome(await session.leave(eventId)),
                                        (r) => {
                                            if (r)
                                                setValue({
                                                    ...value,
                                                    revision: r.eventRevision,
                                                    myParticipation: r.participation,
                                                });
                                        },
                                    );
                                }}
                            >
                                공연 참여 종료
                            </button>
                        </div>
                    )}
                    {!value.myParticipation.active && (
                        <Notice>다시 참여하려면 유효한 초대 링크에서 그룹을 선택해주세요.</Notice>
                    )}
                </>
            )}
            <Notice>{action.message}</Notice>
            <Link to="/account/events">내 공연 목록</Link>
        </EntryFrame>
    );
}
export function ProfileScreen() {
    return (
        <EntryFrame title="내 정보/동의">
            <AccountProfileForm />
            <Link to="/account/events">내 공연 목록</Link>
            <LogoutButton />
            <DeactivateSection />
        </EntryFrame>
    );
}
/**
 * 계정 이름·동의·소셜 연결 편집. 계정 화면과 운영 화면 상단의 내 정보 팝업이 함께 쓴다.
 * withDeactivate: 팝업에서도 회원 탈퇴까지 보일지.
 */
export function AccountProfileForm({ withDeactivate = false }: { withDeactivate?: boolean }) {
    const session = useIdentity();
    const { account } = useAccount();
    const action = useAction();
    // 바꾼 동의만 담는다(저장할 때 이것만 보낸다). 화면에는 현재 동의 위에 바꾼 값을 얹어 보인다
    const [consent, setConsent] = useState<Partial<Consents>>({});
    const current = useCurrentConsents();
    const push = usePush();
    const [pushStatus, setPushStatus] = useState<PushStatus>('off');
    useEffect(() => (push ? push.onStatus(setPushStatus) : undefined), [push]);
    // 한 줄에 왼쪽 이름, 오른쪽 입력·값을 둔다: 닉네임 · 이메일 · 소셜 계정 · 동의들 → 변경사항 저장 → 회원 탈퇴
    return (
        <div className="stack">
            <form
                className={styles.line}
                onSubmit={(e) => {
                    e.preventDefault();
                    const name = String(new FormData(e.currentTarget).get('displayName'));
                    void action.run(
                        async () => outcome(await session.updateName(name)),
                        () => action.setMessage('닉네임을 변경했습니다.'),
                    );
                }}
            >
                <label htmlFor="displayName" className={`${styles.name} ${styles.key}`}>
                    닉네임
                </label>
                <div className={styles.control}>
                    <input
                        id="displayName"
                        name="displayName"
                        className="inp"
                        defaultValue={account?.displayName}
                        maxLength={20}
                    />
                    <button className="btn" aria-label="닉네임 변경" disabled={action.busy}>
                        변경
                    </button>
                </div>
            </form>
            {/* 닉네임·이메일까지가 계정 정보다. 아래에 구분선을 두고 소셜·동의를 이어 둔다 */}
            <div className={`${styles.line} ${styles.lastKey}`}>
                <span className={`${styles.name} ${styles.key}`}>이메일</span>
                <span className={`${styles.value} ${styles.keyValue}`}>
                    {account?.primaryEmail ?? '등록 이메일 없음'}
                </span>
            </div>
            <SocialLink />
            <form
                className="stack"
                onSubmit={(e) => {
                    e.preventDefault();
                    void action.run(
                        async () => outcome(await session.updateConsents(consent)),
                        (value) => {
                            const pushOn = consent.push === true;
                            if (value) setConsent({});
                            // 푸시 수신에 동의하면 그때 알림 권한을 묻고 기기를 등록한다(Android 앱)
                            if (pushOn && push) void push.enable().catch(() => undefined);
                            current.reload();
                            action.setMessage('변경사항을 저장했습니다.');
                        },
                    );
                }}
            >
                {current.failed && (
                    <Notice>지금 동의 상태를 불러오지 못했습니다. 잠시 뒤 다시 열어주세요.</Notice>
                )}
                {(Object.keys(consentLabels) as (keyof Consents)[]).map((key) => {
                    const now = current.value?.[key];
                    const shown = consent[key] ?? now;
                    return (
                        <div className={styles.line} key={key}>
                            <label htmlFor={`consent-${key}`} className={styles.name}>
                                {consentLabels[key]}
                            </label>
                            <select
                                id={`consent-${key}`}
                                className="inp"
                                value={shown === undefined ? '' : String(shown)}
                                disabled={now === undefined}
                                onChange={(e) => {
                                    const value = e.target.value === 'true';
                                    const next = { ...consent };
                                    // 지금 값으로 되돌리면 바꾼 것으로 보지 않는다
                                    if (value === now) delete next[key];
                                    else next[key] = value;
                                    setConsent(next);
                                }}
                            >
                                {shown === undefined && <option value="">불러오는 중</option>}
                                <option value="true">동의</option>
                                <option value="false">동의하지 않음</option>
                            </select>
                        </div>
                    );
                })}
                <button className="btn" disabled={action.busy || Object.keys(consent).length === 0}>
                    변경사항 저장
                </button>
            </form>
            {push && <p className="small">휴대폰 알림: {PUSH_LABEL[pushStatus]}</p>}
            <Notice>{action.message}</Notice>
            {withDeactivate && <DeactivateSection />}
        </div>
    );
}

/** 개인정보 문의·처리 상태 확인 창구(V9-D36). */
const PRIVACY_CONTACT = 'privacy@example.invalid';

function deactivateMessage(error: unknown) {
    const failure = classifyFailure(error);
    if (
        failure.code === 'REAUTHENTICATION_REQUIRED' ||
        failure.code === 'CHIEF_TRANSFER_REQUIRED' ||
        failure.code === 'TASK_IN_PROGRESS'
    )
        return failure.message ?? '탈퇴할 수 없습니다.';
    if (failure.kind === 'unauthenticated')
        return '로그인이 풀렸습니다. 다시 로그인한 뒤 시도해주세요.';
    return '탈퇴 요청 결과를 확인하지 못했습니다. 다시 로그인해 상태를 확인하거나 운영 창구로 문의해주세요.';
}

/** 탈퇴 = 개인정보 삭제 요청(#334, V9-D39). 비밀번호와 확인 표시가 있어야 보낸다. */
function DeactivateSection() {
    const session = useIdentity();
    const navigate = useNavigate();
    const [open, setOpen] = useState(false);
    const [agreed, setAgreed] = useState(false);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    if (!open)
        return (
            <button className={`btn ${styles.danger}`} type="button" onClick={() => setOpen(true)}>
                회원 탈퇴
            </button>
        );
    return (
        <form
            className="stack"
            aria-label="회원 탈퇴"
            onSubmit={(e) => {
                e.preventDefault();
                if (busy) return;
                const password = String(new FormData(e.currentTarget).get('deactivatePassword'));
                setBusy(true);
                setMessage('');
                session
                    .deactivate(password)
                    .then((receipt) =>
                        navigate('/account/deactivated', { replace: true, state: receipt }),
                    )
                    .catch((error: unknown) => {
                        setMessage(deactivateMessage(error));
                        setBusy(false);
                    });
            }}
        >
            <h2 className="h3">회원 탈퇴</h2>
            <ul className="small">
                <li>탈퇴하면 모든 기기에서 바로 로그아웃되고 다시 로그인할 수 없습니다.</li>
                <li>
                    24시간 안에 이름은 '탈퇴한 사용자'로 바뀌고, 이메일/비밀번호/소셜 연결/기기
                    등록/음성 파일이 지워집니다.
                </li>
                <li>공연 기록을 위해 보고 내용과 채팅 내용은 이름 없이 남습니다.</li>
                <li>
                    종료되지 않은 공연의 총괄은 먼저 인계해야 하고, 진행 중인 업무가 있으면 마친 뒤
                    탈퇴할 수 있습니다. 대기 중인 업무는 담당이 풀리고 공연에서 자동으로 빠집니다.
                </li>
            </ul>
            <Input
                name="deactivatePassword"
                label="현재 비밀번호"
                type="password"
                autoComplete="current-password"
            />
            <label className="small">
                <input
                    type="checkbox"
                    checked={agreed}
                    onChange={(e) => setAgreed(e.target.checked)}
                />{' '}
                위 내용을 확인했고 탈퇴를 요청합니다.
            </label>
            <button className="btn" disabled={busy || !agreed}>
                탈퇴 요청
            </button>
            <button className="btn" type="button" disabled={busy} onClick={() => setOpen(false)}>
                취소
            </button>
            <Notice>{message}</Notice>
            <p className="small">
                비밀번호 로그인이 없는 계정은 {PRIVACY_CONTACT}로 탈퇴를 요청해주세요.
            </p>
        </form>
    );
}

function isReceipt(value: unknown): value is DeactivationReceipt {
    const v = value as Partial<DeactivationReceipt> | null;
    return (
        !!v &&
        typeof v.receiptNo === 'string' &&
        typeof v.deactivatedAt === 'string' &&
        typeof v.deletionDueAt === 'string'
    );
}
function localTime(value: string) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR');
}

/** 탈퇴 접수 결과. 로그인 없이 열리며, 새로고침하면 접수 정보는 다시 보이지 않는다. */
export function DeactivatedScreen() {
    const session = useIdentity();
    const { state } = useLocation();
    const receipt = isReceipt(state) ? state : null;
    // 탈퇴 직후 넘어온 경우에만 로컬 로그인 상태를 지운다. 주소로 직접 연 사람의 로그인은 건드리지 않는다.
    useEffect(() => {
        if (receipt) void session.forgetAfterDeactivation();
    }, [receipt, session]);
    return (
        <EntryFrame title="탈퇴 요청을 접수했습니다">
            {receipt ? (
                <dl className="stack">
                    <dt>접수 번호</dt>
                    <dd>
                        <b>{receipt.receiptNo}</b>
                    </dd>
                    <dt>접수 시각</dt>
                    <dd>{localTime(receipt.deactivatedAt)}</dd>
                    <dt>개인정보 삭제 기한</dt>
                    <dd>{localTime(receipt.deletionDueAt)}까지</dd>
                </dl>
            ) : (
                <p>탈퇴 요청이 접수된 상태입니다.</p>
            )}
            <p className="small">
                모든 기기에서 로그아웃되었습니다. 접수 번호를 적어 두세요. 처리 상태는 아래에서
                확인하거나 {PRIVACY_CONTACT}로 접수 번호와 함께 문의할 수 있습니다.
            </p>
            {receipt && <DeletionStatusCheck initial={receipt.receiptNo} />}
            {!receipt && <Link to="/account/deletion-status">탈퇴 처리 상태 확인</Link>}
            <Link to="/login">처음 화면으로</Link>
        </EntryFrame>
    );
}

/** 지금 동의 상태(V9-D40 동의 조회). 내 정보의 동의 선택 칸이 이 값을 미리 고른다. 저장 뒤 reload로 다시 읽는다. */
function useCurrentConsents() {
    const session = useIdentity();
    const [value, setValue] = useState<Consents | null>(null);
    const [failed, setFailed] = useState(false);
    const [version, setVersion] = useState(0);
    useEffect(() => {
        let alive = true;
        session
            .consentRecord()
            .then((record) => {
                if (!alive) return;
                setValue(record.consents);
                setFailed(false);
            })
            .catch(() => {
                if (alive) setFailed(true);
            });
        return () => {
            alive = false;
        };
    }, [session, version]);
    return { value, failed, reload: () => setVersion((n) => n + 1) };
}

const DELETION_STATUS_LABEL: Record<DeletionStatus['status'], string> = {
    PROCESSING: '처리 중입니다. 기한 안에 개인정보를 정리합니다.',
    COMPLETED: '처리를 마쳤습니다. 이름/연락 수단/기기/음성 정보가 지워졌습니다.',
};

/** 접수 번호로 탈퇴 처리 상태를 본다. 로그인이 필요 없다. */
function DeletionStatusCheck({ initial = '' }: { initial?: string }) {
    const session = useIdentity();
    const [value, setValue] = useState(initial);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<DeletionStatus | null>(null);
    const [message, setMessage] = useState('');
    return (
        <form
            className="stack"
            aria-label="탈퇴 처리 상태 확인"
            onSubmit={(e) => {
                e.preventDefault();
                const receiptNo = value.trim().toUpperCase();
                if (!/^DEL-[0-9]{8}-[0-9A-F]{8}$/.test(receiptNo)) {
                    setResult(null);
                    setMessage('접수 번호는 DEL-날짜8자리-8자리 형식입니다.');
                    return;
                }
                setBusy(true);
                setMessage('');
                session
                    .deletionStatus(receiptNo)
                    .then((status) => setResult(status))
                    .catch((error: unknown) => {
                        setResult(null);
                        setMessage(
                            classifyFailure(error).status === 404
                                ? '해당 접수 번호를 찾지 못했습니다. 번호를 다시 확인해주세요.'
                                : '처리 상태를 확인하지 못했습니다. 잠시 뒤 다시 시도해주세요.',
                        );
                    })
                    .finally(() => setBusy(false));
            }}
        >
            <Input
                name="receiptNo"
                label="접수 번호"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                placeholder="DEL-20261106-1A2B3C4D"
            />
            <button className="btn" disabled={busy}>
                처리 상태 확인
            </button>
            {result && (
                <p role="status">
                    {DELETION_STATUS_LABEL[result.status]} (기한 {localTime(result.deletionDueAt)})
                </p>
            )}
            <Notice>{message}</Notice>
        </form>
    );
}

/** 탈퇴 처리 상태 확인(V9-D40). 로그인 없이 연다. */
export function DeletionStatusScreen() {
    return (
        <EntryFrame title="탈퇴 처리 상태 확인" sub="탈퇴할 때 받은 접수 번호를 입력해주세요.">
            <DeletionStatusCheck />
            <p className="small">접수 번호를 잃어버렸다면 {PRIVACY_CONTACT}로 문의해주세요.</p>
            <Link to="/login">처음 화면으로</Link>
        </EntryFrame>
    );
}
