import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useData, useEvent, useMe } from '../../domain/hooks';
import { bundlesOf, unassignedBundles } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import { Note } from '../../shared/ui/atoms';
import { EntryFrame } from './EntryFrame';
import styles from './entry.module.css';

/**
 * C03 그룹 선택 참여(공유 화면 · P03). 초대 카드에는 그룹명·사전 업무 시간 요약·선택 가능만 보이고
 * 실제 담당자·이슈·채팅은 참여 전 공개하지 않는다. 참여 성공과 업무 배정은 따로 표시한다.
 */
export function JoinScreen() {
    const d = useData();
    const ev = useEvent();
    const { invitee } = useMe();
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const [reserve, setReserve] = useState(false);
    const [available, setAvailable] = useState(true);
    const groups = d.groups.filter((g) => bundlesOf(d, g.id).length > 0);
    // 그룹별 초대 링크(?group=)로 들어오면 그 그룹을 골라 둔다. 고를 수 없는 그룹이면 무시한다
    const [params] = useSearchParams();
    const linked = params.get('group');
    const [groupId, setGroupId] = useState(() =>
        linked && groups.some((g) => g.id === linked) ? linked : '',
    );

    return (
        <EntryFrame
            title={`${ev.name} 초대`}
            sub={`${invitee?.name ?? ''} 님, 함께할 직무 그룹을 골라 참여하세요.`}
        >
            <form
                className="stack"
                onSubmit={(e) => {
                    e.preventDefault();
                    if (!groupId) return;
                    dispatch({ type: 'join', groupId, reserve, available });
                    navigate('/e/home', { replace: true });
                }}
            >
                <fieldset className={styles.groups}>
                    <legend className="lbl">직무 그룹</legend>
                    {groups.map((g) => {
                        const all = bundlesOf(d, g.id).flatMap((b) => b.tasks);
                        const first = all.reduce((a, t) => (t.start < a ? t.start : a), '99:99');
                        const last = all.reduce((a, t) => (t.end > a ? t.end : a), '00:00');
                        const open = unassignedBundles(d, g.id).length;
                        return (
                            <label key={g.id} className={styles.group}>
                                <input
                                    type="radio"
                                    name="group"
                                    value={g.id}
                                    checked={groupId === g.id}
                                    onChange={() => setGroupId(g.id)}
                                />
                                <span className="grow">
                                    <b>{g.name}</b>
                                    <span className="xs muted" style={{ display: 'block' }}>
                                        사전 업무 {all.length}건 / {first}–{last}
                                        {open > 0
                                            ? ` / 맡을 수 있는 묶음 ${open}`
                                            : ' / 지금 맡을 묶음 없음'}
                                    </span>
                                    <span className="xs faint" style={{ display: 'block' }}>
                                        {g.guide}
                                    </span>
                                </span>
                            </label>
                        );
                    })}
                </fieldset>
                <label className="check">
                    <input
                        type="checkbox"
                        checked={reserve}
                        onChange={(e) => setReserve(e.target.checked)}
                    />{' '}
                    예비 인원으로 참여 (바로 업무를 받지 않고, 총괄이 투입을 확정하면 맡습니다)
                </label>
                <label className="check">
                    <input
                        type="checkbox"
                        checked={available}
                        onChange={(e) => setAvailable(e.target.checked)}
                    />{' '}
                    지금 활동할 수 있습니다
                </label>
                <Note>
                    참여하면 AI가 같은 그룹에서 아직 담당이 없는 업무 묶음을 찾아 배정합니다. 배정이
                    늦어지거나 맡을 묶음이 없어도 참여는 그대로 유지됩니다.
                </Note>
                <button type="submit" className="btn primary block" disabled={!groupId}>
                    이 그룹으로 참여
                </button>
                <button
                    type="button"
                    className="btn ghost block"
                    onClick={() => {
                        dispatch({ type: 'leaveEvent' });
                        navigate('/events');
                    }}
                >
                    나중에 참여
                </button>
            </form>
        </EntryFrame>
    );
}
