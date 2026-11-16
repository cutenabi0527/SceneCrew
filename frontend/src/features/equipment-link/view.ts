import type { EquipmentAuto } from '../../domain/types';
import { kstClock } from '../../live/convert';
import type { AutoObservation } from '../equipment/live-contract';
import type { MyReceiver } from './contract';

// 내 연결 장비 칸(#398)의 화면 값. 모르는 값은 null 그대로 둔다.

export function toAuto(a: AutoObservation): EquipmentAuto {
    return {
        state: a.state,
        inputKind: a.inputKind,
        transmitterOn: a.transmitterOn,
        batteryBars: a.batteryBars,
        rfLevel: a.rfLevel,
        audioLevel: a.audioLevel,
        lastReceivedAt: a.lastReceivedAt ? kstClock(a.lastReceivedAt) : null,
    };
}

export function receiverLabel(r: MyReceiver): string {
    const name = r.name ?? r.model ?? '수신기';
    return r.hostLabel ? `${name} (${r.hostLabel})` : name;
}
