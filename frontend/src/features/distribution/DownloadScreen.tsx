import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { isNative } from '../../platform/runtime';
import { parseRelease, sizeText, type AppRelease } from './release';

// 로그인 없이 보는 Android 앱 다운로드·설치 안내(#333, V9-D24~D26).
// APK 다운로드는 누구나 가능하지만, 앱 안의 데이터·행사 참여는 기존 로그인·초대·권한 검사를 그대로 받는다.
type State = { kind: 'loading' } | { kind: 'ready'; release: AppRelease } | { kind: 'failed' };

export function DownloadScreen() {
    const [state, setState] = useState<State>({ kind: 'loading' });
    useEffect(() => {
        if (isNative) return;
        let alive = true;
        fetch('/downloads/latest.json', { cache: 'no-store', credentials: 'omit' })
            .then((r) => (r.ok ? r.json() : null))
            .then((data: unknown) => {
                if (!alive) return;
                const release = parseRelease(data);
                setState(release ? { kind: 'ready', release } : { kind: 'failed' });
            })
            .catch(() => {
                if (alive) setState({ kind: 'failed' });
            });
        return () => {
            alive = false;
        };
    }, []);
    if (isNative)
        return (
            <EntryFrame title="앱 설치 안내">
                <p className="small">이미 SceneCrew 앱을 사용하고 있습니다.</p>
                <Link to="/login">로그인 화면으로</Link>
            </EntryFrame>
        );
    return (
        <EntryFrame
            title="SceneCrew Android 앱 받기"
            sub="Google Play가 아닌 이 사이트에서 직접 내려받아 설치합니다."
        >
            {state.kind === 'loading' && (
                <p className="small" role="status">
                    최신 버전을 확인하고 있습니다.
                </p>
            )}
            {state.kind === 'failed' && (
                <p className="small" role="status">
                    지금은 앱 파일을 확인할 수 없습니다. 웹에서 계속 이용해 주세요.
                </p>
            )}
            {state.kind === 'ready' && (
                <div className="stack">
                    <a
                        className="btn primary block"
                        href={`/downloads/${state.release.file}`}
                        download={state.release.file}
                    >
                        Android 앱 내려받기 ({state.release.versionName},{' '}
                        {sizeText(state.release.size)})
                    </a>
                    <p className="small muted">
                        파일 확인용 SHA-256: <code>{state.release.sha256}</code>
                    </p>
                </div>
            )}
            <section className="stack" aria-label="설치 방법">
                <h2 className="small">설치 방법</h2>
                <ol className="small">
                    <li>휴대폰 브라우저에서 위 버튼으로 APK 파일을 내려받습니다.</li>
                    <li>
                        내려받은 파일을 엽니다. &ldquo;출처를 알 수 없는 앱&rdquo; 안내가 나오면
                        설정에서 이 브라우저의 앱 설치를 허용한 뒤 다시 엽니다.
                    </li>
                    <li>설치를 마치면 SceneCrew 앱을 열고 로그인합니다.</li>
                    <li>
                        이미 설치했다면 새 파일을 같은 방법으로 설치해 업데이트합니다. 로그인과 앱
                        데이터는 유지됩니다.
                    </li>
                </ol>
            </section>
            <section className="stack" aria-label="초대받은 경우">
                <h2 className="small">초대 링크를 받았다면</h2>
                <p className="small">
                    설치 후 받은 초대 링크를 다시 열면 앱에서 열립니다. 로그인한 뒤 직무 그룹을
                    확인하고 참여를 눌러야 참여가 완료됩니다. 앱을 설치하지 않으려면 같은 링크를
                    웹에서 그대로 이용할 수 있습니다.
                </p>
            </section>
            <Link to="/login">웹에서 로그인하기</Link>
        </EntryFrame>
    );
}
