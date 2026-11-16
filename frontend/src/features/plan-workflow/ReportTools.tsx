import { useEffect, useRef, useState } from 'react';
import report from './report.module.css';

// 보고서 틀(ReportFrame) 위 고정 바의 형광펜·지우개. 이 화면에서 보기만 바꾸며 서버에 저장하지 않는다
// (화면을 떠나면 사라진다).
//
// - 형광펜: 켜 둔 동안 커서가 노란 형광펜 모양이 되고, 드래그한 글자만 노랗게 칠한다. 칠할 때마다 한 획이 되고 획마다 순서가 붙는다.
// - 지우개: 형광펜을 켜면 그 왼쪽에 나타난다. 켜면 커서가 지우개 모양이 되고, 칠한 곳을 누르면 그 자리의 가장 최근 획 하나만 지운다(PDF 형광펜처럼).
//
// 칠하는 방법은 두 가지이며 둘 다 글자가 칠보다 앞에 또렷하게 보인다.
// - 입력 칸 밖의 글자(제목·항목 이름 등): CSS Custom Highlight. 글자 뒤 배경만 칠하고 DOM을 바꾸지 않는다.
// - 입력 칸(한 줄 칸·여러 줄 칸) 안의 글자: 브라우저가 칸 안 글자를 칠할 방법을 주지 않으므로, 칸과 같은 글꼴·여백으로
//   글자를 투명하게 그린 층을 칸 '뒤'에 두고 칸 배경을 투명하게 해 칠이 글자 밑으로 비치게 한다.
//   칸의 내용을 고치면 그 칸의 칠은 지운다(글자 위치가 바뀌어 엉뚱한 곳이 칠해지지 않게).

const MARKER = 'report-marker';
const canMarkText = typeof CSS !== 'undefined' && 'highlights' in CSS;

type Tool = 'marker' | 'eraser' | null;
type TextField = HTMLInputElement | HTMLTextAreaElement;
type Stroke = { start: number; end: number; order: number };
type FieldMarks = { strokes: Stroke[]; layer: HTMLDivElement };
type TextMark = { range: Range; order: number };

function isTextField(el: EventTarget | null): el is TextField {
    return (
        el instanceof HTMLTextAreaElement ||
        (el instanceof HTMLInputElement && ['text', 'search', ''].includes(el.type))
    );
}

const COPIED = [
    'fontFamily',
    'fontSize',
    'fontWeight',
    'fontStyle',
    'letterSpacing',
    'wordSpacing',
    'textIndent',
    'textTransform',
    'lineHeight',
    'paddingTop',
    'paddingRight',
    'paddingBottom',
    'paddingLeft',
    'tabSize',
] as const;

/** 칸 뒤 층을 칸의 안쪽(테두리 안) 자리·글자 모양·스크롤에 맞춘다 */
function place(field: TextField, marks: FieldMarks, host: HTMLElement) {
    const { layer } = marks;
    const inner = layer.firstElementChild as HTMLDivElement;
    if (!field.isConnected) {
        layer.style.display = 'none';
        return;
    }
    const cs = getComputedStyle(field);
    const f = field.getBoundingClientRect();
    const h = host.getBoundingClientRect();
    const bl = parseFloat(cs.borderLeftWidth);
    const bt = parseFloat(cs.borderTopWidth);
    layer.style.display = '';
    layer.style.left = `${f.left - h.left + host.scrollLeft + bl}px`;
    layer.style.top = `${f.top - h.top + host.scrollTop + bt}px`;
    layer.style.width = `${field.clientWidth}px`;
    layer.style.height = `${field.clientHeight}px`;
    layer.style.borderRadius = cs.borderRadius;
    for (const key of COPIED) inner.style[key] = cs[key];
    const area = field instanceof HTMLTextAreaElement;
    inner.style.width = `${field.clientWidth}px`;
    inner.style.whiteSpace = area ? 'pre-wrap' : 'pre';
    inner.style.overflowWrap = area ? 'break-word' : 'normal';
    if (!area) {
        // 한 줄 칸은 글자를 위아래 가운데에 둔다
        const content =
            field.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
        inner.style.lineHeight = `${content}px`;
        inner.style.height = `${field.clientHeight}px`;
    }
    inner.style.transform = `translate(${-field.scrollLeft}px, ${-field.scrollTop}px)`;
}

/** 획 끝점마다 글자를 나누고, 칠한 조각에는 그 자리를 덮는 가장 최근 획의 순서를 단다(지우개가 그 획을 찾는다) */
function draw(field: TextField, marks: FieldMarks) {
    const inner = marks.layer.firstElementChild as HTMLDivElement;
    const text = field.value;
    const cuts = new Set([0, text.length]);
    for (const s of marks.strokes) cuts.add(s.start).add(s.end);
    const points = [...cuts].sort((a, b) => a - b);
    inner.replaceChildren();
    for (let i = 0; i < points.length - 1; i++) {
        const [from, to] = [points[i], points[i + 1]];
        const top = marks.strokes
            .filter((s) => s.start <= from && s.end >= to)
            .reduce<Stroke | null>((best, s) => (!best || s.order > best.order ? s : best), null);
        const piece = text.slice(from, to);
        if (!top) {
            inner.append(piece);
            continue;
        }
        const mark = document.createElement('mark');
        mark.dataset.order = String(top.order);
        mark.textContent = piece;
        inner.append(mark);
    }
    // 여러 줄 칸의 끝 줄바꿈도 높이를 차지하게 한 칸 더 둔다
    if (field instanceof HTMLTextAreaElement) inner.append(' ');
}

/** 칸을 층 앞에 세우고 배경을 비워 뒤의 칠이 보이게 한다. 칠이 없어지면 되돌린다 */
function lift(field: TextField, on: boolean) {
    field.style.position = on ? 'relative' : '';
    field.style.zIndex = on ? '1' : '';
    field.style.background = on ? 'transparent' : '';
}

function inside(rects: DOMRectList, x: number, y: number) {
    return [...rects].some(
        (r) => x >= r.left - 1 && x <= r.right + 1 && y >= r.top - 1 && y <= r.bottom + 1,
    );
}

function frameOf(el: HTMLElement | null) {
    // 도구가 틀 밖(등록 화면 탭 줄)에 그려지면 화면의 보고서 틀(하나뿐)을 쓴다
    return (
        el?.closest<HTMLElement>('[data-report]') ??
        document.querySelector<HTMLElement>('[data-report]')
    );
}

function scrollOf(el: HTMLElement | null) {
    return frameOf(el)?.querySelector<HTMLElement>('[data-report-scroll]') ?? null;
}

function paint(texts: TextMark[]) {
    if (!canMarkText) return;
    if (texts.length) CSS.highlights.set(MARKER, new Highlight(...texts.map((t) => t.range)));
    else CSS.highlights.delete(MARKER);
}

function drop(fields: Map<TextField, FieldMarks>, field: TextField) {
    const marks = fields.get(field);
    if (!marks) return;
    marks.layer.remove();
    lift(field, false);
    fields.delete(field);
}

export function ReportTools() {
    const ref = useRef<HTMLDivElement>(null);
    const [tool, setTool] = useState<Tool>(null);
    const order = useRef(0);
    const texts = useRef<TextMark[]>([]);
    const fields = useRef(new Map<TextField, FieldMarks>());

    const frame = () => frameOf(ref.current);
    const scrollBox = () => scrollOf(ref.current);
    const paintTexts = () => paint(texts.current);
    const dropField = (field: TextField) => drop(fields.current, field);

    // 칠한 칸이 있는 동안 화면이 움직여도(스크롤·내용 변화·창 크기) 층이 칸을 따라가게 한다
    useEffect(() => {
        let raf = 0;
        const tick = () => {
            const host = scrollBox();
            if (host) for (const [field, marks] of fields.current) place(field, marks, host);
            raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(raf);
    }, []);

    // 칠하는 동안·지우는 동안 틀에 표시를 달아 커서를 형광펜·지우개 모양으로 바꾼다
    useEffect(() => {
        const root = frame();
        root?.toggleAttribute('data-marking', tool === 'marker');
        root?.toggleAttribute('data-erasing', tool === 'eraser');
        return () => {
            root?.removeAttribute('data-marking');
            root?.removeAttribute('data-erasing');
        };
    }, [tool]);

    useEffect(() => {
        const host = scrollBox();
        if (!tool || !host) return;

        const mark = (e: MouseEvent) => {
            if (isTextField(e.target)) {
                const field = e.target;
                const start = field.selectionStart ?? 0;
                const end = field.selectionEnd ?? 0;
                if (start === end) return;
                let marks = fields.current.get(field);
                if (!marks) {
                    const layer = document.createElement('div');
                    layer.className = report.markLayer;
                    layer.setAttribute('aria-hidden', 'true');
                    layer.append(document.createElement('div'));
                    host.append(layer);
                    marks = { strokes: [], layer };
                    fields.current.set(field, marks);
                    lift(field, true);
                }
                marks.strokes.push({ start, end, order: ++order.current });
                draw(field, marks);
                place(field, marks, host);
                field.setSelectionRange(end, end);
                return;
            }
            const selection = window.getSelection();
            if (!canMarkText || !selection || selection.isCollapsed || !selection.rangeCount)
                return;
            const range = selection.getRangeAt(0);
            if (!host.contains(range.commonAncestorContainer)) return;
            texts.current.push({ range: range.cloneRange(), order: ++order.current });
            paintTexts();
            selection.removeAllRanges();
        };

        // 누른 자리를 덮는 획 가운데 가장 최근 것 하나만 지운다
        const erase = (e: MouseEvent) => {
            const { clientX: x, clientY: y } = e;
            let best: { order: number; remove: () => void } | null = null;
            for (const t of texts.current)
                if (t.order > (best?.order ?? 0) && inside(t.range.getClientRects(), x, y))
                    best = {
                        order: t.order,
                        remove: () => {
                            texts.current = texts.current.filter((o) => o !== t);
                            paintTexts();
                        },
                    };
            for (const [field, marks] of fields.current)
                for (const m of marks.layer.querySelectorAll<HTMLElement>('mark')) {
                    const o = Number(m.dataset.order);
                    if (o > (best?.order ?? 0) && inside(m.getClientRects(), x, y))
                        best = {
                            order: o,
                            remove: () => {
                                marks.strokes = marks.strokes.filter((s) => s.order !== o);
                                if (marks.strokes.length) draw(field, marks);
                                else dropField(field);
                            },
                        };
                }
            best?.remove();
        };

        // 지우개로 누를 때는 칸에 커서가 들어가거나 글자가 골라지지 않게 한다
        const blockSelect = (e: MouseEvent) => e.preventDefault();
        if (tool === 'marker') host.addEventListener('mouseup', mark);
        else {
            host.addEventListener('mousedown', blockSelect);
            host.addEventListener('click', erase);
        }
        return () => {
            host.removeEventListener('mouseup', mark);
            host.removeEventListener('mousedown', blockSelect);
            host.removeEventListener('click', erase);
        };
    }, [tool]);

    // 칠한 칸의 내용을 고치면 그 칸의 칠을 지운다(형광펜을 끈 뒤에도)
    useEffect(() => {
        const host = scrollBox();
        if (!host) return;
        const onInput = (e: Event) => {
            if (isTextField(e.target)) dropField(e.target);
        };
        host.addEventListener('input', onInput);
        return () => host.removeEventListener('input', onInput);
    }, []);

    // 화면을 떠나면 칠한 것도 모두 지운다
    useEffect(
        () => () => {
            texts.current = [];
            paintTexts();
            for (const field of [...fields.current.keys()]) dropField(field);
        },
        [],
    );

    // 형광펜을 켜면(지우는 중 포함) 그 왼쪽에 지우개가 나타난다. 지우개는 형광펜을 켠 동안 칠하기·지우기를 오간다
    const on = tool !== null;

    return (
        <div ref={ref} className={report.barGroup}>
            {on && (
                <button
                    type="button"
                    className={`${report.barIcon} ${report.barIconPlain}`}
                    aria-pressed={tool === 'eraser'}
                    aria-label="지우개"
                    title={
                        tool === 'eraser'
                            ? '지우개 끄기(다시 칠하기)'
                            : '지우개: 칠한 곳을 누르면 그 자리의 가장 최근 칠 하나를 지웁니다'
                    }
                    onClick={() => setTool(tool === 'eraser' ? 'marker' : 'eraser')}
                >
                    <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                    >
                        <path d="M7 21h13" />
                        <path d="M4.6 15.4l9.8-9.8a2 2 0 0 1 2.8 0l2.2 2.2a2 2 0 0 1 0 2.8L11 19H8.2l-3.6-3.6z" />
                        <path d="M9.5 10.5l5 5" />
                    </svg>
                </button>
            )}
            <button
                type="button"
                className={report.barIcon}
                aria-pressed={on}
                aria-label="형광펜"
                title={on ? '형광펜 끄기' : '형광펜: 켜고 글자를 드래그하면 노랗게 칠합니다'}
                onClick={() => setTool(on ? null : 'marker')}
            >
                <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                >
                    <path d="M9 11l-4 4v3h3l4-4" />
                    <path d="M9 11l6-6 4 4-6 6z" />
                    <path d="M4 21h16" />
                </svg>
            </button>
        </div>
    );
}
