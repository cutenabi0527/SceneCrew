import { useEffect, useState } from 'react';
import { kstTime } from '../../live/convert';
import { Note } from '../../shared/ui/atoms';
import { AutoChip, AutoDetail } from '../equipment/EquipmentParts';
import { linkFailureText, readRelease, type EquipmentLinkDesk } from './client';
import type { LinkCode, MyEquipmentLink, MyReceiver, ProgramRelease } from './contract';
import { receiverLabel, toAuto } from './view';

// 내 연결(L01)의 장비 연결(#398, V9-D59). 노트북에서 수집 프로그램을 켜고 WEB에서 받은 코드를 넣으면,
// 프로그램이 찾은 수신기가 여기 보이고 "이 수신기 = 이 장비 칸"을 고른다. 토큰·수신기 IP는 화면 어디에도 없다.

/** 수집 프로그램 내려받기와 연결 코드. 코드는 사람·노트북 단위라 장비마다가 아니라 한 번 받는다. */
export function ProgramSetup({
    desk,
    onIssued,
}: {
    desk: EquipmentLinkDesk;
    onIssued: () => void;
}) {
    const [release, setRelease] = useState<ProgramRelease | null | undefined>(undefined);
    const [code, setCode] = useState<LinkCode | null>(null);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');

    useEffect(() => {
        const ac = new AbortController();
        void readRelease(ac.signal).then((r) => {
            if (!ac.signal.aborted) setRelease(r);
        });
        return () => ac.abort();
    }, []);

    const issue = async () => {
        setBusy(true);
        setMessage('');
        try {
            const r = await desk.issueCode();
            if (r.kind === 'issued') setCode(r.code);
            else if (r.kind === 'rejected') setMessage(linkFailureText(r.failure));
            else setMessage('코드를 받았는지 확인하지 못했습니다. 다시 눌러 새 코드를 받으세요.');
            onIssued();
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="card stack" aria-label="장비 수집 프로그램">
            <b>장비 연결 준비</b>
            <ol className="small stack s6" style={{ margin: 0, paddingLeft: 20 }}>
                <li>
                    수신기와 LAN으로 연결한 노트북에서 수집 프로그램을 내려받아 실행합니다.
                    {release === undefined && ' (내려받기 정보를 읽는 중…)'}
                </li>
                <li>아래에서 연결 코드를 받아 프로그램에 넣습니다(10분 안에 한 번).</li>
                <li>프로그램이 찾은 수신기가 아래 장비 칸에 보이면 골라서 연결합니다.</li>
            </ol>
            {release ? (
                <div className="stack s6">
                    <a className="btn" href={`/downloads/${release.file}`} download>
                        수집 프로그램 내려받기 (Windows, {release.version}
                        {release.bytes ? `, ${(release.bytes / 1024 / 1024).toFixed(1)}MB` : ''})
                    </a>
                    <p className="xs muted" style={{ overflowWrap: 'anywhere' }}>
                        SHA-256 {release.sha256}
                    </p>
                    <Note>
                        서명하지 않은 프로그램이라 Windows가 "PC 보호" 창을 띄우면 "추가 정보 →
                        실행"을 누르세요. 공연 중에는 노트북 쪽 LAN 케이블을 고정하세요.
                    </Note>
                </div>
            ) : (
                release === null && (
                    <p className="small" role="status">
                        내려받기 정보를 찾지 못했습니다. 총괄에게 수집 프로그램 파일을 받아 주세요.
                    </p>
                )
            )}
            <div className="row">
                <button type="button" className="btn primary" disabled={busy} onClick={issue}>
                    {code ? '새 연결 코드 받기' : '연결 코드 받기'}
                </button>
                {code && (
                    <span className="row" role="status" aria-label="연결 코드">
                        <b style={{ fontSize: 22, letterSpacing: 2 }}>{code.code}</b>
                        <span className="xs muted">{kstTime(code.expiresAt)}까지</span>
                    </span>
                )}
            </div>
            {message && (
                <p className="small" role="alert">
                    {message}
                </p>
            )}
        </section>
    );
}

type Step = { kind: 'idle' } | { kind: 'takeover'; receiver: MyReceiver } | { kind: 'unlink' };

/** 장비 칸 하나. 자동 관측 상태와 핵심 값을 보이고, 찾은 수신기를 이 칸에 연결·해제한다 */
export function EquipmentLinkCard({
    item,
    receivers,
    desk,
    onChanged,
}: {
    item: MyEquipmentLink;
    receivers: MyReceiver[];
    desk: EquipmentLinkDesk;
    onChanged: () => void;
}) {
    const [choice, setChoice] = useState('');
    const [step, setStep] = useState<Step>({ kind: 'idle' });
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const auto = toAuto(item.autoObservation);
    const keyOf = (r: { collectorId: string; receiverKey: string }) =>
        `${r.collectorId}/${r.receiverKey}`;
    const linked = item.link ? receivers.find((r) => keyOf(r) === keyOf(item.link!)) : undefined;
    // 이미 이 칸에 연결된 수신기는 고를 필요가 없다
    const choices = receivers.filter((r) => r.linkedAssetId !== item.id);
    const picked = choices.find((r) => keyOf(r) === choice) ?? null;

    const link = async (receiver: MyReceiver, takeover: boolean) => {
        setBusy(true);
        setMessage('');
        try {
            const r = await desk.link(item.id, receiver, takeover);
            if (r.kind === 'linked') {
                setStep({ kind: 'idle' });
                setChoice('');
                setMessage('연결했습니다. 프로그램이 10초 안에 이 수신기 수집을 시작합니다.');
            } else if (r.kind === 'taken') setStep({ kind: 'takeover', receiver });
            else if (r.kind === 'rejected') setMessage(linkFailureText(r.failure));
            else setMessage('연결됐는지 확인하지 못했습니다. 목록을 다시 확인하세요.');
            onChanged();
        } finally {
            setBusy(false);
        }
    };

    const unlink = async () => {
        setBusy(true);
        setMessage('');
        try {
            const r = await desk.unlink(item.id);
            setStep({ kind: 'idle' });
            setMessage(
                r === 'unlinked' || r === 'already'
                    ? '연결을 해제했습니다. 연결된 장비가 남지 않은 프로그램은 멈춥니다.'
                    : r === 'rejected'
                      ? '연결을 해제하지 못했습니다. 연결한 사람이나 총괄만 해제할 수 있습니다.'
                      : '해제됐는지 확인하지 못했습니다. 목록을 다시 확인하세요.',
            );
            onChanged();
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="card stack" aria-label={`${item.name} 장비 연결`}>
            <div className="row between">
                <b>{item.name}</b>
                <AutoChip auto={auto} />
            </div>
            <p className="small">
                {item.linkTask
                    ? `연결 담당 업무: ${item.linkTask.title}`
                    : '연결 담당 업무가 정해지지 않은 장비입니다.'}
                {item.link &&
                    ` / 연결된 수신기: ${linked ? receiverLabel(linked) : '다른 사람의 노트북 수신기'}`}
                {item.linkedByMe && ' / 내가 연결한 칸입니다.'}
            </p>
            <AutoDetail auto={auto} />

            {step.kind === 'takeover' && (
                <div className="stack" role="alertdialog" aria-label="넘겨받기 확인">
                    <Note kind="warn">
                        이 장비 칸이 다른 수신기로 수집 중이거나, 고른 수신기가 다른 장비 칸에
                        연결돼 있습니다. 넘겨받으면 그쪽 연결은 끊깁니다.
                    </Note>
                    <div className="row">
                        <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={() => void link(step.receiver, true)}
                        >
                            넘겨받기
                        </button>
                        <button
                            type="button"
                            className="btn ghost"
                            onClick={() => setStep({ kind: 'idle' })}
                        >
                            취소
                        </button>
                    </div>
                </div>
            )}

            {step.kind === 'unlink' && (
                <div className="stack" role="alertdialog" aria-label="연결 해제 확인">
                    <Note kind="warn">
                        연결을 해제하면 이 장비 칸의 자동 관측이 멈춥니다. 연결된 장비가 남지 않은
                        수집 프로그램은 멈추고, 다시 쓰려면 새 연결 코드가 필요합니다.
                    </Note>
                    <div className="row">
                        <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={unlink}
                        >
                            연결 해제
                        </button>
                        <button
                            type="button"
                            className="btn ghost"
                            onClick={() => setStep({ kind: 'idle' })}
                        >
                            취소
                        </button>
                    </div>
                </div>
            )}

            {step.kind === 'idle' && item.canLink && (
                <div className="row" style={{ flexWrap: 'wrap' }}>
                    {choices.length > 0 ? (
                        <>
                            <label className="field" style={{ minWidth: 240 }}>
                                <span>찾은 수신기</span>
                                <select value={choice} onChange={(e) => setChoice(e.target.value)}>
                                    <option value="">수신기 고르기</option>
                                    {choices.map((r) => (
                                        <option key={keyOf(r)} value={keyOf(r)}>
                                            {receiverLabel(r)}
                                            {r.linkedAssetId ? ' - 다른 장비 칸에 연결됨' : ''}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            <button
                                type="button"
                                className="btn primary"
                                disabled={busy || !picked}
                                onClick={() => picked && void link(picked, false)}
                            >
                                이 수신기로 연결
                            </button>
                        </>
                    ) : (
                        <p className="small muted">
                            프로그램이 찾은 수신기가 없습니다. 수집 프로그램을 켜고 코드를 넣으면
                            10초 안에 여기 보입니다.
                        </p>
                    )}
                </div>
            )}
            {step.kind === 'idle' && item.link && (item.linkedByMe || item.canLink) && (
                <div className="row">
                    <button
                        type="button"
                        className="btn"
                        disabled={busy}
                        onClick={() => setStep({ kind: 'unlink' })}
                    >
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
