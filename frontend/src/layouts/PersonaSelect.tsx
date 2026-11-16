import { useData, useSession } from '../domain/hooks';
import { roleLabel } from '../domain/selectors';
import { useDemoState, useDispatch } from '../domain/store-context';

/** 데모 전용: 같은 행사를 다른 참여자 권한으로 본다(와이어프레임의 "보기" 선택). */
export function PersonaSelect({ className }: { className?: string }) {
    const d = useData();
    const { me } = useSession();
    const dispatch = useDispatch();
    // 실서버 모드에서는 다른 참여자로 바꿔 보는 기능을 쓰지 않는다(실제 계정의 권한만 쓴다).
    if (useDemoState().live) return null;
    return (
        <label className={className}>
            <span>보기</span>
            <select
                value={me ?? ''}
                onChange={(e) => dispatch({ type: 'persona', me: e.target.value })}
            >
                {d.members.map((m) => (
                    <option key={m.id} value={m.id}>
                        {m.name} / {roleLabel(d, m)}
                    </option>
                ))}
            </select>
        </label>
    );
}
