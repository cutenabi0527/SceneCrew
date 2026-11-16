import { useEffect, useRef, useState } from 'react';
import { CONNECTION_LABEL } from '../safety-map/contract';
import { Chip, Note } from '../../shared/ui/atoms';
import { stopReason, type CameraLinkDesk } from './client';
import type { MyCameraLink } from './contract';
import {
    FrameSender,
    keepAwake,
    openCamera,
    videoCapture,
    videoDevices,
    type SenderStatus,
} from './sender';

// 내 연결(L01, #396)의 카메라 칸 하나. 총괄 카메라 설정(S01, #397)에서도 같은 부품을 쓴다.
// 흐름: 안내 한 줄 확인 → 카메라 권한 → 카메라 고르기·내 기기 미리보기 → 보내기 시작(W2) → 2초마다 사진(W4).
// 보내는 중인 칸은 넘겨받기 확인 뒤 takeover로 연결한다. 403·409를 받으면 멈추고 이유를 보인다.

export const CONSENT_TEXT =
    '이 카메라 화면은 2초마다 1장씩 서버로 보내 구역 인원만 세고, 녹화·저장하지 않습니다.';

type Step =
    | { kind: 'idle' }
    | { kind: 'consent' }
    | { kind: 'pick'; devices: MediaDeviceInfo[]; deviceId: string }
    | { kind: 'takeover' }
    | { kind: 'sending' };

const INPUT_LABEL = { BROWSER: '웹 연결', COLLECTOR: '예비 수집 프로그램' } as const;

export function CameraLinkCard({
    camera,
    desk,
    onChanged,
    onSendingChange,
}: {
    camera: MyCameraLink;
    desk: CameraLinkDesk;
    /** 서버 상태가 바뀌었을 수 있다(목록 다시 읽기) */
    onChanged: () => void;
    /** 이 탭이 보내는 중인지(화면 경고 문구용) */
    onSendingChange?: (cameraId: string, sending: boolean) => void;
}) {
    const [step, setStep] = useState<Step>({ kind: 'idle' });
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState<SenderStatus | null>(null);
    const video = useRef<HTMLVideoElement>(null);
    const stream = useRef<MediaStream | null>(null);
    const sender = useRef<FrameSender | null>(null);
    const awake = useRef<{ release(): void } | null>(null);
    const linkId = useRef<string | null>(null);
    const sending = step.kind === 'sending';

    const closeCamera = () => {
        sender.current?.stop();
        sender.current = null;
        stream.current?.getTracks().forEach((t) => t.stop());
        stream.current = null;
        awake.current?.release();
        awake.current = null;
        linkId.current = null;
    };
    // 화면을 떠나면 카메라와 보내기를 멈춘다(탭을 닫으면 끊긴다는 경고와 같다)
    useEffect(() => closeCamera, []);
    useEffect(() => {
        onSendingChange?.(camera.id, sending);
    }, [camera.id, sending, onSendingChange]);

    const attach = async (deviceId?: string) => {
        stream.current?.getTracks().forEach((t) => t.stop());
        stream.current = await openCamera(deviceId);
        if (video.current) {
            video.current.srcObject = stream.current;
            await video.current.play().catch(() => undefined);
        }
    };

    // 안내 확인 → 권한 → 기기 목록·미리보기
    const allow = async () => {
        setBusy(true);
        setMessage('');
        try {
            await attach();
            const devices = await videoDevices();
            const current = stream.current?.getVideoTracks()[0]?.getSettings().deviceId ?? '';
            setStep({ kind: 'pick', devices, deviceId: current });
        } catch {
            setMessage(
                '카메라를 열지 못했습니다. 브라우저 주소창의 카메라 권한을 허용하고, 다른 프로그램이 카메라를 쓰고 있지 않은지 확인하세요.',
            );
            closeCamera();
            setStep({ kind: 'idle' });
        } finally {
            setBusy(false);
        }
    };

    const choose = async (deviceId: string) => {
        if (step.kind !== 'pick') return;
        setStep({ ...step, deviceId });
        try {
            await attach(deviceId);
        } catch {
            setMessage('고른 카메라를 열지 못했습니다. 다른 카메라를 골라 보세요.');
        }
    };

    const startSending = (id: string) => {
        linkId.current = id;
        const v = video.current;
        if (!v) return;
        const s = new FrameSender({
            capture: videoCapture(v),
            send: (f) => desk.sendFrame(camera.id, { linkId: id, ...f }),
            stopReason,
            onStatus: (next) => {
                setStatus(next);
                if (next.kind === 'stopped') {
                    closeCamera();
                    setStep({ kind: 'idle' });
                    if (next.reason) setMessage(next.reason);
                    onChanged();
                }
            },
        });
        sender.current = s;
        s.start();
        void keepAwake().then((lock) => {
            awake.current = lock;
        });
        setStep({ kind: 'sending' });
    };

    // 보내기 시작(W2). 보내는 중이면 넘겨받기 확인으로 간다
    const link = async (takeover: boolean) => {
        setBusy(true);
        setMessage('');
        try {
            const result = await desk.link(camera.id, camera.revision, takeover);
            switch (result.kind) {
                case 'linked':
                    startSending(result.link.linkId);
                    onChanged();
                    return;
                case 'taken':
                    setStep({ kind: 'takeover' });
                    return;
                case 'conflict':
                    setMessage(
                        '카메라 설정이 바뀌었습니다. 목록을 다시 불러왔으니 한 번 더 눌러 주세요.',
                    );
                    break;
                case 'rejected':
                    setMessage(
                        result.failure.code === 'CAMERA_LINK_FORBIDDEN'
                            ? '이 카메라를 연결할 자격이 없습니다. 연결 담당 업무가 대기·진행 중인지 확인하세요.'
                            : result.failure.code === 'CAMERA_DISABLED'
                              ? '사용 안 함으로 둔 카메라입니다. 총괄에게 다시 사용으로 바꿔 달라고 하세요.'
                              : '연결하지 못했습니다. 공연 상태와 참여 정보를 확인하세요.',
                    );
                    break;
                case 'check-state':
                    setMessage(
                        '연결 결과를 확인하지 못했습니다. 목록을 다시 불러왔으니 상태를 확인하세요.',
                    );
                    break;
            }
            closeCamera();
            setStep({ kind: 'idle' });
            onChanged();
        } finally {
            setBusy(false);
        }
    };

    const unlink = async () => {
        setBusy(true);
        setMessage('');
        sender.current?.stop();
        closeCamera();
        setStep({ kind: 'idle' });
        try {
            const r = await desk.unlink(camera.id);
            setMessage(
                r === 'unlinked' || r === 'already'
                    ? '연결을 해제했습니다. 이 칸은 등록 안됨으로 돌아갑니다.'
                    : r === 'rejected'
                      ? '연결을 해제할 수 없습니다. 연결한 사람이나 총괄만 해제할 수 있습니다.'
                      : '해제 결과를 확인하지 못했습니다. 목록을 다시 불러왔으니 상태를 확인하세요.',
            );
        } finally {
            setBusy(false);
            onChanged();
        }
    };

    const cancel = () => {
        closeCamera();
        setStep({ kind: 'idle' });
        setMessage('');
    };

    return (
        <section className="card stack" aria-label={`${camera.name} 연결`}>
            <div className="row between">
                <b>{camera.name}</b>
                <span className="row">
                    {sending ? (
                        <Chip kind="accent">이 탭에서 보내는 중</Chip>
                    ) : (
                        <Chip>{CONNECTION_LABEL[camera.connectionState]}</Chip>
                    )}
                    {camera.inputKind && <Chip>{INPUT_LABEL[camera.inputKind]}</Chip>}
                    {camera.calibrationRequired && <Chip kind="warn">보정 필요</Chip>}
                </span>
            </div>
            <p className="small">
                {camera.linkTask
                    ? `연결 담당 업무: ${camera.linkTask.title}`
                    : '연결 담당 업무가 정해지지 않은 카메라입니다.'}
                {camera.linkedByMe &&
                    !sending &&
                    ' / 내가 연결해 둔 칸입니다(다른 브라우저일 수 있습니다).'}
            </p>

            {step.kind === 'consent' && (
                <div className="stack">
                    <Note>{CONSENT_TEXT}</Note>
                    <div className="row">
                        <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={allow}
                        >
                            카메라 권한 허용하고 계속
                        </button>
                        <button type="button" className="btn ghost" onClick={cancel}>
                            취소
                        </button>
                    </div>
                </div>
            )}

            {/* 내 기기 미리보기(방향 확인). 보내는 동안에도 같은 영상에서 사진을 잡는다 */}
            <video
                ref={video}
                aria-label={`${camera.name} 내 기기 미리보기`}
                muted
                playsInline
                hidden={step.kind !== 'pick' && step.kind !== 'takeover' && !sending}
                style={{ width: '100%', maxWidth: 480, borderRadius: 8, background: '#000' }}
            />

            {step.kind === 'pick' && (
                <div className="stack">
                    {step.devices.length > 1 && (
                        <label className="field">
                            <span>카메라 고르기</span>
                            <select
                                value={step.deviceId}
                                onChange={(e) => void choose(e.target.value)}
                            >
                                {step.devices.map((d, i) => (
                                    <option key={d.deviceId || i} value={d.deviceId}>
                                        {d.label || `카메라 ${i + 1}`}
                                    </option>
                                ))}
                            </select>
                        </label>
                    )}
                    <p className="small">
                        미리보기로 카메라 방향이 객석을 비추는지 확인한 뒤 보내기를 시작하세요.
                    </p>
                    <div className="row">
                        <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={() => void link(false)}
                        >
                            보내기 시작
                        </button>
                        <button type="button" className="btn ghost" onClick={cancel}>
                            취소
                        </button>
                    </div>
                </div>
            )}

            {step.kind === 'takeover' && (
                <div className="stack" role="alertdialog" aria-label="넘겨받기 확인">
                    <Note kind="warn">
                        다른 사람(또는 다른 브라우저)이 이 카메라를 보내는 중입니다. 넘겨받으면 그쪽
                        보내기는 멈춥니다.
                    </Note>
                    <div className="row">
                        <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={() => void link(true)}
                        >
                            넘겨받기
                        </button>
                        <button type="button" className="btn ghost" onClick={cancel}>
                            취소
                        </button>
                    </div>
                </div>
            )}

            {sending && (
                <p className="small" role="status">
                    보내는 중
                    {status?.kind === 'sending' &&
                        ` / 보낸 사진 ${status.sent}장${status.skipped ? ` / 건너뜀 ${status.skipped}` : ''}`}
                </p>
            )}

            {step.kind === 'idle' && (
                <div className="row">
                    {camera.canLink && (
                        <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={() => {
                                setMessage('');
                                setStep({ kind: 'consent' });
                            }}
                        >
                            이 컴퓨터 카메라 연결
                        </button>
                    )}
                    {camera.linkedByMe && (
                        <button type="button" className="btn" disabled={busy} onClick={unlink}>
                            연결 해제
                        </button>
                    )}
                </div>
            )}
            {sending && (
                <div className="row">
                    <button type="button" className="btn" disabled={busy} onClick={unlink}>
                        연결 해제
                    </button>
                </div>
            )}
            {message && (
                <p className="small" role="status">
                    {message}
                </p>
            )}
        </section>
    );
}
