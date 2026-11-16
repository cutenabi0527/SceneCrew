import { describe, expect, it } from 'vitest';
import { microphoneRequest, nsProbeText, parseNsProbe, resolveNsProbe } from './ns-probe';

function memoryStore() {
    const values = new Map<string, string>();
    return {
        store: () => ({
            getItem: (key: string) => values.get(key) ?? null,
            setItem: (key: string, value: string) => void values.set(key, value),
        }),
    };
}

describe('소음 억제 확인 표시', () => {
    it('표시가 없으면 운영과 같이 audio: true로 요청한다', () => {
        expect(microphoneRequest(null)).toEqual({ audio: true });
        expect(microphoneRequest('on')).toEqual({ audio: true });
    });

    it('off는 소음 억제만, raw는 에코 제거/자동 이득까지 끈다', () => {
        expect(microphoneRequest('off')).toEqual({ audio: { noiseSuppression: false } });
        expect(microphoneRequest('raw')).toEqual({
            audio: { noiseSuppression: false, echoCancellation: false, autoGainControl: false },
        });
    });

    it('일반 쿼리와 Hash 쿼리를 모두 읽고, 정해진 값만 받는다', () => {
        expect(parseNsProbe('?ns=off', '')).toBe('off');
        expect(parseNsProbe('?ns=raw', '#/e/report')).toBe('raw');
        expect(parseNsProbe('', '#/login?ns=raw')).toBe('raw');
        expect(parseNsProbe('?demo-review=load&ns=off', '#/app')).toBe('off');
        expect(parseNsProbe('', '')).toBeNull();
        expect(parseNsProbe('?ns=OFF', '#/login?ns=false')).toBeNull();
    });

    it('앱 안에서 이동해 쿼리가 사라져도 같은 탭에서는 유지하고, ns=on으로 되돌린다', () => {
        const { store } = memoryStore();
        expect(resolveNsProbe('', '', store)).toBeNull();
        expect(resolveNsProbe('?ns=raw', '', store)).toBe('raw');
        expect(resolveNsProbe('', '#/e/report', store)).toBe('raw');
        expect(resolveNsProbe('?ns=nope', '', store)).toBe('raw');
        expect(resolveNsProbe('?ns=on', '', store)).toBe('on');
        expect(resolveNsProbe('', '', store)).toBe('on');
    });

    it('저장소를 쓸 수 없어도 주소의 표시는 그대로 쓴다', () => {
        const blocked = () => {
            throw new DOMException('막힘', 'SecurityError');
        };
        expect(resolveNsProbe('?ns=off', '', blocked)).toBe('off');
        expect(resolveNsProbe('', '', blocked)).toBeNull();
    });

    it('브라우저가 알려 준 값을 표시하고, 없는 값은 없음으로 쓴다', () => {
        expect(
            nsProbeText({
                mode: 'raw',
                fileName: 'x.webm',
                settings: { noiseSuppression: false, echoCancellation: true },
            }),
        ).toBe('확인용 ns=raw / 소음 억제 false / 에코 제거 true / 자동 이득 없음');
    });
});
