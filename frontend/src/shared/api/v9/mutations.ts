// 계약 예시를 조금씩 망가뜨린 변형을 만든다. 손으로 짠 검사기와 스키마 검사기의 판정을 비교하는 시험이 쓴다.
// 변형마다 경로와 종류를 남겨 어느 필드에서 판정이 갈렸는지 알 수 있게 한다.

export interface Mutation {
    path: string;
    kind: 'remove' | 'extra' | 'number' | 'string' | 'null' | 'enum' | 'empty-array';
    value: unknown;
}

const clone = <T>(v: T): T => structuredClone(v);

function set(
    root: unknown,
    keys: (string | number)[],
    change: (parent: Record<string, unknown> | unknown[], key: string | number) => void,
) {
    const copy = clone(root);
    let node: unknown = copy;
    for (const k of keys.slice(0, -1)) node = (node as Record<string | number, unknown>)[k];
    change(node as Record<string, unknown>, keys.at(-1)!);
    return copy;
}

/** 깊이 maxDepth까지 객체·배열 첫 원소를 따라가며 변형을 만든다. */
export function mutations(root: unknown, maxDepth = 4): Mutation[] {
    const out: Mutation[] = [];
    const visit = (node: unknown, keys: (string | number)[]) => {
        if (keys.length > maxDepth) return;
        const path = keys.length ? keys.join('.') : '$';
        if (Array.isArray(node)) {
            if (node.length) visit(node[0], [...keys, 0]);
            return;
        }
        if (!node || typeof node !== 'object') return;
        out.push({
            path,
            kind: 'extra',
            value: keys.length
                ? set(root, keys, (p, k) => {
                      (p as Record<string | number, Record<string, unknown>>)[k].unexpectedField =
                          1;
                  })
                : { ...(root as Record<string, unknown>), unexpectedField: 1 },
        });
        for (const [k, v] of Object.entries(node)) {
            const at = [...keys, k];
            const p = at.join('.');
            const replace = (value: unknown) =>
                set(root, at, (parent, key) => {
                    (parent as Record<string | number, unknown>)[key] = value;
                });
            out.push({
                path: p,
                kind: 'remove',
                value: set(root, at, (parent, key) => {
                    delete (parent as Record<string | number, unknown>)[key];
                }),
            });
            if (v !== null) out.push({ path: p, kind: 'null', value: replace(null) });
            if (typeof v === 'string') {
                out.push({ path: p, kind: 'number', value: replace(1) });
                out.push({ path: p, kind: 'enum', value: replace('UNKNOWN_CONTRACT_VALUE') });
            }
            if (typeof v === 'number' || typeof v === 'boolean')
                out.push({ path: p, kind: 'string', value: replace(String(v)) });
            if (Array.isArray(v) && v.length)
                out.push({ path: p, kind: 'empty-array', value: replace([]) });
            visit(v, at);
        }
    };
    visit(root, []);
    return out;
}
