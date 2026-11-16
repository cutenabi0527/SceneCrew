import { useContext } from 'react';
import type { FloorPlan } from '../domain/types';
import type { Member } from '../features/members/contract';
import type { Workflow } from '../features/plan-workflow/client';
import { responder, type Responder } from '../features/response/client';
import { SafetyReader } from '../features/safety-map/client';
import { safetyCommands, type SafetyCommands } from '../features/safety-map/commands';
import {
    RISK_LABEL,
    latestByZone,
    type Camera,
    type SafetyMap,
} from '../features/safety-map/contract';
import type { SupportTarget } from '../features/safety-map/SupportPanel';
import { useBackground } from '../features/unconfirmed-draft/useBackground';
import { LiveContext } from './context';

// 실서버 W05·S01(#372 2차): 디자인 안전 화면이 기능 화면의 부품(현재 프레임·카메라 설정·안전 인력 지원)을
// 그대로 쓰도록 LiveBridge가 마지막으로 읽은 안전 지도·카메라와 서버 연결을 넘긴다. 총괄만 있다.

export interface LiveSafetyLink {
    client: Workflow;
    reader: SafetyReader;
    commands: SafetyCommands;
    responder: Responder;
    map: SafetyMap;
    cameras: Camera[];
    /** 계획의 구역·직무 그룹 */
    zones: { id: string; name: string }[];
    groups: { id: string; name: string }[];
    members: Member[];
    eventRevision: string;
}

type Parts = Omit<LiveSafetyLink, 'client' | 'reader' | 'commands' | 'responder'>;

/**
 * 새로 읽은 값으로 연결을 만든다. 같은 공연 연결이면 조회기·명령을 그대로 써서
 * 주기 읽기마다 현재 프레임 조회가 처음부터 다시 시작되지 않게 한다.
 */
export function linkSafety(
    prev: LiveSafetyLink | null,
    client: Workflow,
    parts: Parts,
): LiveSafetyLink {
    if (prev && prev.client === client) return { ...prev, ...parts };
    return {
        client,
        reader: new SafetyReader(client.session, client.scope),
        commands: safetyCommands(client.session, client.scope),
        responder: responder(client.session, client.scope),
        ...parts,
    };
}

/** 실서버 총괄이면 안전 연결, 데모·팀원이면 null */
export function useLiveSafety(): LiveSafetyLink | null {
    return useContext(LiveContext)?.safety ?? null;
}

/**
 * 안전 인력 지원 대상: 최신(FRESH) 관측이 있는 구역. 화면에서 고른 구역을 맨 앞에 두어 처음 골라지게 한다.
 */
export function supportTargets(link: LiveSafetyLink, first?: string): SupportTarget[] {
    const names = new Map(link.zones.map((z) => [z.id, z.name]));
    const targets: SupportTarget[] = [];
    for (const [zoneId, { estimate, observation }] of latestByZone(link.map.observations)) {
        if (observation.freshness !== 'FRESH') continue;
        const count =
            estimate.estimatedCount != null
                ? ` / 추정 ${Math.round(estimate.estimatedCount)}명`
                : '';
        targets.push({
            zoneId,
            observationId: observation.id,
            label: `${names.get(zoneId) ?? '구역'} / ${RISK_LABEL[estimate.riskLevel]}${count}`,
        });
    }
    return targets.sort((a, b) => Number(b.zoneId === first) - Number(a.zoneId === first));
}

/**
 * V9-D62: 실서버 안전 지도의 평면도 배경(발행 때 저장한 PDF 평면도 쪽 그림). 그림이 없거나 데모면 null이다.
 * 평면도(layout)가 바뀌면 다시 받는다.
 */
export function useLiveFloorPlan(): FloorPlan | null {
    const link = useLiveSafety();
    const layout = link?.map.layout ?? null;
    const key = layout ? `${link!.client.scope.eventId}:${layout.id}` : null;
    const bg = useBackground(key, layout ? (signal) => link!.reader.background(signal) : null);
    return bg && layout
        ? {
              src: bg.src,
              crop: bg.crop,
              width: layout.width,
              height: layout.height,
              name: 'PDF 평면도',
          }
        : null;
}
