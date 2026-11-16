import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure } from '../../shared/api/failure';
import type { EventSummary } from '../identity/contract';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import { resultText } from '../response/ui';
import { MemberDesk } from './client';
import { InvitePanel } from './InvitePanel';
import type { Member, WorkGroup } from './contract';

// 참여 인원(총괄 WEB, #285·#286·#347·#353). 초대 링크 복사·QR·재발급, 그룹별 인원·가용·예비 확인, 인원의 그룹·예비 지정 변경, 총괄 인계.
// 팀원 본인의 그룹 선택·가용 변경은 참여 화면(identity)에서 한다. 그룹 자체의 추가·수정은 변경안으로 한다.

export function LiveMembersRoute() {
    return (
        <EventAccess>
            {(client, event) => <LiveMembers client={client} event={event} />}
        </EventAccess>
    );
}

function readText(error: unknown) {
    const f = classifyFailure(error);
    if (f.kind === 'cancelled') return '';
    if (f.kind === 'access-denied')
        return '참여 인원은 현재 총괄만 볼 수 있습니다. 서버에 기능이 아직 열리지 않았을 수도 있습니다.';
    if (f.kind === 'unauthenticated') return '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.';
    return '참여 인원을 확인하지 못했습니다. 응답이 계약과 다르면 표시하지 않습니다.';
}

interface Loaded {
    groups: WorkGroup[];
    members: Member[];
    truncated: boolean;
    eventRevision: string;
}

function MemberRow({
    member,
    groups,
    busy,
    onChange,
}: {
    member: Member;
    groups: WorkGroup[];
    busy: boolean;
    onChange: (changes: { workGroupId?: string; isReserve?: boolean }) => void;
}) {
    const usable = groups.filter((g) => g.active !== false);
    return (
        <li className="stack">
            <b className="small">
                {member.displayName}
                {member.eventRole === 'CHIEF' ? ' / 총괄' : ''}
                {member.isReserve ? ' / 예비' : ''}
                {!member.available ? ' / 지금 불가' : ''}
                {!member.active ? ' / 떠남' : ''}
            </b>
            {member.eventRole === 'WORKER' && member.active && (
                <div className="row">
                    <label className="small">
                        그룹{' '}
                        <select
                            aria-label={`${member.displayName} 그룹`}
                            value={member.workGroupId ?? ''}
                            disabled={busy}
                            onChange={(e) => onChange({ workGroupId: e.target.value })}
                        >
                            {member.workGroupId === null && <option value="">미배정</option>}
                            {usable.map((g) => (
                                <option key={g.id} value={g.id}>
                                    {g.name}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label className="small">
                        <input
                            type="checkbox"
                            aria-label={`${member.displayName} 예비 인원`}
                            checked={member.isReserve}
                            disabled={busy}
                            onChange={(e) => onChange({ isReserve: e.target.checked })}
                        />{' '}
                        예비 인원
                    </label>
                </div>
            )}
        </li>
    );
}

/** 총괄 인계(042). 후임과 내 새 그룹을 고르고, 한 번 더 확인한 뒤 보낸다. */
function ChiefTransfer({
    members,
    groups,
    busy,
    onTransfer,
}: {
    members: Member[];
    groups: WorkGroup[];
    busy: boolean;
    onTransfer: (successor: Member, groupId: string) => void;
}) {
    const [successorId, setSuccessorId] = useState('');
    const [groupId, setGroupId] = useState('');
    const [confirming, setConfirming] = useState(false);
    const successor = members.find((m) => m.memberId === successorId);
    const group = groups.find((g) => g.id === groupId);
    const ready = !!successor && !!group;
    return (
        <section aria-label="총괄 인계" className="stack">
            <b>총괄 인계</b>
            <p className="xs">
                후임이 총괄이 되고, 나는 고른 그룹의 팀원이 됩니다. 총괄은 후임을 정하기 전에는
                공연을 떠날 수 없습니다.
            </p>
            {!members.length || !groups.length ? (
                <p className="small">
                    {!members.length
                        ? '넘겨받을 수 있는 참여 인원이 없습니다.'
                        : '내가 옮겨 갈 활성 그룹이 없습니다.'}
                </p>
            ) : (
                <>
                    <label className="small">
                        후임 총괄{' '}
                        <select
                            aria-label="후임 총괄"
                            value={successorId}
                            disabled={busy}
                            onChange={(e) => {
                                setSuccessorId(e.target.value);
                                setConfirming(false);
                            }}
                        >
                            <option value="">선택</option>
                            {members.map((m) => (
                                <option key={m.memberId} value={m.memberId}>
                                    {m.displayName}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label className="small">
                        인계 뒤 내 그룹{' '}
                        <select
                            aria-label="인계 뒤 내 그룹"
                            value={groupId}
                            disabled={busy}
                            onChange={(e) => {
                                setGroupId(e.target.value);
                                setConfirming(false);
                            }}
                        >
                            <option value="">선택</option>
                            {groups.map((g) => (
                                <option key={g.id} value={g.id}>
                                    {g.name}
                                </option>
                            ))}
                        </select>
                    </label>
                    {!confirming ? (
                        <button
                            className="btn"
                            type="button"
                            disabled={busy || !ready}
                            onClick={() => setConfirming(true)}
                        >
                            총괄 넘기기
                        </button>
                    ) : (
                        <div className="stack" role="group" aria-label="총괄 인계 확인">
                            <p className="small" role="alert">
                                {successor?.displayName}에게 총괄을 넘기고 나는 {group?.name} 팀원이
                                됩니다. 넘긴 뒤에는 총괄 기능을 쓸 수 없습니다.
                            </p>
                            <div className="row">
                                <button
                                    className="btn"
                                    type="button"
                                    disabled={busy}
                                    onClick={() => successor && onTransfer(successor, groupId)}
                                >
                                    넘기기 확정
                                </button>
                                <button
                                    className="btn"
                                    type="button"
                                    disabled={busy}
                                    onClick={() => setConfirming(false)}
                                >
                                    취소
                                </button>
                            </div>
                        </div>
                    )}
                </>
            )}
        </section>
    );
}

function LiveMembers({ client, event }: { client: Workflow; event: EventSummary }) {
    const desk = useMemo(() => new MemberDesk(client.session, client.scope), [client]);
    const chief = event.myParticipation?.eventRole === 'CHIEF';
    const [data, setData] = useState<Loaded | null>(null);
    const [message, setMessage] = useState('참여 인원을 불러오고 있습니다.');
    const [notice, setNotice] = useState('');
    const [busy, setBusy] = useState(false);
    const [reload, setReload] = useState(0);
    const navigate = useNavigate();

    useEffect(() => {
        if (!chief) return;
        let active = true;
        // 공연 revision만 필요하다. 계획(client.read)까지 읽지 않는다.
        Promise.all([
            desk.groups(),
            desk.members(),
            client.session.event(client.scope.eventId),
        ]).then(
            ([groups, members, ctx]) => {
                if (!active) return;
                setData({
                    groups: groups.items,
                    members: members.items,
                    truncated: members.truncated,
                    eventRevision: ctx.revision,
                });
                setMessage('');
            },
            (e: unknown) => {
                if (!active) return;
                const f = classifyFailure(e);
                if (f.kind === 'access-denied' || f.kind === 'unauthenticated') setData(null);
                setMessage(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [desk, client, chief, reload]);

    if (!chief)
        return (
            <EntryFrame title="참여 인원">
                <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
                <p role="status">참여 인원 관리는 총괄만 할 수 있습니다.</p>
            </EntryFrame>
        );

    const change = async (
        member: Member,
        changes: { workGroupId?: string; isReserve?: boolean },
    ) => {
        if (busy || !data) return;
        setBusy(true);
        setNotice('');
        try {
            const result = await desk.update(member.memberId, changes, data.eventRevision);
            if (result.kind === 'done') {
                const next = result.data;
                setData(
                    (prev) =>
                        prev && {
                            ...prev,
                            eventRevision: next.eventRevision,
                            members: prev.members.map((m) =>
                                m.memberId === next.member.memberId ? next.member : m,
                            ),
                        },
                );
                setNotice(`${next.member.displayName}의 배정을 바꿨습니다.`);
            } else {
                // 결과를 모르거나 충돌·거절이면 현재 인원을 다시 조회한다
                setReload((n) => n + 1);
                setNotice(resultText(result, '인원 변경'));
            }
        } finally {
            setBusy(false);
        }
    };

    const transfer = async (successor: Member, groupId: string) => {
        const me = event.myParticipation?.memberId;
        if (busy || !data || !me) return;
        setBusy(true);
        setNotice('');
        try {
            const result = await desk.transferChief(
                me,
                successor.memberId,
                groupId,
                data.eventRevision,
            );
            if (result.kind === 'done') {
                // 이제 팀원이다. 참여 화면에서 현재 권한을 다시 조회한다.
                void navigate(`/account/events/${client.scope.eventId}`);
                return;
            }
            setReload((n) => n + 1);
            setNotice(
                result.kind === 'rejected' && result.failure.code === 'INVALID_REFERENCE'
                    ? '고른 후임이나 그룹이 지금은 쓸 수 없습니다. 목록을 다시 조회했으니 다시 골라주세요.'
                    : resultText(result, '총괄 인계'),
            );
        } finally {
            setBusy(false);
        }
    };

    const groupName = new Map(data?.groups.map((g) => [g.id, g.name]) ?? []);
    const sections = data
        ? [
              ...data.groups.map((g) => ({
                  key: g.id,
                  title: `${g.name}${g.active === false ? ' (비활성)' : ''}`,
                  members: data.members.filter(
                      (m) => m.eventRole === 'WORKER' && m.workGroupId === g.id,
                  ),
              })),
              {
                  key: 'none',
                  title: '총괄/미배정',
                  members: data.members.filter(
                      (m) =>
                          m.eventRole === 'CHIEF' ||
                          m.workGroupId === null ||
                          !groupName.has(m.workGroupId),
                  ),
              },
          ]
        : [];

    return (
        <EntryFrame title="참여 인원">
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            <button className="btn" type="button" onClick={() => setReload((n) => n + 1)}>
                참여 인원 새로고침
            </button>
            {message && <p role="status">{message}</p>}
            {notice && <p role="status">{notice}</p>}
            {/* 초대 링크 조회가 실패해도(서버 미제공 등) 인원 목록과 인계는 쓸 수 있게 따로 받는다 */}
            {data && (
                <InvitePanel
                    desk={desk}
                    eventState={event.state}
                    revision={() => Promise.resolve(data.eventRevision)}
                    onRotated={(revision) =>
                        setData((prev) => prev && { ...prev, eventRevision: revision })
                    }
                    refresh={reload}
                    onUnsettled={() => setReload((n) => n + 1)}
                />
            )}
            {data?.truncated && (
                <p role="alert">
                    인원이 많아 일부만 표시했습니다. 새로고침해도 같으면 서버 목록을 확인해주세요.
                </p>
            )}
            {sections.map((s) => (
                <section key={s.key} aria-label={`${s.title} 인원`} className="stack">
                    <b>
                        {s.title} / {s.members.filter((m) => m.active).length}명
                    </b>
                    <ul className="stack">
                        {s.members.map((m) => (
                            <MemberRow
                                key={m.memberId}
                                member={m}
                                groups={data!.groups}
                                busy={busy}
                                onChange={(changes) => void change(m, changes)}
                            />
                        ))}
                        {!s.members.length && <li className="small">인원 없음</li>}
                    </ul>
                </section>
            ))}
            <p className="xs">
                그룹을 새로 만들거나 이름/안내를 바꾸려면 보고/대응의 변경안으로 승인합니다.
            </p>
            {data && event.state !== 'ENDED' && (
                <ChiefTransfer
                    members={data.members.filter(
                        (m) => m.active && m.memberId !== event.myParticipation?.memberId,
                    )}
                    groups={data.groups.filter((g) => g.active !== false)}
                    busy={busy}
                    onTransfer={(m, g) => void transfer(m, g)}
                />
            )}
        </EntryFrame>
    );
}
