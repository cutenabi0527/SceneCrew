import { useState } from 'react';
import { useData, useEvent } from '../../domain/hooks';
import { newId, useDispatch } from '../../domain/store-context';
import { Note } from '../../shared/ui/atoms';
import { MAX_BYTES, parseEquipmentList, type ImportRow } from './equipment-list';

/**
 * D01 장비 자료 가져오기(보조 패널): 파일을 읽어 후보 표를 보이고, 고른 행만 등록한다.
 * 기술 상태·호환·배선은 자료로 확정하지 않고 담당자 확인 전 미확인으로 시작한다.
 */
export function EquipmentImport() {
    const d = useData();
    const ev = useEvent();
    const dispatch = useDispatch();
    const [file, setFile] = useState<string | null>(null);
    const [rows, setRows] = useState<ImportRow[]>([]);
    const [picked, setPicked] = useState<Set<number>>(new Set());
    const [message, setMessage] = useState('');
    const editable = ev.state !== 'ENDED';
    const existing = new Set(d.equipment.map((a) => a.name));

    const read = async (f: File) => {
        setMessage('');
        if (!/\.(csv|txt|md)$/i.test(f.name)) {
            setMessage('CSV/TXT/MD 파일만 읽을 수 있습니다.');
            return;
        }
        if (f.size > MAX_BYTES) {
            setMessage('파일이 너무 큽니다(256KB 이내).');
            return;
        }
        const parsed = parseEquipmentList(await f.text());
        setFile(f.name);
        setRows(parsed);
        setPicked(
            new Set(parsed.flatMap((r, i) => (!r.error && !existing.has(r.name) ? [i] : []))),
        );
        if (!parsed.length) setMessage('읽을 수 있는 장비 행이 없습니다.');
    };

    return (
        <div className="stack s6">
            <label className="field">
                <span className="lbl">장비 자료 가져오기 (CSV/TXT/MD, 열: 이름, 종류, 위치)</span>
                <input
                    type="file"
                    accept=".csv,.txt,.md,text/csv,text/plain,text/markdown"
                    disabled={!editable}
                    onChange={(e) => {
                        const f = e.target.files?.[0];
                        if (f) void read(f);
                        e.target.value = '';
                    }}
                />
            </label>
            {message && <Note kind="warn">{message}</Note>}
            {rows.length > 0 && (
                <>
                    <table className="table">
                        <thead>
                            <tr>
                                <th>등록</th>
                                <th>이름</th>
                                <th>종류</th>
                                <th>위치</th>
                                <th>확인</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map((r, i) => {
                                const dup = existing.has(r.name);
                                return (
                                    <tr key={`${i}-${r.name}`}>
                                        <td>
                                            <input
                                                type="checkbox"
                                                aria-label={`${r.name || i + 1}행 등록`}
                                                checked={picked.has(i)}
                                                disabled={!!r.error}
                                                onChange={() =>
                                                    setPicked((s) => {
                                                        const next = new Set(s);
                                                        if (next.has(i)) next.delete(i);
                                                        else next.add(i);
                                                        return next;
                                                    })
                                                }
                                            />
                                        </td>
                                        <td>{r.name || '-'}</td>
                                        <td>{r.kind || '-'}</td>
                                        <td>{r.place}</td>
                                        <td className="xs">
                                            {r.error ?? (dup ? '같은 이름 있음' : '등록 가능')}
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                    <div className="row">
                        <span className="xs muted grow">
                            {file} / 기술 상태는 미확인으로 등록합니다
                        </span>
                        <button
                            type="button"
                            className="btn sm ghost"
                            onClick={() => {
                                setRows([]);
                                setFile(null);
                            }}
                        >
                            취소
                        </button>
                        <button
                            type="button"
                            className="btn sm primary"
                            disabled={!picked.size || !editable}
                            onClick={() => {
                                dispatch({
                                    type: 'importEquipment',
                                    source: file ?? '자료',
                                    items: [...picked].map((i) => ({
                                        id: newId('EQ'),
                                        name: rows[i].name,
                                        kind: rows[i].kind,
                                        place: rows[i].place,
                                    })),
                                });
                                setMessage('');
                                setRows([]);
                                setFile(null);
                            }}
                        >
                            고른 {picked.size}건 등록
                        </button>
                    </div>
                </>
            )}
        </div>
    );
}
