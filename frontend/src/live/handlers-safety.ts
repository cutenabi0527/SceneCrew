import type { Action } from '../domain/reducer';
import { safetyCommands } from '../features/safety-map/commands';
import { DEFAULT_SIZE, pointsOf } from '../features/unconfirmed-draft/layout';
import type { Handler, LiveRuntime } from './runtime';
import { resultText } from './runtime';

// 안전(W05·S01)의 쓰기 액션을 안전 지도·카메라 명령으로 바꾼다(#372).
// 화면 값은 직접 바꾸지 않고, 끝나면 LiveBridge가 서버에서 다시 읽어 그린다.

type Of<T extends Action['type']> = Extract<Action, { type: T }>;
function expect<T extends Action['type']>(action: Action, type: T): Of<T> {
    if (action.type !== type) throw new Error('잘못된 액션');
    return action as Of<T>;
}

const commands = (r: LiveRuntime) => safetyCommands(r.session, r.client.scope);
const ratio = (v: number) => Math.min(1, Math.max(0, Math.round(v * 10) / 1000));

export const SAFETY_HANDLERS: Partial<Record<Action['type'], Handler>> = {
    /**
     * 평면도 도형 저장(PATCH safety-map). 계획에 있는 구역의 도형만 바꾸고, 면적·위험 기준·최소 인원은
     * 서버가 준 값을 그대로 돌려보낸다. 새 구역·이름 변경·평면도 그림은 실서버에서 받지 않는다.
     */
    async setFloorPlan(r, action) {
        const a = expect(action, 'setFloorPlan');
        const map = r.ops.safety?.map;
        if (!map) return '안전 지도를 읽지 못해 저장하지 않았습니다. 잠시 뒤 다시 시도해주세요.';
        const planZones = new Set(r.plan?.zones.map((z) => z.id) ?? []);
        const zones = a.zones.filter((z) => z.id && planZones.has(z.id));
        if (!zones.length) return '저장할 구역 도형이 없습니다.';
        const result = await commands(r).saveMap(
            map,
            {
                width: map.layout?.width ?? DEFAULT_SIZE.width,
                height: map.layout?.height ?? DEFAULT_SIZE.height,
                zones: zones.map((z) => {
                    const prev = map.profiles.find((p) => p.zoneId === z.id);
                    const rule = prev?.riskRule as { condition?: string } | null | undefined;
                    return {
                        zoneId: z.id!,
                        points: pointsOf({
                            x: ratio(z.rect[0]),
                            y: ratio(z.rect[1]),
                            width: ratio(z.rect[2]),
                            height: ratio(z.rect[3]),
                        }),
                        effectiveAreaM2: prev?.effectiveAreaM2 ?? null,
                        riskCondition: rule?.condition ?? null,
                    };
                }),
            },
            r.eventRevision,
        );
        return result.kind === 'done'
            ? '평면도를 저장했습니다.'
            : resultText(result, '평면도 저장');
    },

    /** 카메라 사용·중지(PATCH safety-cameras enabled) */
    async cameraActive(r, action) {
        const a = expect(action, 'cameraActive');
        const camera = r.ops.safety?.cameras.find((c) => c.id === a.cameraId);
        if (!camera) return '카메라를 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        const result = await commands(r).updateCamera(camera, { enabled: a.active });
        return result.kind === 'done'
            ? a.active
                ? `${camera.name}을(를) 다시 사용합니다.`
                : `${camera.name}을(를) 중지했습니다. 미리보기와 관측이 닫힙니다.`
            : resultText(result, '카메라 설정');
    },
};
