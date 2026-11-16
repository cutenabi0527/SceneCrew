import { useState } from 'react';
import { useData, useEvent, useViewer } from '../../domain/hooks';
import { groupName, mentionsIn, nameOf, receivesIssueForward } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import type { Issue } from '../../domain/types';
import styles from './issue.module.css';

const NOTE_MAX = 500;

/**
 * 이슈 전달(총괄): 다른 부서 전체 또는 그 부서의 특정 사람에게 보내고, 본문에 @이름을 쓰면 그 사람도 알린다.
 * 받은 사람·부서는 이 이슈를 볼 수 있게 되며 알림함으로 받는다. 전달은 업무 배정·변경이 아니다.
 */
export function IssueForwardPanel({ issue: i }: { issue: Issue }) {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const dispatch = useDispatch();
    const [groupId, setGroupId] = useState('');
    const [memberId, setMemberId] = useState('');
    const [note, setNote] = useState('');
    const [sent, setSent] = useState('');
    if (!v.isChief || ev.state !== 'LIVE') return null;

    const people = d.members.filter((m) => m.role === 'WORKER' && m.groupId === groupId);
    const mentions = mentionsIn(d, note).filter((id) => id !== v.me?.id);
    // 입력 중인 마지막 @단어로 사람을 추천한다
    const typing = /@(\S*)$/.exec(note)?.[1];
    const suggest =
        typing === undefined
            ? []
            : d.members
                  .filter((m) => m.id !== v.me?.id && m.name.startsWith(typing))
                  .filter((m) => !mentions.includes(m.id) || m.name !== typing)
                  .slice(0, 6);
    const canSend = !!groupId || mentions.length > 0;

    return (
        <div className={styles.forward}>
            <div className="row">
                <div className="field grow">
                    <select
                        className="inp"
                        aria-label="전달할 부서 (태그만 보낼 때는 비움)"
                        value={groupId}
                        onChange={(e) => {
                            setGroupId(e.target.value);
                            setMemberId('');
                        }}
                    >
                        <option value="">부서</option>
                        {d.groups.map((g) => (
                            <option key={g.id} value={g.id}>
                                {g.name}
                            </option>
                        ))}
                    </select>
                </div>
                <div className="field grow">
                    <select
                        className="inp"
                        aria-label="받는 사람 (비우면 부서 전체)"
                        value={memberId}
                        disabled={!groupId}
                        onChange={(e) => setMemberId(e.target.value)}
                    >
                        <option value="">받는 사람</option>
                        {people.map((m) => (
                            <option key={m.id} value={m.id}>
                                {m.name}
                            </option>
                        ))}
                    </select>
                </div>
            </div>
            <div className="field">
                <textarea
                    className="inp"
                    aria-label="전달 내용 (@이름으로 사람을 태그)"
                    rows={2}
                    maxLength={NOTE_MAX}
                    value={note}
                    placeholder="예) 스테이지 통로 확보 부탁드립니다 @박민수"
                    onChange={(e) => {
                        setNote(e.target.value);
                        setSent('');
                    }}
                />
            </div>
            {suggest.length > 0 && (
                <div className={styles.mentionList} role="listbox" aria-label="태그할 사람">
                    {suggest.map((m) => (
                        <button
                            key={m.id}
                            type="button"
                            role="option"
                            aria-selected={false}
                            className="btn sm"
                            onClick={() => setNote(note.replace(/@(\S*)$/, `@${m.name} `))}
                        >
                            @{m.name}{' '}
                            <span className="xs muted">
                                {m.role === 'CHIEF' ? '총괄' : groupName(d, m.groupId)}
                            </span>
                        </button>
                    ))}
                </div>
            )}
            {mentions.length > 0 && (
                <span className="xs">태그: {mentions.map((id) => nameOf(d, id)).join(', ')}</span>
            )}
            {/* 전달 버튼은 위 입력 칸과 같은 너비로 둔다 */}
            <div className="stack s6">
                <button
                    type="button"
                    className={`btn primary ${styles.sendBtn}`}
                    disabled={!canSend}
                    onClick={() => {
                        dispatch({
                            type: 'forwardIssue',
                            issueId: i.id,
                            groupId: groupId || null,
                            memberId: memberId || null,
                            note,
                        });
                        setSent(
                            memberId
                                ? `${nameOf(d, memberId)}에게 전달했습니다`
                                : groupId
                                  ? `${groupName(d, groupId)} 전체에 전달했습니다`
                                  : '태그한 사람에게 전달했습니다',
                        );
                        setNote('');
                        setMemberId('');
                    }}
                >
                    전달
                </button>
                {sent && (
                    <span className="xs muted" role="status">
                        {sent}
                    </span>
                )}
            </div>
        </div>
    );
}

/** 전달 기록: 총괄은 전체, 팀원은 본인에게 전달된 기록만 본다. */
export function IssueForwardLog({ issue: i }: { issue: Issue }) {
    const d = useData();
    const v = useViewer();
    const forwards = i.forwards?.filter((f) => v.isChief || receivesIssueForward(f, v.me));
    if (!forwards?.length) return null;
    return (
        <div className={styles.forward}>
            <h4>전달 기록</h4>
            <ul className={styles.forwardLog}>
                {forwards.map((f, k) => (
                    <li key={`${f.at}-${k}`}>
                        <span className="mono xs">{f.at}</span>{' '}
                        <b className="small">
                            {f.memberId
                                ? nameOf(d, f.memberId)
                                : f.groupId
                                  ? `${groupName(d, f.groupId)} 전체`
                                  : '태그'}
                        </b>
                        {f.mentions.length > 0 && (
                            <span className="xs">
                                {' '}
                                @{f.mentions.map((id) => nameOf(d, id)).join(' @')}
                            </span>
                        )}
                        {f.note && <p className="small">{f.note}</p>}
                    </li>
                ))}
            </ul>
        </div>
    );
}
