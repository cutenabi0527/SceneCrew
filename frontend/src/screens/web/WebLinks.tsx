import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CameraLinkCard } from '../../features/camera-link/CameraLinkCard';
import { CameraLinkDesk } from '../../features/camera-link/client';
import type { MyCameraLink } from '../../features/camera-link/contract';
import { EquipmentLinkDesk } from '../../features/equipment-link/client';
import type { MyEquipmentLink, MyReceiver } from '../../features/equipment-link/contract';
import { EquipmentLinkCard, ProgramSetup } from '../../features/equipment-link/EquipmentLinkCard';
import { useLive } from '../../live/context';
import { Empty, Note } from '../../shared/ui/atoms';

/** 목록을 다시 읽는 간격(다른 사람의 연결·해제, 첫 사진 도착을 반영) */
const REFRESH_MS = 10_000;

/**
 * L01 내 연결(#396, V9-D58·D60). 노트북 WEB에서 카메라를 연결하고 2초마다 사진을 보낸다.
 * 팀원은 내 연결 담당 업무의 카메라만, 총괄은 모든 카메라가 보인다(W1).
 * 보내기는 이 탭이 열려 있는 동안만 이어진다. 데모 공연에는 서버가 없어 연결할 수 없다.
 */
export function WebLinks() {
    const { client } = useLive();
    const desk = useMemo(
        () => (client ? new CameraLinkDesk(client.session, client.scope) : null),
        [client],
    );
    const equipmentDesk = useMemo(
        () => (client ? new EquipmentLinkDesk(client.session, client.scope) : null),
        [client],
    );
    const [cameras, setCameras] = useState<MyCameraLink[] | null>(null);
    const [equipment, setEquipment] = useState<MyEquipmentLink[]>([]);
    const [receivers, setReceivers] = useState<MyReceiver[]>([]);
    const [error, setError] = useState('');
    const [sending, setSending] = useState<Set<string>>(() => new Set());
    const seq = useRef(0);

    // 늦게 온 응답이 새 응답을 덮지 않게 순번을 붙인다
    const load = useCallback(() => {
        if (!desk) return;
        const mine = ++seq.current;
        desk.myLinks().then(
            (r) => {
                if (mine !== seq.current) return;
                setCameras(r.cameras);
                setEquipment(r.equipment);
                setReceivers(r.receivers);
                setError('');
            },
            () => {
                if (mine === seq.current)
                    setError('내 연결 목록을 읽지 못했습니다. 잠시 뒤 다시 읽습니다.');
            },
        );
    }, [desk]);

    useEffect(() => {
        if (!desk) return;
        load();
        const t = setInterval(load, REFRESH_MS);
        return () => {
            clearInterval(t);
            seq.current += 1;
        };
    }, [desk, load]);

    const onSendingChange = useCallback((id: string, on: boolean) => {
        setSending((prev) => {
            if (prev.has(id) === on) return prev;
            const next = new Set(prev);
            if (on) next.add(id);
            else next.delete(id);
            return next;
        });
    }, []);

    // 보내는 중에 탭을 닫으려 하면 브라우저가 한 번 묻게 한다
    const anySending = sending.size > 0;
    useEffect(() => {
        if (!anySending) return;
        const warn = (e: BeforeUnloadEvent) => {
            e.preventDefault();
            e.returnValue = '';
        };
        window.addEventListener('beforeunload', warn);
        return () => window.removeEventListener('beforeunload', warn);
    }, [anySending]);

    if (!desk || !equipmentDesk)
        return (
            <Empty icon="info">
                내 연결은 실제 공연에서 노트북 카메라를 서버에 연결하는 화면입니다. 데모 공연에서는
                쓸 수 없습니다.
            </Empty>
        );

    return (
        <div className="stack" style={{ maxWidth: 760, margin: '0 auto', width: '100%' }}>
            <h2>내 연결</h2>
            <p className="small">
                노트북 카메라를 연결하면 2초마다 사진 1장을 서버로 보내 구역 인원만 셉니다.
                녹화·저장하지 않습니다. 무선 마이크 수신기는 노트북 수집 프로그램으로 연결합니다.
            </p>
            {anySending && (
                <Note kind="warn">
                    이 탭을 닫으면 보내기가 끊깁니다. 다른 탭을 보거나 창을 내려도 계속 보내지만,
                    노트북이 잠자기에 들어가지 않게 전원을 연결하고 절전을 꺼 두세요.
                </Note>
            )}
            {error && (
                <p className="small" role="alert">
                    {error}
                </p>
            )}
            {cameras === null ? (
                !error && <p className="small">불러오는 중…</p>
            ) : cameras.length === 0 && equipment.length === 0 ? (
                <Empty icon="info">
                    연결할 카메라·장비가 없습니다. 총괄이 카메라·장비 연결 담당 업무를 맡기면 여기에
                    보입니다.
                </Empty>
            ) : (
                <>
                    {cameras.length > 0 && <h3>카메라</h3>}
                    {cameras.map((c) => (
                        <CameraLinkCard
                            key={c.id}
                            camera={c}
                            desk={desk}
                            onChanged={load}
                            onSendingChange={onSendingChange}
                        />
                    ))}
                    {equipment.length > 0 && (
                        <>
                            <h3>장비 자동 관측</h3>
                            {equipment.some((e) => e.canLink) && (
                                <ProgramSetup desk={equipmentDesk} onIssued={load} />
                            )}
                            {equipment.map((e) => (
                                <EquipmentLinkCard
                                    key={e.id}
                                    item={e}
                                    receivers={receivers}
                                    desk={equipmentDesk}
                                    onChanged={load}
                                />
                            ))}
                        </>
                    )}
                </>
            )}
        </div>
    );
}
