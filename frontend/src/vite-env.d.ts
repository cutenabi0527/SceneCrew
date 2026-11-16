/// <reference types="vite/client" />

declare const __APP_PLATFORM__: 'web' | 'android';
declare const __API_BASE_URL__: string;
/** Android 빌드의 versionCode(android/app/build.gradle). WEB은 0이다. */
declare const __APP_VERSION_CODE__: number;
/** Android 소셜 로그인(#388) 사용 여부. VITE_ANDROID_OAUTH=on인 Android 빌드만 true다. */
declare const __ANDROID_OAUTH__: boolean;
