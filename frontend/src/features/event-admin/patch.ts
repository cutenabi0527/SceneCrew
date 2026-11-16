import { ContractViolationError } from '../../shared/api/contract';
import { BASIC_FIELDS, type BasicKey } from './labels';
import { fromLocalInput } from './time';

/**
 * 기본정보 편집 칸 값 → 024(PATCH /events/{id}) 본문. 공연 관리 화면과 PDF 탭 자동 진행(V9-D65)이 같이 쓴다.
 * 시각은 입력 칸 값(이 기기 시간대)을 오프셋이 있는 시각으로 바꾸고 빈 칸은 null로 비운다.
 * 필수가 아닌 칸을 비우면 null, 숫자는 앞뒤 공백을 뺀다. 고치지 않은 칸은 싣지 않는다.
 */
export function basicsPatch(
    edits: Partial<Record<BasicKey, string>>,
): Record<string, string | null> {
    const body: Record<string, string | null> = {};
    for (const f of BASIC_FIELDS) {
        const v = edits[f.key];
        if (v === undefined) continue;
        if (f.kind === 'time') {
            if (v === '') body[f.key] = null;
            else {
                const iso = fromLocalInput(v);
                if (!iso)
                    throw new ContractViolationError(f.key, `${f.label} 형식이 올바르지 않습니다.`);
                body[f.key] = iso;
            }
        } else if (v.trim() === '' && !('required' in f && f.required)) body[f.key] = null;
        else body[f.key] = f.kind === 'number' ? v.trim() : v;
    }
    return body;
}
