import { useState } from 'react';
import { useNavigate } from 'react-router';
import { DEMO_PERSONAS } from '../../domain/seed';
import { useData } from '../../domain/hooks';
import { nameOf } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import { EntryFrame } from './EntryFrame';
import styles from './entry.module.css';

/** 공통 로그인(공유 화면). WEB·APP 같은 계정, 바깥 틀만 다르다. 인증은 미구현이라 데모 계정으로 들어간다. */
export function LoginScreen() {
    const d = useData();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const [notice, setNotice] = useState('');
    const enter = (me: string) => {
        dispatch({ type: 'login', me });
        navigate('/events');
    };
    return (
        <EntryFrame landing title="로그인" sub="WEB / APP 모두 같은 계정을 사용합니다.">
            <form
                className="stack"
                onSubmit={(e) => {
                    e.preventDefault();
                    setNotice('계정 인증은 아직 연동 전입니다. 아래 데모 계정으로 둘러보세요.');
                }}
            >
                <div className="row">
                    <button
                        type="button"
                        className="btn grow"
                        onClick={() => setNotice('소셜 로그인은 연동 전입니다.')}
                    >
                        구글로 계속하기
                    </button>
                    <button
                        type="button"
                        className="btn grow"
                        onClick={() => setNotice('소셜 로그인은 연동 전입니다.')}
                    >
                        카카오
                    </button>
                    <button
                        type="button"
                        className="btn grow"
                        onClick={() => setNotice('소셜 로그인은 연동 전입니다.')}
                    >
                        네이버
                    </button>
                </div>
                <div className="field">
                    <label htmlFor="email">이메일</label>
                    <input
                        id="email"
                        type="email"
                        autoComplete="email"
                        placeholder="name@example.com"
                    />
                </div>
                <div className="field">
                    <label htmlFor="pw">비밀번호</label>
                    <input
                        id="pw"
                        type="password"
                        autoComplete="current-password"
                        placeholder="8~64자"
                    />
                </div>
                <button type="submit" className="btn primary block">
                    이메일로 계속하기
                </button>
                {notice && (
                    <p className="small muted" role="status">
                        {notice}
                    </p>
                )}
            </form>
            <div className={styles.demo}>
                <span className="xs muted">데모 계정</span>
                {DEMO_PERSONAS.map(([id, label]) => (
                    <button
                        key={id}
                        type="button"
                        className={styles.demoBtn}
                        onClick={() => enter(id)}
                    >
                        <b>{nameOf(d, id)}</b> {label}
                    </button>
                ))}
            </div>
        </EntryFrame>
    );
}
