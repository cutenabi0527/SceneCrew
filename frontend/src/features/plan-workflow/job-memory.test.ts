import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearJobMemory, jobMemoryKey, readJobMemory, writeJobMemory } from './job-memory';

const scope = {
    eventId: '10000000-0000-4000-8000-000000000002',
    memberId: '10000000-0000-4000-8000-000000000003',
    participationRevision: '1',
};
const key = jobMemoryKey(scope);

describe('계획 작업 기억(#268)', () => {
    // 단위 시험은 Node 환경이라 브라우저 세션 저장소를 흉내 낸다.
    let store: Map<string, string>;
    beforeEach(() => {
        store = new Map();
        vi.stubGlobal('sessionStorage', {
            getItem: (k: string) => store.get(k) ?? null,
            setItem: (k: string, v: string) => store.set(k, v),
            removeItem: (k: string) => store.delete(k),
        });
    });
    afterEach(() => vi.unstubAllGlobals());

    it('참여 회차마다 다른 키에 작업과 접수 당시 revision을 둔다', () => {
        writeJobMemory(key, { jobs: { generate: 'job-1' }, revisions: { 'job-1': '7' } });
        expect(readJobMemory(key)).toEqual({
            jobs: { generate: 'job-1' },
            revisions: { 'job-1': '7' },
        });
        expect(jobMemoryKey({ ...scope, participationRevision: '2' })).not.toBe(key);
        expect(readJobMemory(jobMemoryKey({ ...scope, participationRevision: '2' }))).toEqual({
            jobs: {},
            revisions: {},
        });
    });

    it('형식이 다른 값은 버리고, 작업이 없으면 저장소에서 지운다', () => {
        store.set(
            key,
            JSON.stringify({ jobs: { generate: 3, other: 'x' }, revisions: { a: '-1' } }),
        );
        expect(readJobMemory(key)).toEqual({ jobs: {}, revisions: {} });
        store.set(key, '{not json');
        expect(readJobMemory(key)).toEqual({ jobs: {}, revisions: {} });
        writeJobMemory(key, { jobs: {}, revisions: {} });
        expect(store.has(key)).toBe(false);
        writeJobMemory(key, { jobs: { extract: 'e-1' }, revisions: {} });
        clearJobMemory(key);
        expect(store.has(key)).toBe(false);
    });

    it('저장소를 쓸 수 없어도 오류 없이 빈 기억이다', () => {
        vi.stubGlobal('sessionStorage', {
            getItem: () => {
                throw new Error('blocked');
            },
            setItem: () => {
                throw new Error('blocked');
            },
            removeItem: () => {
                throw new Error('blocked');
            },
        });
        expect(readJobMemory(key)).toEqual({ jobs: {}, revisions: {} });
        expect(() => writeJobMemory(key, { jobs: { generate: 'j' }, revisions: {} })).not.toThrow();
        expect(() => clearJobMemory(key)).not.toThrow();
    });
});
