// 휴대폰 녹음 비교용 확인 표시(S15P31A202-356). 주소에 ns 쿼리를 직접 붙였을 때만 켜진다.
// - ns=on: 운영과 같은 요청에 실제 설정값 표시와 파일 받기만 더한다(켬 쪽 비교 녹음).
// - ns=off: 단말 소음 억제만 끈다.
// - ns=raw: 안드로이드 Chrome에서 소음 억제가 에코 제거에 묶일 수 있어 에코 제거·자동 이득까지 끈다.
// 앱 안에서 이동하면 쿼리가 사라지므로 같은 탭에서는 sessionStorage로 유지한다.

export type NsProbeMode = 'on' | 'off' | 'raw';

const KEY = 'scenecrew-ns-probe';

const REQUESTS: Record<NsProbeMode, MediaStreamConstraints> = {
    on: { audio: true },
    off: { audio: { noiseSuppression: false } },
    raw: {
        audio: { noiseSuppression: false, echoCancellation: false, autoGainControl: false },
    },
};

export function microphoneRequest(mode: NsProbeMode | null): MediaStreamConstraints {
    return mode ? REQUESTS[mode] : { audio: true };
}

function asMode(value: string | null): NsProbeMode | null {
    return value === 'on' || value === 'off' || value === 'raw' ? value : null;
}

/** Hash history(Android)는 쿼리가 `#/경로?ns=off`처럼 # 뒤에 붙는다. */
export function parseNsProbe(search: string, hash: string): NsProbeMode | null {
    const query = hash.indexOf('?');
    const fromHash = query < 0 ? null : new URLSearchParams(hash.slice(query)).get('ns');
    return asMode(fromHash) ?? asMode(new URLSearchParams(search).get('ns'));
}

type Store = Pick<Storage, 'getItem' | 'setItem'>;

export function resolveNsProbe(
    search: string,
    hash: string,
    store: () => Store,
): NsProbeMode | null {
    const picked = parseNsProbe(search, hash);
    try {
        if (picked) {
            store().setItem(KEY, picked);
            return picked;
        }
        return asMode(store().getItem(KEY));
    } catch {
        return picked;
    }
}

export function readNsProbe() {
    return resolveNsProbe(
        window.location.search,
        window.location.hash,
        () => window.sessionStorage,
    );
}

export interface NsProbe {
    mode: NsProbeMode;
    settings: MediaTrackSettings;
    fileName: string;
}

export function nsProbeText({ mode, settings }: NsProbe) {
    const show = (value: boolean | string | undefined) =>
        value === undefined ? '없음' : String(value);
    return (
        `확인용 ns=${mode} / 소음 억제 ${show(settings.noiseSuppression)}` +
        ` / 에코 제거 ${show(settings.echoCancellation)}` +
        ` / 자동 이득 ${show(settings.autoGainControl)}`
    );
}
