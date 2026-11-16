import { useLayoutEffect, useRef, type KeyboardEvent, type SyntheticEvent } from 'react';
import { DocText } from './DocText';
import { normalizeSteps, STEP_INDENT } from './change-doc';

const PREFIX = /^\s*- /;
const lineAt = (text: string, pos: number) => {
    const start = pos === 0 ? 0 : text.lastIndexOf('\n', pos - 1) + 1;
    const found = text.indexOf('\n', pos);
    const end = found < 0 ? text.length : found;
    const prefix = PREFIX.exec(text.slice(start, end))?.[0].length ?? 0;
    return { start, end, bodyStart: start + prefix };
};

/** 되돌리기 기록이 남도록 브라우저 입력 명령으로 글을 바꾼다 */
function replaceRange(el: HTMLTextAreaElement, start: number, end: number, text: string) {
    el.setSelectionRange(start, end);
    const done = text
        ? document.execCommand('insertText', false, text)
        : document.execCommand('delete');
    if (!done) {
        el.setRangeText(text, start, end, 'end');
        el.dispatchEvent(new Event('input', { bubbles: true }));
    }
}

/**
 * 절차 글 칸: 모든 줄은 글머리표 '-'로 시작한다. Enter는 새 글머리표, Tab·Shift+Tab은 들여쓰기·내어쓰기이고,
 * 글머리표 바로 뒤의 Backspace는 내어쓰거나 윗줄과 합친다. 글머리표는 지워지지 않는다.
 */
export function StepsText({
    value,
    onChange,
    label,
}: {
    value: string;
    onChange: (next: string) => void;
    label: string;
}) {
    const ref = useRef<HTMLTextAreaElement>(null);
    // 붙여넣기 등으로 글머리표를 다시 채웠을 때 커서 자리
    const caret = useRef<number | null>(null);
    useLayoutEffect(() => {
        if (caret.current !== null) ref.current?.setSelectionRange(caret.current, caret.current);
        caret.current = null;
    }, [value]);

    const indent = (el: HTMLTextAreaElement, out: boolean) => {
        const { selectionStart: a, selectionEnd: b, value: text } = el;
        const first = lineAt(text, a).start;
        const last = lineAt(text, b).end;
        const block = text.slice(first, last);
        const next = block
            .split('\n')
            .map((l) =>
                out ? l.replace(new RegExp(`^ {1,${STEP_INDENT.length}}`), '') : STEP_INDENT + l,
            )
            .join('\n');
        if (next === block) return;
        const firstMove = next.split('\n')[0].length - block.split('\n')[0].length;
        replaceRange(el, first, last, next);
        const from = Math.max(a + firstMove, first);
        el.setSelectionRange(from, a === b ? from : b + next.length - block.length);
    };

    const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
        // 한글 조합 중인 키는 입력기에 맡긴다
        if (e.nativeEvent.isComposing) return;
        const el = e.currentTarget;
        if (e.key === 'Tab') {
            e.preventDefault();
            e.stopPropagation();
            indent(el, e.shiftKey);
            return;
        }
        const { selectionStart: a, selectionEnd: b, value: text } = el;
        const line = lineAt(text, a);
        const collapsed = a === b;
        if (e.key === 'Enter') {
            e.preventDefault();
            const lead = text.slice(line.start, line.bodyStart - 2);
            // 빈 글머리표에서 Enter는 내어쓰기
            if (collapsed && a === line.bodyStart && line.end === a) {
                if (lead) indent(el, true);
                return;
            }
            replaceRange(el, a, b, `\n${lead}- `);
        } else if (e.key === 'Backspace' && collapsed && a === line.bodyStart) {
            e.preventDefault();
            if (text.slice(line.start, a) !== '- ') indent(el, true);
            else if (line.start > 0) replaceRange(el, line.start - 1, a, '');
        } else if (e.key === 'Delete' && collapsed && a === line.end && a < text.length) {
            e.preventDefault();
            replaceRange(el, a, lineAt(text, a + 1).bodyStart, '');
        } else if (e.key === 'ArrowLeft' && !e.shiftKey && collapsed && a === line.bodyStart) {
            e.preventDefault();
            if (line.start > 0) el.setSelectionRange(line.start - 1, line.start - 1);
        }
    };

    // 커서가 글머리표 안으로 들어가지 않게 한다
    const onSelect = (e: SyntheticEvent<HTMLTextAreaElement>) => {
        const el = e.currentTarget;
        if (el.selectionStart !== el.selectionEnd) return;
        const { bodyStart } = lineAt(el.value, el.selectionStart);
        if (el.selectionStart < bodyStart) el.setSelectionRange(bodyStart, bodyStart);
    };

    return (
        <DocText
            inputRef={(el) => {
                ref.current = el;
            }}
            label={label}
            value={value}
            onKeyDown={onKeyDown}
            onSelect={onSelect}
            onChange={(raw) => {
                const next = normalizeSteps(raw);
                if (next !== raw && ref.current)
                    caret.current = normalizeSteps(raw.slice(0, ref.current.selectionStart)).length;
                onChange(next);
            }}
        />
    );
}
