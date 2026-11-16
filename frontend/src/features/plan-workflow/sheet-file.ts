/**
 * 장비 목록 파일(XLSX/CSV)을 화면에 보일 표로 읽는다. 첫 시트만 읽고 값은 글자 그대로 둔다
 * (서식·병합·수식 계산은 하지 않는다). 외부 라이브러리 없이 브라우저의 압축 해제를 쓴다.
 */
export type Sheet = { name: string; rows: string[][] };

/** 화면에 담는 상한. 넘는 행·열은 잘라 낸다 */
export const SHEET_MAX_ROWS = 1000;
export const SHEET_MAX_COLS = 50;
export const SHEET_MAX_BYTES = 10 * 1024 * 1024;

export class SheetReadError extends Error {}

export async function readSheetFile(file: File): Promise<Sheet> {
    const name = file.name;
    if (file.size > SHEET_MAX_BYTES) throw new SheetReadError('파일이 너무 큽니다(10MiB 이내).');
    if (/\.csv$/i.test(name)) return { name, rows: trim(parseCsv(await file.text())) };
    if (/\.xlsx$/i.test(name))
        return { name, rows: trim(await parseXlsx(await file.arrayBuffer())) };
    if (/\.xls$/i.test(name))
        throw new SheetReadError(
            '예전 엑셀(.xls)은 읽을 수 없습니다. .xlsx로 저장해 다시 올려주세요.',
        );
    throw new SheetReadError('XLSX 또는 CSV 파일만 올릴 수 있습니다.');
}

/** 빈 끝 행·열을 지우고 상한으로 자른 뒤 모든 행의 칸 수를 맞춘다 */
function trim(rows: string[][]): string[][] {
    const cut = rows.slice(0, SHEET_MAX_ROWS).map((r) => r.slice(0, SHEET_MAX_COLS));
    while (cut.length && cut[cut.length - 1].every((v) => !v.trim())) cut.pop();
    const lastFilled = (r: string[]) => {
        for (let i = r.length - 1; i >= 0; i--) if (r[i]?.trim()) return i;
        return -1;
    };
    const width = Math.max(0, ...cut.map((r) => lastFilled(r) + 1));
    return cut.map((r) => Array.from({ length: width }, (_, i) => r[i] ?? ''));
}

export function parseCsv(text: string): string[][] {
    const src = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
    const rows: string[][] = [];
    let row: string[] = [];
    let cell = '';
    let quoted = false;
    for (let i = 0; i < src.length; i++) {
        const ch = src[i];
        if (quoted) {
            if (ch === '"' && src[i + 1] === '"') {
                cell += '"';
                i++;
            } else if (ch === '"') quoted = false;
            else cell += ch;
        } else if (ch === '"') quoted = true;
        else if (ch === ',') {
            row.push(cell);
            cell = '';
        } else if (ch === '\n' || ch === '\r') {
            if (ch === '\r' && src[i + 1] === '\n') i++;
            row.push(cell);
            rows.push(row);
            row = [];
            cell = '';
        } else cell += ch;
    }
    if (cell || row.length) {
        row.push(cell);
        rows.push(row);
    }
    return rows;
}

// ---- XLSX: zip 안의 workbook·sharedStrings·첫 시트 XML만 읽는다 ----

type ZipEntry = { method: number; offset: number; size: number };

function zipEntries(buf: ArrayBuffer): Map<string, ZipEntry> {
    const view = new DataView(buf);
    let end = -1;
    for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 65557); i--) {
        if (view.getUint32(i, true) === 0x06054b50) {
            end = i;
            break;
        }
    }
    if (end < 0)
        throw new SheetReadError('엑셀 파일을 읽지 못했습니다. 파일이 손상되었는지 확인해주세요.');
    const count = view.getUint16(end + 10, true);
    let p = view.getUint32(end + 16, true);
    const names = new TextDecoder();
    const entries = new Map<string, ZipEntry>();
    for (let n = 0; n < count; n++) {
        if (view.getUint32(p, true) !== 0x02014b50) break;
        const method = view.getUint16(p + 10, true);
        const size = view.getUint32(p + 20, true);
        const nameLen = view.getUint16(p + 28, true);
        const extraLen = view.getUint16(p + 30, true);
        const commentLen = view.getUint16(p + 32, true);
        const local = view.getUint32(p + 42, true);
        const name = names.decode(new Uint8Array(buf, p + 46, nameLen));
        const dataAt =
            local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
        entries.set(name, { method, offset: dataAt, size });
        p += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
}

async function entryText(buf: ArrayBuffer, entries: Map<string, ZipEntry>, name: string) {
    const entry = entries.get(name);
    if (!entry) return null;
    const raw = new Uint8Array(buf, entry.offset, entry.size);
    let bytes: ArrayBuffer;
    if (entry.method === 0) bytes = raw.slice().buffer;
    else if (entry.method === 8)
        bytes = await new Response(
            new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw')),
        ).arrayBuffer();
    else throw new SheetReadError('지원하지 않는 압축 방식의 엑셀 파일입니다.');
    return new TextDecoder().decode(bytes);
}

const attr = (tag: string, key: string) => new RegExp(`\\s${key}="([^"]*)"`).exec(tag)?.[1] ?? null;

function decode(text: string) {
    return text
        .replace(/_x([0-9A-Fa-f]{4})_/g, (_, h: string) => String.fromCharCode(parseInt(h, 16)))
        .replace(/&#x([0-9A-Fa-f]+);/g, (_, h: string) => String.fromCodePoint(parseInt(h, 16)))
        .replace(/&#(\d+);/g, (_, d: string) => String.fromCodePoint(Number(d)))
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&amp;/g, '&');
}

/** 서식 조각(<r>)으로 나뉜 글자를 모은다. 읽는 법 표기(<rPh>)는 뺀다 */
function richText(xml: string) {
    const plain = xml.replace(/<rPh\b[\s\S]*?<\/rPh>/g, '');
    let out = '';
    for (const m of plain.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)) out += decode(m[1]);
    return out;
}

function columnIndex(ref: string) {
    const letters = /^[A-Z]+/.exec(ref)?.[0] ?? '';
    let n = 0;
    for (const ch of letters) n = n * 26 + (ch.charCodeAt(0) - 64);
    return n - 1;
}

function resolveTarget(target: string) {
    if (target.startsWith('/')) return target.slice(1);
    return `xl/${target}`.replace(/[^/]+\/\.\.\//g, '');
}

export async function parseXlsx(buf: ArrayBuffer): Promise<string[][]> {
    const entries = zipEntries(buf);
    const workbook = await entryText(buf, entries, 'xl/workbook.xml');
    if (workbook === null) throw new SheetReadError('엑셀 시트를 찾지 못했습니다.');
    const first = /<sheet\b[^>]*>/.exec(workbook)?.[0];
    const relId = first ? (attr(first, 'r:id') ?? attr(first, 'id')) : null;
    const rels = (await entryText(buf, entries, 'xl/_rels/workbook.xml.rels')) ?? '';
    let sheetPath = 'xl/worksheets/sheet1.xml';
    for (const m of rels.matchAll(/<Relationship\b[^>]*>/g)) {
        const target = attr(m[0], 'Target');
        if (relId && attr(m[0], 'Id') === relId && target) sheetPath = resolveTarget(target);
    }
    const sheet = await entryText(buf, entries, sheetPath);
    if (sheet === null) throw new SheetReadError('엑셀 시트를 찾지 못했습니다.');
    const sharedXml = (await entryText(buf, entries, 'xl/sharedStrings.xml')) ?? '';
    const shared = [...sharedXml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)].map((m) =>
        richText(m[1]),
    );

    const rows: string[][] = [];
    for (const rm of sheet.matchAll(/<row\b([^>]*?)(?:\/>|>([\s\S]*?)<\/row>)/g)) {
        const r = Number(attr(rm[0], 'r') ?? rows.length + 1) - 1;
        if (r >= SHEET_MAX_ROWS) break;
        const row: string[] = [];
        let next = 0;
        for (const cm of (rm[2] ?? '').matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
            const ref = attr(cm[0], 'r');
            const col = ref ? columnIndex(ref) : next;
            next = col + 1;
            if (col >= SHEET_MAX_COLS) continue;
            const body = cm[2] ?? '';
            const type = attr(cm[0], 't');
            const v = /<v>([\s\S]*?)<\/v>/.exec(body)?.[1];
            let value = '';
            if (type === 's') value = shared[Number(v)] ?? '';
            else if (type === 'inlineStr') value = richText(body);
            else if (type === 'b') value = v === '1' ? 'TRUE' : v === '0' ? 'FALSE' : '';
            else if (v !== undefined) value = decode(v);
            row[col] = value;
        }
        while (rows.length < r) rows.push([]);
        rows[r] = Array.from(row, (x) => x ?? '');
    }
    return rows;
}
