import { useEffect, useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import { resultText } from '../response/ui';
import type { MemberDesk } from './client';
import type { InviteDraft } from './contract';
import { groupInviteUrl } from './invite-url';
import { InviteQr } from './InviteQr';
import styles from './invite.module.css';

// 초대 링크 조회·복사·QR·재발급(총괄, #353·#384). 참여 인원 기능 화면과 운영진(W06)이 같이 쓴다.
// 링크에는 초대 토큰이 들어 있다. 화면에만 보이고 외부로 보내지 않는다(QR도 브라우저에서 그린다).

export function InvitePanel({
    desk,
    eventState,
    revision,
    onRotated,
    refresh = 0,
    onUnsettled,
    groupId,
}: {
    desk: MemberDesk;
    eventState: string;
    /** 재발급(033)에 보낼 지금 공연 revision */
    revision: () => Promise<string>;
    /** 재발급 뒤 바뀐 공연 revision */
    onRotated?: (revision: string) => void;
    /** 값이 바뀌면 초대 링크를 다시 읽는다(부모 화면의 새로고침) */
    refresh?: number;
    /** 재발급이 충돌·거절·결과 불명으로 끝났다. 부모가 공연 revision을 다시 읽게 한다 */
    onUnsettled?: () => void;
    /** 주면 그 그룹 전용 링크(참여 화면이 그 그룹을 골라 둔 채 열림)를 보이고 복사한다 */
    groupId?: string;
}) {
    const [invite, setInvite] = useState<InviteDraft | null>(null);
    const [failed, setFailed] = useState(false);
    const [notice, setNotice] = useState('');
    const [busy, setBusy] = useState(false);
    const [rotating, setRotating] = useState(false);
    const [reload, setReload] = useState(0);

    useEffect(() => {
        let active = true;
        desk.invite().then(
            (value) => {
                if (!active) return;
                setInvite(value);
                setFailed(false);
            },
            (e: unknown) => {
                if (!active) return;
                setInvite(null);
                setFailed(classifyFailure(e).kind !== 'cancelled');
            },
        );
        return () => {
            active = false;
        };
    }, [desk, reload, refresh]);

    const url = invite && (groupId ? groupInviteUrl(invite.inviteUrl, groupId) : invite.inviteUrl);
    const copy = async () => {
        if (!url) return;
        try {
            await navigator.clipboard.writeText(url);
            setNotice('초대 링크를 복사했습니다.');
        } catch {
            setNotice('복사하지 못했습니다. 링크를 직접 선택해 복사해주세요.');
        }
    };

    const rotate = async () => {
        if (busy) return;
        setBusy(true);
        setNotice('');
        try {
            const result = await desk.rotateInvite(await revision());
            setRotating(false);
            if (result.kind === 'done') {
                setInvite(result.data);
                setFailed(false);
                onRotated?.(result.data.revision);
                setNotice(
                    '초대 링크를 새로 만들었습니다. 이전 링크와 QR은 더 이상 쓸 수 없습니다.',
                );
            } else {
                // 결과를 모르거나 충돌·거절이면 현재 링크와 공연 revision을 다시 조회한다
                setReload((n) => n + 1);
                onUnsettled?.();
                setNotice(resultText(result, '초대 링크 재발급'));
            }
        } catch {
            setNotice('초대 링크를 새로 만들지 못했습니다. 연결을 확인해주세요.');
        } finally {
            setBusy(false);
        }
    };

    const open = eventState === 'RECRUITING' || eventState === 'LIVE';
    return (
        <section aria-label="초대 링크" className={`stack ${styles.invite}`}>
            <b>초대 링크</b>
            {notice && <p role="status">{notice}</p>}
            {invite && (
                <>
                    {/* QR을 먼저 크게 보이고, 그 아래 초대 링크를 둥근 상자에 둔다(모두 가운데) */}
                    {url && <InviteQr url={url} />}
                    <p className={styles.link}>{url}</p>
                    <div className={`row ${styles.actions}`}>
                        <button className="btn" type="button" onClick={() => void copy()}>
                            초대 링크 복사
                        </button>
                        {!rotating && (
                            <button
                                className="btn"
                                type="button"
                                disabled={busy}
                                onClick={() => setRotating(true)}
                            >
                                링크 새로 만들기
                            </button>
                        )}
                    </div>
                    <p className="xs">
                        링크를 받은 사람은 로그인 뒤 그룹을 골라 직접 참여합니다. 자동으로
                        참여시키지 않습니다.
                    </p>
                </>
            )}
            {failed && (
                <p className="small" role="status">
                    초대 링크를 불러오지 못했습니다. 인원 확인과 변경은 그대로 할 수 있습니다.
                </p>
            )}
            {/* 읽지 못했으면 먼저 다시 조회한다. 새로 만들면 이미 나눠 준 링크·QR이 무효가 되므로 확인을 거친다
                (033은 링크가 없어도 발급한다) */}
            {!invite && failed && (
                <div className="row">
                    <button
                        className="btn"
                        type="button"
                        disabled={busy}
                        onClick={() => setReload((n) => n + 1)}
                    >
                        초대 링크 다시 조회
                    </button>
                    {open && !rotating && (
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={() => setRotating(true)}
                        >
                            초대 링크 새로 만들기
                        </button>
                    )}
                </div>
            )}
            {rotating && (
                <div className="stack" role="group" aria-label="초대 링크 재발급 확인">
                    <p className="small">
                        새 링크를 만들면 지금 링크와 QR은 바로 쓸 수 없게 됩니다. 이미 참여한 인원은
                        그대로입니다.
                    </p>
                    <div className="row">
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={() => void rotate()}
                        >
                            새 링크 만들기
                        </button>
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={() => setRotating(false)}
                        >
                            취소
                        </button>
                    </div>
                </div>
            )}
        </section>
    );
}
