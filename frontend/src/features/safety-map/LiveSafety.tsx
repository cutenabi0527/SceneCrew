import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure } from '../../shared/api/failure';
import type { EventSummary } from '../identity/contract';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { displayTime } from '../plan-workflow/time';
import { MemberDesk } from '../members/client';
import type { Member } from '../members/contract';
import { responder as responderFor } from '../response/client';
import { SafetyReader } from './client';
import { safetyCommands } from './commands';
import { CameraSettings, MapSettings } from './SafetySettings';
import {
    CONNECTION_LABEL,
    FRESHNESS_LABEL,
    RISK_LABEL,
    RISK_ORDER,
    latestByZone,
    type Camera,
    type Point,
    type SafetyMap,
    connectionHint,
} from './contract';
import styles from './safety-map.module.css';
import { qualityReasonsText } from './quality';
import { SupportPanel, type SupportTarget } from './SupportPanel';
import { useSnapshot } from './useSnapshot';

// 실제 서버의 고정 카메라 안전 지도(W05, 총괄 WEB). 구역별 추정 인원·밀집도·기준 대비를 비교하고
// 카메라 현재 프레임을 본다. 관측만으로 인력을 옮기지 않는다. 지원 이동은 후보를 골라 만든 변경안을 승인한다(#361).

/** 지도와 카메라 목록을 다시 읽는 주기 */
export const REFRESH_MS = 15_000;

export function LiveSafetyRoute() {
    return (
        <EventAccess>{(client, event) => <LiveSafety client={client} event={event} />}</EventAccess>
    );
}

function readText(error: unknown) {
    const f = classifyFailure(error);
    if (f.kind === 'cancelled') return '';
    if (f.kind === 'access-denied')
        return '안전 지도는 현재 총괄만 볼 수 있습니다. 서버에 안전 기능이 아직 열리지 않았을 수도 있습니다.';
    if (f.kind === 'unauthenticated') return '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.';
    return '안전 정보를 확인하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.';
}

const centroid = (points: Point[]) => ({
    x: points.reduce((n, p) => n + p.x, 0) / points.length,
    y: points.reduce((n, p) => n + p.y, 0) / points.length,
});

function SafetyMapView({ map, names }: { map: SafetyMap; names: Map<string, string> }) {
    const latest = latestByZone(map.observations);
    if (!map.layout) return <p className="small">확인된 공연장 배치도가 없습니다.</p>;
    const ratio = map.layout.height / map.layout.width;
    return (
        <svg
            className={styles.map}
            viewBox={`0 0 1 ${ratio}`}
            role="img"
            aria-label="구역별 추정 인원 지도"
        >
            {map.layout.zonePolygons.map((zone) => {
                const point = centroid(zone.points);
                const found = latest.get(zone.zoneId);
                // 최신 관측이 아니면 위험도 색 대신 회색으로 두어 현재값처럼 보이지 않게 한다
                const tone =
                    found && found.observation.freshness === 'FRESH'
                        ? found.estimate.riskLevel
                        : 'NOT_CURRENT';
                const count = found?.estimate.estimatedCount;
                return (
                    <g key={zone.zoneId}>
                        <polygon
                            className={styles.zone}
                            points={zone.points.map((p) => `${p.x},${p.y * ratio}`).join(' ')}
                        />
                        <circle
                            className={`${styles.point} ${styles[tone]}`}
                            cx={point.x}
                            cy={point.y * ratio}
                            r={0.025}
                        />
                        <text className={styles.label} x={point.x} y={point.y * ratio + 0.06}>
                            {names.get(zone.zoneId) ?? '구역'}
                            {count != null ? ` ${Math.round(count)}명` : ''}
                        </text>
                    </g>
                );
            })}
        </svg>
    );
}

function CameraFrame({ reader, camera }: { reader: SafetyReader; camera: Camera }) {
    const { frame, status } = useSnapshot(reader, camera.id);
    return (
        <section aria-label="카메라 현재 화면" className="stack">
            <b>{camera.name} 현재 화면</b>
            {frame && (
                <>
                    <img
                        className={styles.frame}
                        src={frame.url}
                        alt={`${camera.name} 현재 프레임`}
                    />
                    <p className="xs">
                        촬영 {frame.capturedAt ? displayTime(frame.capturedAt) : '시각 미상'} / 수신{' '}
                        {frame.receivedAt ? displayTime(frame.receivedAt) : '시각 미상'} /{' '}
                        {frame.freshness === 'FRESH'
                            ? '최신 프레임'
                            : frame.freshness === 'STALE'
                              ? '오래된 프레임'
                              : '최신성 알 수 없음'}
                    </p>
                </>
            )}
            {status && <p role="status">{status}</p>}
            <p className="xs">
                원본은 녹화하지 않고 현재 프레임만 봅니다. 화면을 닫거나 다른 탭으로 가면 받기를
                멈춥니다.
            </p>
        </section>
    );
}

function LiveSafety({ client, event }: { client: Workflow; event: EventSummary }) {
    const reader = useMemo(() => new SafetyReader(client.session, client.scope), [client]);
    const chief = event.myParticipation?.eventRole === 'CHIEF';
    const [map, setMap] = useState<SafetyMap | null>(null);
    const [cameras, setCameras] = useState<Camera[]>([]);
    const [names, setNames] = useState(new Map<string, string>());
    const [message, setMessage] = useState('안전 지도를 불러오고 있습니다.');
    const [selected, setSelected] = useState<string | null>(null);
    const [reload, setReload] = useState(0);
    const commands = useMemo(() => safetyCommands(client.session, client.scope), [client]);
    const [eventRevision, setEventRevision] = useState<string | null>(null);
    const [planZones, setPlanZones] = useState<{ id: string; name: string }[]>([]);
    const [planGroups, setPlanGroups] = useState<{ id: string; name: string }[]>([]);
    const [settings, setSettings] = useState(false);
    const [members, setMembers] = useState<Member[]>([]);
    const responder = useMemo(() => responderFor(client.session, client.scope), [client]);

    useEffect(() => {
        if (!chief) return;
        let active = true;
        Promise.all([
            reader.map(),
            reader.cameras(),
            client.read(),
            // 지원 후보 이름 표시용. 실패해도 안전 지도는 보인다
            new MemberDesk(client.session, client.scope).members().catch(() => null),
        ]).then(
            ([m, page, ctx, people]) => {
                if (!active) return;
                if (people) setMembers(people.items);
                setMap(m);
                setCameras(page.items);
                setNames(new Map(ctx.plan.zones.map((z) => [z.id, z.name])));
                setPlanZones(ctx.plan.zones.map((z) => ({ id: z.id, name: z.name })));
                setPlanGroups(ctx.plan.workGroups.map((g) => ({ id: g.id, name: g.name })));
                setEventRevision(ctx.event.revision);
                setMessage('');
            },
            (e: unknown) => {
                if (!active) return;
                const f = classifyFailure(e);
                // 권한을 잃으면 열린 화면의 안전 정보도 지운다
                if (f.kind === 'access-denied' || f.kind === 'unauthenticated') {
                    setMap(null);
                    setCameras([]);
                    setSelected(null);
                }
                setMessage(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [reader, client, chief, reload]);

    useEffect(() => {
        if (!chief) return;
        const tick = () => {
            if (document.visibilityState === 'visible') setReload((n) => n + 1);
        };
        const timer = window.setInterval(tick, REFRESH_MS);
        return () => window.clearInterval(timer);
    }, [chief]);

    if (!chief)
        return (
            <EntryFrame title="안전">
                <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
                <p role="status">안전 지도와 카메라 화면은 총괄만 볼 수 있습니다.</p>
            </EntryFrame>
        );

    const latest = latestByZone(map?.observations ?? []);
    const zones = map
        ? [...new Set([...(map.layout?.zonePolygons.map((z) => z.zoneId) ?? []), ...latest.keys()])]
        : [];
    const sorted = zones
        .map((zoneId) => ({ zoneId, found: latest.get(zoneId) }))
        .sort(
            (a, b) =>
                RISK_ORDER.indexOf(a.found?.estimate.riskLevel ?? 'UNASSESSED') -
                    RISK_ORDER.indexOf(b.found?.estimate.riskLevel ?? 'UNASSESSED') ||
                (b.found?.estimate.estimatedCount ?? -1) - (a.found?.estimate.estimatedCount ?? -1),
        );
    const shownCamera = cameras.find((c) => c.id === selected) ?? null;
    // 지원 대상: 최신(FRESH) 관측이 있는 구역을 위험한 순서로
    const targets: SupportTarget[] = sorted.flatMap(({ zoneId, found }) =>
        found && found.observation.freshness === 'FRESH'
            ? [
                  {
                      zoneId,
                      observationId: found.observation.id,
                      label: `${names.get(zoneId) ?? '구역'} · ${RISK_LABEL[found.estimate.riskLevel]}${
                          found.estimate.estimatedCount != null
                              ? ` · 추정 ${Math.round(found.estimate.estimatedCount)}명`
                              : ''
                      }`,
                  },
              ]
            : [],
    );

    return (
        <EntryFrame title="안전">
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            <p className="small">
                총괄: 고정 카메라의 구역별 추정 인원과 기준 대비를 비교합니다. 추정값이며, 지원 인력
                이동은 아래 안전 인력 지원에서 후보를 골라 지원안을 만든 뒤 승인합니다. 보고에서
                시작한 대응은{' '}
                <Link to={`/account/events/${client.scope.eventId}/issues`}>보고/대응</Link>에서
                합니다.
            </p>
            <button className="btn" type="button" onClick={() => setReload((n) => n + 1)}>
                안전 지도 새로고침
            </button>
            {message && <p role="status">{message}</p>}
            {map && (
                <>
                    <SafetyMapView map={map} names={names} />
                    <ul aria-label="지도 범례" className={styles.legend}>
                        {RISK_ORDER.map((r) => (
                            <li key={r} className="xs">
                                <svg className={styles.swatch} viewBox="0 0 1 1" aria-hidden="true">
                                    <circle className={styles[r]} cx={0.5} cy={0.5} r={0.5} />
                                </svg>
                                {RISK_LABEL[r]}
                            </li>
                        ))}
                        <li className="xs">
                            <svg className={styles.swatch} viewBox="0 0 1 1" aria-hidden="true">
                                <circle className={styles.NOT_CURRENT} cx={0.5} cy={0.5} r={0.5} />
                            </svg>
                            최신 관측 아님
                        </li>
                    </ul>
                    <ul aria-label="구역 비교" className="stack">
                        {sorted.map(({ zoneId, found }) => {
                            const profile = map.profiles.find((p) => p.zoneId === zoneId);
                            return (
                                <li key={zoneId} className="small">
                                    <b>{names.get(zoneId) ?? '구역'}</b> /{' '}
                                    {found ? RISK_LABEL[found.estimate.riskLevel] : '관측 없음'}
                                    {found?.estimate.estimatedCount != null &&
                                        ` / 추정 ${Math.round(found.estimate.estimatedCount)}명`}
                                    {found?.estimate.densityPerM2 != null &&
                                        ` / ${found.estimate.densityPerM2.toFixed(2)}명/㎡`}
                                    {found &&
                                        ` / ${FRESHNESS_LABEL[found.observation.freshness]} / ${
                                            found.observation.capturedAt
                                                ? displayTime(found.observation.capturedAt)
                                                : '촬영 시각 미상'
                                        }`}
                                    {profile?.effectiveAreaM2 != null &&
                                        ` / 유효 면적 ${profile.effectiveAreaM2}㎡`}
                                    {!!found?.estimate.qualityReasons.length && (
                                        <span className="xs">
                                            {' '}
                                            (관측 품질:{' '}
                                            {qualityReasonsText(found.estimate.qualityReasons)})
                                        </span>
                                    )}
                                </li>
                            );
                        })}
                        {!sorted.length && <li className="small">관측한 구역이 없습니다.</li>}
                    </ul>
                </>
            )}
            {map && eventRevision && (
                <SupportPanel
                    reader={reader}
                    responder={responder}
                    targets={targets}
                    zones={planZones}
                    groups={planGroups}
                    members={members}
                    eventRevision={eventRevision}
                    onRefresh={async () => setReload((n) => n + 1)}
                />
            )}
            <b>카메라</b>
            <ul aria-label="카메라 목록" className="stack">
                {cameras.map((c) => (
                    <li key={c.id}>
                        <button
                            type="button"
                            className="btn block"
                            aria-pressed={c.id === selected}
                            onClick={() => setSelected(c.id)}
                        >
                            {c.name} / {CONNECTION_LABEL[c.connectionState]} /{' '}
                            {FRESHNESS_LABEL[c.freshness]}
                        </button>
                        <p className="xs">
                            {c.sourceBindingRef === null ? (
                                '웹 연결'
                            ) : (
                                <>
                                    연결 식별자 <code>{c.sourceBindingRef}</code>
                                </>
                            )}{' '}
                            / 마지막 촬영{' '}
                            {c.lastCapturedAt ? displayTime(c.lastCapturedAt) : '없음'} / 분석{' '}
                            {c.lastProcessedAt ? displayTime(c.lastProcessedAt) : '없음'}
                        </p>
                        {connectionHint(c) && <p className="xs">{connectionHint(c)}</p>}
                    </li>
                ))}
                {!cameras.length && !message && (
                    <li className="small">등록된 카메라가 없습니다.</li>
                )}
            </ul>
            {shownCamera && (
                <CameraFrame key={shownCamera.id} reader={reader} camera={shownCamera} />
            )}
            {map && eventRevision && (
                <>
                    <button
                        className="btn"
                        type="button"
                        aria-expanded={settings}
                        onClick={() => setSettings((v) => !v)}
                    >
                        {settings ? '설정 닫기' : '안전 지도/카메라 설정'}
                    </button>
                    {settings && (
                        <>
                            <MapSettings
                                key={`${String(map.layout?.revision ?? 'none')}:${map.profiles.length}`}
                                commands={commands}
                                map={map}
                                zones={planZones}
                                groups={planGroups}
                                eventRevision={eventRevision}
                                onChanged={() => setReload((n) => n + 1)}
                            />
                            <CameraSettings
                                commands={commands}
                                map={map}
                                cameras={cameras}
                                zones={planZones}
                                eventRevision={eventRevision}
                                onChanged={() => setReload((n) => n + 1)}
                                reader={reader}
                            />
                        </>
                    )}
                </>
            )}
        </EntryFrame>
    );
}
