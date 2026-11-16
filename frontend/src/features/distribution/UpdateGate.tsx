import { useEffect, useState, type ReactNode } from 'react';
import { App as CapacitorApp } from '@capacitor/app';
import { CapacitorHttp } from '@capacitor/core';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { apiBaseUrl, appVersionCode, isNative, platform } from '../../platform/runtime';
import { isOutdated, parseRelease, releaseOrigin, type AppRelease } from './release';

// 서버 계약보다 오래된 앱을 막는다(#386, V9-D51).
// 배포 기록 latest.json의 minVersionCode보다 설치 버전이 낮으면 앱 대신 새 버전 설치 안내만 보인다.
// 기록을 읽지 못하거나 형식이 틀리면 막지 않는다. 확인하는 동안에도 앱은 그대로 연다.

async function readRelease(): Promise<AppRelease | null> {
    const url = `${releaseOrigin(apiBaseUrl)}/downloads/latest.json`;
    try {
        // 앱의 출처(https://localhost)와 호스트가 달라 WebView fetch는 CORS에 막힌다. 네이티브 요청을 쓴다.
        if (isNative) {
            const res = await CapacitorHttp.get({
                url,
                headers: { 'Cache-Control': 'no-cache' },
                responseType: 'json',
            });
            return res.status === 200 ? parseRelease(res.data) : null;
        }
        const res = await fetch(url, { cache: 'no-store', credentials: 'omit' });
        return res.ok ? parseRelease(await res.json()) : null;
    } catch {
        return null;
    }
}

export function UpdateGate({ children }: { children: ReactNode }) {
    const [release, setRelease] = useState<AppRelease | null>(null);
    useEffect(() => {
        if (platform !== 'android') return;
        let alive = true;
        const check = () =>
            void readRelease().then((next) => {
                if (alive) setRelease(next);
            });
        check();
        // 백그라운드에 오래 있던 앱도 다시 열 때 확인한다.
        const listener = isNative ? CapacitorApp.addListener('resume', check) : null;
        return () => {
            alive = false;
            void listener?.then((l) => l.remove());
        };
    }, []);
    if (release && isOutdated(appVersionCode, release)) return <UpdateRequired release={release} />;
    return children;
}

function UpdateRequired({ release }: { release: AppRelease }) {
    const address = `${new URL(releaseOrigin(apiBaseUrl)).host}/download`;
    const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');
    const copy = () => {
        const clipboard = navigator.clipboard as Clipboard | undefined;
        if (!clipboard) return setCopied('failed');
        clipboard.writeText(`https://${address}`).then(
            () => setCopied('done'),
            () => setCopied('failed'),
        );
    };
    return (
        <EntryFrame
            title="새 버전을 설치해 주세요"
            sub="지금 설치된 앱은 서버와 맞지 않아 사용할 수 없습니다."
        >
            <div className="stack">
                <p className="small">
                    최신 버전은 {release.versionName}입니다. 휴대폰 브라우저(Chrome 등)의 주소창에
                    아래 주소를 직접 입력해 새 APK를 내려받아 설치해 주세요. 앱 안의 링크는 다시 이
                    앱으로 열리기 때문에 주소를 직접 입력해야 합니다.
                </p>
                <p className="small">
                    <code>{address}</code>
                </p>
                <button type="button" className="btn primary block" onClick={copy}>
                    주소 복사
                </button>
                {copied !== 'idle' && (
                    <p className="small muted" role="status">
                        {copied === 'done'
                            ? '주소를 복사했습니다. 브라우저 주소창에 붙여 넣어 주세요.'
                            : '복사하지 못했습니다. 주소를 직접 입력해 주세요.'}
                    </p>
                )}
                <p className="small muted">
                    새 버전을 같은 방법으로 설치하면 업데이트되며 로그인과 앱 데이터는 유지됩니다.
                </p>
            </div>
        </EntryFrame>
    );
}
