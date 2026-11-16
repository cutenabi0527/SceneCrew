import { Capacitor } from '@capacitor/core';
import type { AppPlatform } from './config';

export const platform: AppPlatform = __APP_PLATFORM__;
export const isNative = Capacitor.isNativePlatform();
export const apiBaseUrl = __API_BASE_URL__;
export const appVersionCode = __APP_VERSION_CODE__;
/** Android 소셜 로그인(#388). BE가 Android 공개 API를 연 뒤 빌드 환경값 VITE_ANDROID_OAUTH=on으로 켠다. */
export const androidOAuthEnabled = __ANDROID_OAUTH__;
