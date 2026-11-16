import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { defineConfig, loadEnv } from 'vite';
import { validateApiBase } from './src/platform/config.ts';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '');
    const platform = mode === 'android' ? 'android' : 'web';
    const apiBase = env.VITE_API_BASE_URL ?? (platform === 'web' ? '/api/v1' : '');
    validateApiBase(apiBase, platform);
    // 앱 최소 버전 검사(#386)에 쓰는 설치 버전. APK와 같은 build.gradle 값을 읽어 둘이 어긋나지 않게 한다.
    const versionCode = platform === 'android' ? androidVersionCode() : 0;

    return {
        plugins: [react()],
        define: {
            __APP_PLATFORM__: JSON.stringify(platform),
            __API_BASE_URL__: JSON.stringify(apiBase),
            __APP_VERSION_CODE__: JSON.stringify(versionCode),
            // Android 소셜 로그인(#388)은 BE가 Android 공개 API를 연 뒤에만 켠다.
            __ANDROID_OAUTH__: JSON.stringify(
                platform === 'android' && env.VITE_ANDROID_OAUTH === 'on',
            ),
        },
        build: { outDir: `dist/${platform}`, emptyOutDir: true },
        server: {
            // 휴대폰에서 ngrok 터널로 개발 서버에 접속할 수 있게 ngrok 도메인만 허용한다
            allowedHosts: ['.ngrok-free.dev', '.ngrok-free.app'],
            proxy: {
                '/api': {
                    target: env.DEV_API_PROXY_TARGET ?? 'http://127.0.0.1:8080',
                    changeOrigin: true,
                    // 채팅 WebSocket(/api/v1/events/{e}/ws)도 같은 BE로 넘긴다.
                    ws: true,
                },
                '/actuator/health': { target: env.DEV_API_PROXY_TARGET ?? 'http://127.0.0.1:8080' },
            },
        },
    };
});

function androidVersionCode() {
    const gradle = readFileSync(new URL('./android/app/build.gradle', import.meta.url), 'utf8');
    const match = /^\s*versionCode\s+(\d+)\s*$/m.exec(gradle);
    if (!match) throw new Error('android/app/build.gradle에서 versionCode를 찾지 못했습니다.');
    return Number(match[1]);
}
