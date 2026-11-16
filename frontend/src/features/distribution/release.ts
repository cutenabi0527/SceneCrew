// 참가자 공개 APK의 배포 기록(#333). 서버 /downloads/latest.json을 그대로 읽는다.
// 파일 이름은 같은 폴더 안의 이름만 허용하고, 해시는 64자리 소문자 16진수여야 한다.
// minVersionCode(#386, V9-D51)는 서버 계약과 맞는 가장 낮은 앱 버전이다. 없으면 버전 검사를 하지 않는다.
export type AppRelease = {
    versionName: string;
    versionCode: number;
    minVersionCode?: number;
    file: string;
    sha256: string;
    size: number;
    releasedAt: string;
};

export function parseRelease(value: unknown): AppRelease | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const v = value as Record<string, unknown>;
    if (
        typeof v.versionName !== 'string' ||
        !/^\d+\.\d+\.\d+$/.test(v.versionName) ||
        typeof v.versionCode !== 'number' ||
        !Number.isInteger(v.versionCode) ||
        v.versionCode < 1 ||
        typeof v.file !== 'string' ||
        !/^[A-Za-z0-9._-]+\.apk$/.test(v.file) ||
        v.file.startsWith('.') ||
        typeof v.sha256 !== 'string' ||
        !/^[0-9a-f]{64}$/.test(v.sha256) ||
        typeof v.size !== 'number' ||
        !Number.isInteger(v.size) ||
        v.size <= 0 ||
        typeof v.releasedAt !== 'string' ||
        Number.isNaN(Date.parse(v.releasedAt))
    )
        return null;
    // 최소 버전이 배포 중인 버전보다 높으면 아무도 맞출 수 없는 잘못된 기록이다.
    if (
        v.minVersionCode !== undefined &&
        (typeof v.minVersionCode !== 'number' ||
            !Number.isInteger(v.minVersionCode) ||
            v.minVersionCode < 1 ||
            v.minVersionCode > v.versionCode)
    )
        return null;
    return {
        versionName: v.versionName,
        versionCode: v.versionCode,
        ...(v.minVersionCode === undefined ? {} : { minVersionCode: v.minVersionCode }),
        file: v.file,
        sha256: v.sha256,
        size: v.size,
        releasedAt: v.releasedAt,
    };
}

export function sizeText(bytes: number) {
    return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

/** 설치된 앱이 서버 계약보다 오래되어 쓰면 안 되는가. 기록을 읽지 못하면(null) 막지 않는다. */
export function isOutdated(appVersionCode: number, release: AppRelease | null) {
    return release?.minVersionCode !== undefined && appVersionCode < release.minVersionCode;
}

/** 앱(https://localhost)에서 읽을 배포 기록 주소. 업무 서버와 같은 호스트의 /downloads/에 있다. */
export function releaseOrigin(apiBaseUrl: string) {
    return new URL(apiBaseUrl).origin;
}
