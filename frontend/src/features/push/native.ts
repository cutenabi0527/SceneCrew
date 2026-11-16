import { registerPlugin } from '@capacitor/core';
import { PushNotifications } from '@capacitor/push-notifications';
import type { PushNative } from './registrar';

// Capacitor 푸시 플러그인과 앱 전용 DeviceSecret 플러그인(android/.../DeviceSecretPlugin.java) 연결.
const DeviceSecret = registerPlugin<{ get(): Promise<{ deviceId: string; deviceProof: string }> }>(
    'DeviceSecret',
);
// 이 빌드에 Firebase 설정이 있는지(android/.../PushSupportPlugin.java, #307)
const PushSupport = registerPlugin<{ available(): Promise<{ available: boolean }> }>('PushSupport');

export const nativePush: PushNative = {
    checkPermissions: () => PushNotifications.checkPermissions(),
    requestPermissions: () => PushNotifications.requestPermissions(),
    register: () => PushNotifications.register(),
    onToken: async (listener) => {
        const handle = await PushNotifications.addListener('registration', (t) =>
            listener(t.value),
        );
        return () => void handle.remove();
    },
    onError: async (listener) => {
        const handle = await PushNotifications.addListener('registrationError', (e) =>
            listener(e.error),
        );
        return () => void handle.remove();
    },
    onOpen: async (listener) => {
        // 알림 data(notificationId·eventId)를 넘긴다. 형식 검사는 registrar가 한다
        const handle = await PushNotifications.addListener('pushNotificationActionPerformed', (a) =>
            listener((a.notification.data ?? {}) as Record<string, unknown>),
        );
        return () => void handle.remove();
    },
    deviceSecret: () => DeviceSecret.get(),
    available: () => PushSupport.available().then((r) => r.available),
};
