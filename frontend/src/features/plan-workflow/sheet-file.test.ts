import { describe, expect, it } from 'vitest';
import { parseCsv, parseXlsx, readSheetFile, SheetReadError } from './sheet-file';

async function deflate(bytes: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
    return new Uint8Array(
        await new Response(
            new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw')),
        ).arrayBuffer(),
    );
}

/** 시험용 최소 zip(CRC는 읽는 쪽이 보지 않아 0으로 둔다) */
async function zip(files: Record<string, string>, compress: boolean) {
    const enc = new TextEncoder();
    const locals: Uint8Array<ArrayBuffer>[] = [];
    const centrals: Uint8Array<ArrayBuffer>[] = [];
    let offset = 0;
    for (const [name, text] of Object.entries(files)) {
        const nameBytes = enc.encode(name);
        const raw = enc.encode(text);
        const data = compress ? await deflate(raw) : raw;
        const local = new Uint8Array(30 + nameBytes.length + data.length);
        const lv = new DataView(local.buffer);
        lv.setUint32(0, 0x04034b50, true);
        lv.setUint16(8, compress ? 8 : 0, true);
        lv.setUint32(18, data.length, true);
        lv.setUint32(22, raw.length, true);
        lv.setUint16(26, nameBytes.length, true);
        local.set(nameBytes, 30);
        local.set(data, 30 + nameBytes.length);
        const central = new Uint8Array(46 + nameBytes.length);
        const cv = new DataView(central.buffer);
        cv.setUint32(0, 0x02014b50, true);
        cv.setUint16(10, compress ? 8 : 0, true);
        cv.setUint32(20, data.length, true);
        cv.setUint32(24, raw.length, true);
        cv.setUint16(28, nameBytes.length, true);
        cv.setUint32(42, offset, true);
        central.set(nameBytes, 46);
        locals.push(local);
        centrals.push(central);
        offset += local.length;
    }
    const cdSize = centrals.reduce((n, c) => n + c.length, 0);
    const end = new Uint8Array(22);
    const ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(8, centrals.length, true);
    ev.setUint16(10, centrals.length, true);
    ev.setUint32(12, cdSize, true);
    ev.setUint32(16, offset, true);
    return new Blob([...locals, ...centrals, end]).arrayBuffer();
}

const book = {
    'xl/workbook.xml':
        '<workbook><sheets><sheet name="장비" sheetId="1" r:id="rId1"/></sheets></workbook>',
    'xl/_rels/workbook.xml.rels':
        '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>',
    'xl/sharedStrings.xml':
        '<sst><si><t>이름</t></si><si><r><t>수</t></r><r><t>량</t></r><rPh><t>x</t></rPh></si><si><t>무대 &amp; 조명</t></si></sst>',
    'xl/worksheets/sheet1.xml':
        '<worksheet><sheetData>' +
        '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>' +
        '<row r="3"><c r="A3" t="s"><v>2</v></c><c r="B3" t="inlineStr"><is><t>메모</t></is></c><c r="C3"><v>12</v></c></row>' +
        '</sheetData></worksheet>',
};

describe('장비 목록 파일 읽기', () => {
    it.each([false, true])('XLSX 첫 시트를 칸 자리 그대로 읽는다(압축 %s)', async (compress) => {
        const rows = await parseXlsx(await zip(book, compress));
        expect(rows).toEqual([['이름', '', '수량'], [], ['무대 & 조명', '메모', '12']]);
    });

    it('CSV의 따옴표·줄바꿈을 읽고 빈 끝 행·열을 지운다', async () => {
        expect(parseCsv('a,"b,""c"""\r\n"줄\n바꿈",x\n')).toEqual([
            ['a', 'b,"c"'],
            ['줄\n바꿈', 'x'],
        ]);
        const sheet = await readSheetFile(new File(['﻿이름,수량,\n조명,2,\n,,\n'], 'a.csv'));
        expect(sheet.rows).toEqual([
            ['이름', '수량'],
            ['조명', '2'],
        ]);
    });

    it('예전 .xls와 다른 형식은 안내와 함께 거절한다', async () => {
        await expect(readSheetFile(new File(['x'], 'a.xls'))).rejects.toThrow(SheetReadError);
        await expect(readSheetFile(new File(['x'], 'a.png'))).rejects.toThrow(/XLSX 또는 CSV/);
    });
});
