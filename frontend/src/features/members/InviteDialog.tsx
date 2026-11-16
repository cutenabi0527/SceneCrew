import { useMemo, useState } from 'react';
import { useSession } from '../../domain/hooks';
import { useDemoState } from '../../domain/store-context';
import { useLive } from '../../live/context';
import { Dialog } from '../../shared/ui/Dialog';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { ShellFrame } from '../plan-workflow/frame';
import { MemberDesk } from './client';
import { groupInviteUrl } from './invite-url';
import { InvitePanel } from './InvitePanel';
import { InviteQr } from './InviteQr';
import styles from './invite.module.css';

/**
 * 인원 추가(운영 구성도의 그룹 카드 +·초대 자리): 먼저 새로 추가할지 묻고(ask), 그다음 초대 링크를 준다.
 * 초대 링크는 공연 하나에 하나이며, 받은 사람은 로그인 뒤 그룹을 직접 골라 참여한다(자동 참여 없음).
 * 실서버는 서버 초대 링크(복사·QR·재발급), 데모는 데모 참여 주소를 보인다.
 */
/** 한 번에 만들 수 있는 초대 자리 수 상한(행사 정원 31명 안쪽) */
const INVITE_MAX = 30;

export function InviteDialog({
    groupId,
    groupName,
    ask,
    onAdd,
    onClose,
}: {
    groupId: string;
    groupName: string;
    /** true면 "인원을 새로 추가하시겠습니까?"부터 묻는다 */
    ask: boolean;
    /** "예"를 눌렀을 때 입력한 인원 수(그룹 밑에 그만큼 초대 자리를 만든다) */
    onAdd?: (count: number) => void;
    onClose: () => void;
}) {
    const [step, setStep] = useState<'ask' | 'link'>(ask ? 'ask' : 'link');
    const [count, setCount] = useState('1');
    const n = Number(count);
    const valid = Number.isInteger(n) && n >= 1 && n <= INVITE_MAX;
    if (step === 'ask')
        return (
            <Dialog title="인원 추가" onClose={onClose}>
                <form
                    className="stack"
                    onSubmit={(e) => {
                        e.preventDefault();
                        if (!valid) return;
                        onAdd?.(n);
                        setStep('link');
                    }}
                >
                    <p>
                        <b>{groupName}</b>에 인원을 새로 추가하시겠습니까?
                    </p>
                    <div className="field">
                        <label htmlFor="inviteCount">초대할 인원 수</label>
                        <span className="row nw">
                            <input
                                id="inviteCount"
                                className="inp"
                                type="number"
                                inputMode="numeric"
                                min={1}
                                max={INVITE_MAX}
                                step={1}
                                value={count}
                                style={{ width: 96 }}
                                aria-invalid={!valid || undefined}
                                aria-describedby="inviteCountHint"
                                onChange={(e) => setCount(e.target.value)}
                            />
                            <span>명</span>
                        </span>
                        <span id="inviteCountHint" className="hint">
                            {valid
                                ? `${groupName} 인원 맨 밑에 초대 자리 ${n}개를 만듭니다.`
                                : `1명부터 ${INVITE_MAX}명까지 입력해주세요.`}
                        </span>
                    </div>
                    <div className="row" style={{ justifyContent: 'flex-end' }}>
                        <button type="button" className="btn ghost" onClick={onClose}>
                            아니요
                        </button>
                        <button type="submit" className="btn primary" disabled={!valid}>
                            예
                        </button>
                    </div>
                </form>
            </Dialog>
        );
    return (
        <Dialog title="인원 초대" sub={`${groupName}에 함께할 인원에게 보내세요`} onClose={onClose}>
            <InviteLink groupId={groupId} groupName={groupName} />
        </Dialog>
    );
}

function InviteLink({ groupId, groupName }: { groupId: string; groupName: string }) {
    const { live } = useDemoState();
    const { eventId } = useSession();
    if (live && eventId)
        return (
            <EventAccess eventId={eventId} frame={ShellFrame}>
                {(client, event) => (
                    <LiveInvite client={client} eventState={event.state} groupId={groupId} />
                )}
            </EventAccess>
        );
    return <DemoInvite groupId={groupId} groupName={groupName} />;
}

/** 실서버 초대 링크: 조회·복사·QR·재발급(#384) */
function LiveInvite({
    client,
    eventState,
    groupId,
}: {
    client: Workflow;
    eventState: string;
    groupId: string;
}) {
    const desk = useMemo(() => new MemberDesk(client.session, client.scope), [client]);
    const { reload } = useLive();
    return (
        <InvitePanel
            desk={desk}
            eventState={eventState}
            groupId={groupId}
            // 재발급 직전에 지금 공연 revision을 읽는다. 운영 화면 값은 주기 읽기라 늦을 수 있다
            revision={() => client.session.event(client.scope.eventId).then((e) => e.revision)}
            onRotated={() => void reload().catch(() => undefined)}
        />
    );
}

/** 데모 초대 링크: 서버 초대 토큰이 없어 데모 참여 주소에 그룹을 붙여 준다 */
function DemoInvite({ groupId, groupName }: { groupId: string; groupName: string }) {
    const url = groupInviteUrl(`${window.location.origin}/join`, groupId);
    const [notice, setNotice] = useState('');
    const copy = async () => {
        try {
            await navigator.clipboard.writeText(url);
            setNotice('초대 링크를 복사했습니다.');
        } catch {
            setNotice('복사하지 못했습니다. 링크를 직접 선택해 복사해주세요.');
        }
    };
    return (
        <section aria-label="초대 링크" className={`stack ${styles.invite}`}>
            <InviteQr url={url} />
            <p className={styles.link}>{url}</p>
            <div className={`row ${styles.actions}`}>
                <button type="button" className="btn primary" onClick={() => void copy()}>
                    초대 링크 복사
                </button>
                {notice && (
                    <output className="xs muted" role="status">
                        {notice}
                    </output>
                )}
            </div>
            <p className="xs muted">
                {groupName} 전용 링크입니다. 같은 그룹이면 늘 같은 링크이고, 받은 사람은 로그인 뒤
                {groupName}이(가) 골라진 참여 화면에서 직접 참여합니다(자동 참여 없음). 참여한
                순서대로 그룹의 초대 자리를 위에서부터 채웁니다. 데모는 서버 초대 토큰 없이 데모
                참여 화면으로 연결됩니다.
            </p>
        </section>
    );
}
