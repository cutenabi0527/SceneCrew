import { describe, expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { ScopeManager } from '../../shared/scope/scope-manager';
import { ScopeDisposedError } from '../../shared/scope/scope';
import { account, credentials, eventPage, participation, revision } from './contract';
import { IdentitySession } from './session';
import type { RefreshStore, StoredRefresh } from './refresh-store';
import { v9Examples } from '../../shared/api/v9/testing';

const id = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const eventId = '00000000-0000-4000-8000-000000000003';
const memberId = '00000000-0000-4000-8000-000000000004';
const groupId = '00000000-0000-4000-8000-000000000005';
const user = {
    id,
    displayName: '확인 계정',
    primaryEmail: 'test@example.invalid',
    primaryEmailVerified: true,
    status: 'ACTIVE',
};
const expiry = {
    accessExpiresAt: '2026-11-03T10:00:00Z',
    refreshExpiresAt: '2026-11-04T10:00:00Z',
};
const login = { account: user, channel: 'WEB', ...expiry };
const androidLogin = {
    ...login,
    channel: 'ANDROID',
    accessToken: 'test-access',
    refreshToken: 'test-refresh',
};
const p = {
    memberId,
    participationRevision: '1',
    active: true,
    eventRole: 'WORKER',
    workGroupId: groupId,
};
const ev = {
    id: eventId,
    name: '검증 공연',
    state: 'RECRUITING',
    revision: '1',
    myParticipation: p,
};
// 단독 행사 조회(GET /events/{e})는 v9 Event 전체다. 계약 예시에서 식별자·참여만 바꿔 쓴다.
const contractEvent = v9Examples.find((e) => e.op === 'GET /events/{e}' && e.code === '200')!
    .value as Record<string, unknown>;
const fullEv = { ...contractEvent, ...ev };
const options = {
    eventId,
    revision: '1',
    groups: [{ workGroupId: groupId, name: '음향', taskSummary: '리허설', selectable: true }],
    myParticipation: null,
};
const join = { participation: p, eventRevision: '2', initialAssignment: 'PENDING' };
const body = {
    workGroupId: groupId,
    available: true,
    isReserve: false,
    expectedEventRevision: '1',
};
const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const error = (status: number, code = 'UNAUTHENTICATED') =>
    json({ code, message: '시험 오류', requestId: 'test' }, status);
function deferred<T>() {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => {
        resolve = r;
    });
    return { promise, resolve };
}
function setup(channel: 'WEB' | 'ANDROID' = 'WEB', store?: RefreshStore) {
    const fetcher = vi.fn<typeof fetch>();
    const scopes = new ScopeManager(new QueryClient());
    const session = new IdentitySession(channel, '/api/v1', scopes, fetcher, store);
    const authenticate = async (
        value: typeof login | typeof androidLogin = channel === 'WEB' ? login : androidLogin,
    ) => {
        fetcher.mockResolvedValueOnce(json(value));
        await session.authenticate('/auth/login', {
            email: user.primaryEmail,
            password: 'TestPass8!',
        });
    };
    return { fetcher, scopes, session, authenticate };
}
describe('인증/참여 공개 계약', () => {
    it('WEB 토큰 본문과 다른 채널, 비활성/그룹 없는 팀원을 거절한다', () => {
        expect(() => credentials(androidLogin, 'WEB', 'LoginResult')).toThrow();
        expect(() =>
            credentials({ ...login, accessToken: 'leak' }, 'WEB', 'LoginResult'),
        ).toThrow();
        expect(() => participation({ ...p, workGroupId: null })).toThrow();
        expect(() => account({ ...user, primaryEmail: null })).toThrow();
    });
    it.each([1, '01', '-1', '9223372036854775808'])('회차 %s를 거절한다', (v) => {
        expect(() => revision(v)).toThrow();
    });
    it('cursor 종료는 JSON null만 사용한다', () => {
        expect(eventPage({ items: [], nextCursor: null }).nextCursor).toBeNull();
        expect(() => eventPage({ items: [], nextCursor: 'null' })).toThrow();
    });
    it('WEB 기존 Cookie 세션은 Me 결과로만 복원한다', async () => {
        const { fetcher, scopes, session } = setup();
        fetcher.mockResolvedValueOnce(json(user));
        await session.bootstrap();
        expect(scopes.currentAccount?.accountId).toBe(id);
        expect(fetcher).toHaveBeenCalledTimes(1);
    });
    it('Android 재시작은 토큰 없이 로그인 상태를 만들지 않는다', async () => {
        const { fetcher, session } = setup('ANDROID');
        await session.bootstrap();
        expect(session.snapshot()).toEqual({ account: null, ready: true });
        expect(fetcher).not.toHaveBeenCalled();
    });
    it('WEB 로그인은 Cookie/CSRF, Android 보호 조회는 Bearer를 쓴다', async () => {
        const web = setup();
        await web.authenticate();
        const init = web.fetcher.mock.calls[0][1]!;
        expect(init.credentials).toBe('same-origin');
        expect(new Headers(init.headers).get('X-SceneCrew-CSRF')).toBe('1');
        const app = setup('ANDROID');
        await app.authenticate();
        app.fetcher.mockResolvedValueOnce(json({ items: [], nextCursor: null }));
        await app.session.events();
        expect(app.fetcher.mock.calls[1][1]?.credentials).toBe('omit');
        expect(new Headers(app.fetcher.mock.calls[1][1]?.headers).get('Authorization')).toBe(
            'Bearer test-access',
        );
    });
    it('실패한 로그인은 기존 계정/회차를 유지하고 Refresh하지 않는다', async () => {
        const { session, scopes, fetcher, authenticate } = setup();
        await authenticate();
        scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        const scope = scopes.participation(eventId);
        fetcher.mockResolvedValueOnce(error(401, 'INVALID_CREDENTIALS'));
        await expect(session.authenticate('/auth/login', {})).rejects.toThrow();
        expect(scopes.participation(eventId)).toBe(scope);
        expect(fetcher).toHaveBeenCalledTimes(2);
    });
    it('보호 조회의 동시 401은 하나의 회전과 22자 시도 식별자를 사용한다', async () => {
        const { session, fetcher, authenticate } = setup('ANDROID');
        await authenticate();
        let requests = 0;
        fetcher.mockImplementation(async (url) =>
            String(url).endsWith('/auth/refresh')
                ? json({ ...androidLogin, accessToken: 'rotated' })
                : ++requests <= 2
                  ? error(401)
                  : json({ items: [], nextCursor: null }),
        );
        await Promise.all([session.events(), session.events()]);
        const calls = fetcher.mock.calls.filter(([url]) => String(url).endsWith('/auth/refresh'));
        expect(calls).toHaveLength(1);
        const init = calls[0][1]!;
        expect(new Headers(init.headers).get('X-SceneCrew-Refresh-Attempt')).toMatch(
            /^[A-Za-z0-9_-]{22}$/,
        );
        expect(new Headers(init.headers).has('Authorization')).toBe(false);
        expect(JSON.parse(String(init.body))).toEqual({ refreshToken: 'test-refresh' });
    });
    it('Refresh 응답 유실은 재전송 없이 세션과 회차를 폐기한다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        fetcher.mockResolvedValueOnce(error(401)).mockRejectedValueOnce(new TypeError('lost'));
        await expect(session.events()).rejects.toThrow();
        expect(session.snapshot().account).toBeNull();
        expect(scopes.currentAccount).toBeNull();
        expect(fetcher).toHaveBeenCalledTimes(3);
        expect(await session.gate.recover()).toBe('login-required');
        expect(fetcher).toHaveBeenCalledTimes(3);
    });
    it('계정 전환 뒤 옛 조회 응답을 전달하지 않는다', async () => {
        const { session, fetcher, authenticate } = setup();
        await authenticate();
        const wait = deferred<Response>();
        fetcher.mockReturnValueOnce(wait.promise);
        const query = session.events();
        const checked = expect(query).rejects.toBeInstanceOf(ScopeDisposedError);
        await authenticate({ ...login, account: { ...user, id: other } });
        wait.resolve(json({ items: [ev], nextCursor: null }));
        await checked;
        expect(session.snapshot().account?.id).toBe(other);
    });
    it('초대 재열기는 조회만 수행하고 실제 참여 회차를 만든다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        fetcher.mockResolvedValueOnce(json({ ...options, myParticipation: p }));
        await session.options('token');
        expect(scopes.participation(eventId)?.participationRevision).toBe('1');
        expect(
            fetcher.mock.calls.every(
                ([, init]) => init?.method !== 'POST' || String(init.body).includes('password'),
            ),
        ).toBe(true);
    });
    it('참여 전 초대 조회가 겹쳐도 서로의 결과를 버리지 않는다(#353)', async () => {
        const { session, fetcher, authenticate } = setup();
        await authenticate();
        fetcher.mockImplementation(async () => json({ ...options, myParticipation: null }));
        const [a, b] = await Promise.all([session.options('token'), session.options('token')]);
        expect(a.myParticipation).toBeNull();
        expect(b.myParticipation).toBeNull();
    });
    it('Join 응답 유실 뒤 같은 본문/같은 키만 명시 재전송한다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        fetcher.mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce(json(join));
        expect((await session.join('token', eventId, body)).status).toBe('failed');
        expect(scopes.participation(eventId)).toBeNull();
        await session.join('token', eventId, { ...body, isReserve: true });
        const a = fetcher.mock.calls[1][1]!,
            b = fetcher.mock.calls[2][1]!;
        expect(new Headers(a.headers).get('Idempotency-Key')).toBe(
            new Headers(b.headers).get('Idempotency-Key'),
        );
        expect(a.body).toBe(b.body);
        expect(scopes.participation(eventId)?.memberId).toBe(memberId);
    });
    it('Join 결과 불명/만료는 상태 조회만 허용한다', async () => {
        const { session, fetcher, authenticate } = setup();
        await authenticate();
        fetcher.mockResolvedValueOnce(error(409, 'IDEMPOTENCY_OUTCOME_UNKNOWN'));
        expect((await session.join('token', eventId, body)).status).toBe('failed');
        expect((await session.join('token', eventId, body)).status).toBe('reconcile');
        expect(fetcher).toHaveBeenCalledTimes(2);
        fetcher.mockResolvedValueOnce(json({ ...options, myParticipation: p }));
        await session.options('token');
        expect(session.pending('join:token')).toBe(false);
    });
    it('이미 참여한 응답은 요청한 그룹으로 덮어쓰지 않는다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        fetcher.mockResolvedValueOnce(
            json({ ...join, participation: { ...p, workGroupId: other } }),
        );
        await session.join('token', eventId, body);
        expect(scopes.participation(eventId)?.memberId).toBe(memberId);
    });
    it('이탈 성공/재참여 때 옛 범위를 닫고 새 회차를 만든다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        const old = scopes.participation(eventId)!;
        fetcher.mockResolvedValueOnce(
            json({ participation: { ...p, active: false }, eventRevision: '3' }),
        );
        await session.leave(eventId);
        expect(old.isCurrent()).toBe(false);
        expect(scopes.participation(eventId)).toBeNull();
        fetcher.mockResolvedValueOnce(
            json({ ...join, participation: { ...p, participationRevision: '2' } }),
        );
        await session.join('token', eventId, body);
        expect(scopes.participation(eventId)?.participationRevision).toBe('2');
    });
    it('이탈 전에 시작한 늦은 행사 조회가 참여를 되살리지 않는다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        const wait = deferred<Response>();
        fetcher.mockReturnValueOnce(wait.promise);
        const query = session.event(eventId);
        const checked = expect(query).rejects.toBeInstanceOf(ScopeDisposedError);
        fetcher.mockResolvedValueOnce(
            json({ participation: { ...p, active: false }, eventRevision: '2' }),
        );
        await session.leave(eventId);
        wait.resolve(json(fullEv));
        await checked;
        expect(scopes.participation(eventId)).toBeNull();
    });
    it('겹친 행사 조회가 같은 참여를 돌려주면 늦은 조회도 성공한다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        const first = deferred<Response>();
        const second = deferred<Response>();
        fetcher.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
        const a = session.event(eventId);
        const b = session.event(eventId);
        first.resolve(json(fullEv));
        await expect(a).resolves.toMatchObject({ id: eventId });
        second.resolve(json(fullEv));
        await expect(b).resolves.toMatchObject({ id: eventId });
        expect(scopes.participation(eventId)?.participationRevision).toBe('1');
    });
    it('겹친 조회의 참여 회차가 다르면 늦은 조회는 적용하지 않는다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        const first = deferred<Response>();
        const second = deferred<Response>();
        fetcher.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
        const a = session.event(eventId);
        const b = session.event(eventId);
        first.resolve(json({ ...fullEv, myParticipation: { ...p, participationRevision: '2' } }));
        await a;
        second.resolve(json(fullEv));
        await expect(b).rejects.toBeInstanceOf(ScopeDisposedError);
        expect(scopes.participation(eventId)?.participationRevision).toBe('2');
    });
    it('현재 행사 403은 해당 참여 범위를 정리한다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        fetcher.mockResolvedValueOnce(error(403));
        await expect(session.event(eventId)).rejects.toThrow();
        expect(scopes.participation(eventId)).toBeNull();
        expect(scopes.currentAccount).not.toBeNull();
    });
    it('가용 명령은 401이어도 자동 회전/재전송하지 않는다', async () => {
        const { session, fetcher, scopes, authenticate } = setup();
        await authenticate();
        scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        fetcher.mockResolvedValueOnce(error(401));
        expect((await session.availability(eventId, memberId, true, '1')).status).toBe('failed');
        expect(fetcher).toHaveBeenCalledTimes(2);
        expect(new Headers(fetcher.mock.calls[1][1]?.headers).has('Idempotency-Key')).toBe(false);
    });
    it('가입/복구202는 세션/Job/멱등 키를 만들지 않는다', async () => {
        const { session, fetcher, scopes } = setup();
        fetcher
            .mockResolvedValueOnce(json({ requestId: id, expiresAt: expiry.accessExpiresAt }, 202))
            .mockResolvedValueOnce(json({ message: 'accepted' }, 202));
        await session.receipts.register({});
        await session.receipts.requestPasswordReset({});
        expect(scopes.currentAccount).toBeNull();
        expect(
            fetcher.mock.calls.every(
                ([, init]) => !new Headers(init?.headers).has('Idempotency-Key'),
            ),
        ).toBe(true);
    });
    it('로그아웃 응답 유실도 로컬 범위는 닫고 서버 미확인을 안내한다', async () => {
        const { session, fetcher, scopes, authenticate } = setup('ANDROID');
        await authenticate();
        fetcher.mockRejectedValueOnce(new TypeError('lost'));
        await expect(session.logout()).rejects.toThrow();
        expect(scopes.currentAccount).toBeNull();
        expect(session.snapshot().notice).toContain('확인하지 못했습니다');
        const init = fetcher.mock.calls[1][1]!;
        expect(new Headers(init.headers).get('X-SceneCrew-Logout-Attempt')).toMatch(
            /^[A-Za-z0-9_-]{22}$/,
        );
        expect(new Headers(init.headers).has('Authorization')).toBe(false);
        expect(JSON.parse(String(init.body))).toEqual({ refreshToken: 'test-refresh' });
    });
});

// 정식 칸(value)과 로그인 임시 칸(pending). 로그인은 stage 뒤 commit으로 정식 칸에 옮긴다(#388).
function vault(initial: StoredRefresh | null = null) {
    let value = initial;
    let pending: StoredRefresh | null = null;
    return {
        get value() {
            return value;
        },
        get pending() {
            return pending;
        },
        take: vi.fn(async () => {
            pending = null;
            const next = value;
            value = null;
            return next;
        }),
        save: vi.fn(async (next: StoredRefresh) => {
            value = { ...next };
        }),
        stage: vi.fn(async (next: StoredRefresh) => {
            pending = { ...next };
        }),
        commit: vi.fn(async () => {
            if (!pending) throw new Error('No staged credential');
            value = pending;
            pending = null;
        }),
        discard: vi.fn(async () => {
            pending = null;
        }),
        clear: vi.fn(async () => {
            value = null;
            pending = null;
        }),
    };
}

describe('공연 만들기(#286)', () => {
    it('요청 키를 붙여 만들고, 만든 사람의 총괄 참여를 현재 범위로 연다', async () => {
        const { fetcher, scopes, session, authenticate } = setup();
        await authenticate();
        const chief = { ...p, eventRole: 'CHIEF', workGroupId: null };
        fetcher.mockResolvedValueOnce(
            json({ ...fullEv, state: 'DRAFT', revision: '1', myParticipation: chief }, 201),
        );
        const result = await session.createEvent('새 공연', '');
        expect(result.status).toBe('succeeded');
        const [path, init] = fetcher.mock.calls.at(-1)!;
        expect(path).toBe('/api/v1/events');
        expect(new Headers(init!.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
        expect(JSON.parse(String(init!.body))).toEqual({ name: '새 공연', description: '' });
        expect(scopes.participation(eventId)?.memberId).toBe(memberId);
    });
    it('만든 사람이 총괄이 아니면 계약 위반이다', async () => {
        const { fetcher, session, authenticate } = setup();
        await authenticate();
        fetcher.mockResolvedValueOnce(json({ ...fullEv, state: 'DRAFT', myParticipation: p }, 201));
        const result = await session.createEvent('새 공연', '');
        expect(result.status).toBe('failed');
    });
});
describe('탈퇴/동의 이력(#334/#229)', () => {
    it('동의 이력은 버전/출처/바뀐 값만 받고 형식이 다르면 거절한다', async () => {
        const { fetcher, session, authenticate } = setup();
        await authenticate();
        const record = {
            consents: { service: true, voice: false, push: true, externalAi: true },
            version: '2026-11-06',
            history: [
                {
                    at: '2026-11-06T01:00:00Z',
                    version: '2026-11-06',
                    source: 'SIGNUP',
                    values: { service: true, voice: true, push: true, externalAi: true },
                },
                {
                    at: '2026-11-06T02:00:00Z',
                    version: '2026-11-06',
                    source: 'CHANGE',
                    values: { voice: false },
                },
            ],
        };
        fetcher.mockResolvedValueOnce(json(record));
        const got = await session.consentRecord();
        expect(got.history[1]).toEqual(record.history[1]);
        expect(fetcher.mock.calls.at(-1)![0]).toBe('/api/v1/me/consents');
        fetcher.mockResolvedValueOnce(
            json({ ...record, history: [{ ...record.history[0], source: 'OTHER' }] }),
        );
        await expect(session.consentRecord()).rejects.toThrow();
        fetcher.mockResolvedValueOnce(json({ ...record, extra: 1 }));
        await expect(session.consentRecord()).rejects.toThrow();
        fetcher.mockResolvedValueOnce(json({ ...record, version: null, history: [] }));
        expect((await session.consentRecord()).version).toBeNull();
    });
    it('처리 상태는 접수 번호 형식을 먼저 검사하고 같은 번호의 응답만 받는다', async () => {
        const { fetcher, session } = setup();
        await expect(session.deletionStatus('DEL-1-x')).rejects.toThrow(TypeError);
        expect(fetcher).not.toHaveBeenCalled();
        const receiptNo = 'DEL-20261106-1A2B3C4D';
        fetcher.mockResolvedValueOnce(
            json({ receiptNo, status: 'PROCESSING', deletionDueAt: '2026-11-07T01:00:00Z' }),
        );
        expect((await session.deletionStatus(receiptNo)).status).toBe('PROCESSING');
        expect(fetcher.mock.calls[0][0]).toBe('/api/v1/deletion-requests/' + receiptNo);
        fetcher.mockResolvedValueOnce(
            json({ receiptNo: 'DEL-20261106-FFFFFFFF', status: 'COMPLETED', deletionDueAt: 'x' }),
        );
        await expect(session.deletionStatus(receiptNo)).rejects.toThrow();
        fetcher.mockResolvedValueOnce(error(404, 'NOT_FOUND'));
        await expect(session.deletionStatus(receiptNo)).rejects.toThrow();
    });
    it('탈퇴 성공은 접수 결과를 돌려주고 로컬 상태는 접수 화면이 지운다', async () => {
        const { fetcher, session, authenticate } = setup();
        await authenticate();
        fetcher.mockResolvedValueOnce(
            json({
                status: 'DISABLED',
                deactivatedAt: '2026-11-06T01:00:00Z',
                receiptNo: 'DEL-20261106-1A2B3C4D',
                deletionDueAt: '2026-11-07T01:00:00Z',
            }),
        );
        const receipt = await session.deactivate('TestPass8!');
        expect(receipt.receiptNo).toBe('DEL-20261106-1A2B3C4D');
        const init = fetcher.mock.calls.at(-1)![1]!;
        expect(new Headers(init.headers).get('X-SceneCrew-CSRF')).toBe('1');
        expect(session.snapshot().account).not.toBeNull();
        await session.forgetAfterDeactivation();
        expect(session.snapshot().account).toBeNull();
    });
});
describe('Android 영속 Refresh와 세션 수명', () => {
    it('로그인 성공은 Refresh와 계정 식별자만 저장하고 Access는 메모리에 둔다', async () => {
        const store = vault();
        const { session, authenticate } = setup('ANDROID', store);
        await authenticate();
        expect(store.value).toEqual({ accountId: id, refreshToken: 'test-refresh' });
        expect(session.liveAccessToken()).toBe('test-access');
        expect(JSON.stringify(store.value)).not.toContain('test-access');
    });
    it('재시작은 저장값을 소비하고 회전한 Access로 Me를 확인한 뒤에만 계정을 연다', async () => {
        const store = vault({ accountId: id, refreshToken: 'saved' });
        const { session, scopes, fetcher } = setup('ANDROID', store);
        const response = deferred<Response>();
        fetcher.mockImplementation(async (url, init) => {
            if (String(url).endsWith('/auth/refresh')) {
                expect(store.value).toBeNull();
                expect(scopes.currentAccount).toBeNull();
                expect(JSON.parse(String(init?.body))).toEqual({ refreshToken: 'saved' });
                expect(new Headers(init?.headers).has('Authorization')).toBe(false);
                return json({ ...androidLogin, accessToken: 'fresh', refreshToken: 'rotated' });
            }
            expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer fresh');
            return response.promise;
        });
        const boot = session.bootstrap();
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
        expect(session.snapshot()).toEqual({ account: null, ready: false });
        expect(store.value).toEqual({ accountId: id, refreshToken: 'rotated' });
        response.resolve(json(user));
        await boot;
        expect(scopes.currentAccount?.accountId).toBe(id);
        expect(session.snapshot().account?.id).toBe(id);
        expect(scopes.participation(eventId)).toBeNull();
        await session.bootstrap();
        expect(fetcher).toHaveBeenCalledTimes(2);
    });
    it('Refresh 응답 유실 뒤 다음 실행에서 옛 토큰을 재전송하지 않는다', async () => {
        const store = vault({ accountId: id, refreshToken: 'saved' });
        const first = setup('ANDROID', store);
        first.fetcher.mockRejectedValueOnce(new TypeError('lost'));
        await first.session.bootstrap();
        expect(store.value).toBeNull();
        expect(first.session.snapshot().account).toBeNull();
        const next = setup('ANDROID', store);
        await next.session.bootstrap();
        expect(next.fetcher).not.toHaveBeenCalled();
    });
    it('회전 중 프로세스가 끝난 것으로 보면 다음 실행에는 자격이 없다', async () => {
        const store = vault();
        const first = setup('ANDROID', store);
        await first.authenticate();
        const response = deferred<Response>();
        first.fetcher.mockReturnValueOnce(response.promise);
        const rotation = first.session.gate.recover();
        await vi.waitFor(() => expect(first.fetcher).toHaveBeenCalledTimes(2));
        expect(store.value).toBeNull();
        const restarted = setup('ANDROID', store);
        await restarted.session.bootstrap();
        expect(restarted.fetcher).not.toHaveBeenCalled();
        response.resolve(error(401));
        await rotation;
    });
    it.each([401, 503])(
        '복원 Me %s는 재회전 없이 자격을 지우고 로그인으로 간다',
        async (status) => {
            const store = vault({ accountId: id, refreshToken: 'saved' });
            const { session, fetcher } = setup('ANDROID', store);
            fetcher.mockResolvedValueOnce(json(androidLogin)).mockResolvedValueOnce(error(status));
            await session.bootstrap();
            expect(session.snapshot()).toEqual({ account: null, ready: true });
            expect(store.value).toBeNull();
            expect(session.liveAccessToken()).toBeNull();
            expect(fetcher).toHaveBeenCalledTimes(2);
        },
    );
    it('저장한 계정과 다른 Me는 복원하지 않는다', async () => {
        const store = vault({ accountId: id, refreshToken: 'saved' });
        const { session, scopes, fetcher } = setup('ANDROID', store);
        fetcher
            .mockResolvedValueOnce(json(androidLogin))
            .mockResolvedValueOnce(json({ ...user, id: other }));
        await session.bootstrap();
        expect(scopes.currentAccount).toBeNull();
        expect(store.value).toBeNull();
    });
    it('손상된 저장소 읽기는 서버 요청과 로그인 상태를 만들지 않는다', async () => {
        const store = vault();
        store.take.mockRejectedValueOnce(new Error('broken'));
        const { session, fetcher } = setup('ANDROID', store);
        await session.bootstrap();
        expect(fetcher).not.toHaveBeenCalled();
        expect(session.snapshot()).toEqual({ account: null, ready: true });
        expect(store.clear).toHaveBeenCalled();
    });
    it('로그인 임시 저장 실패는 새 계정을 표시하지 않고 기존 세션·정식 자격을 유지한다(#388)', async () => {
        const store = vault();
        const { session, scopes, authenticate } = setup('ANDROID', store);
        await authenticate();
        store.stage.mockRejectedValueOnce(new Error('write failed'));
        await expect(
            authenticate({ ...androidLogin, account: { ...user, id: other } }),
        ).rejects.toThrow('저장');
        expect(store.value).toEqual({ accountId: id, refreshToken: 'test-refresh' });
        expect(store.pending).toBeNull();
        expect(scopes.currentAccount?.accountId).toBe(id);
        expect(session.liveAccessToken()).toBe('test-access');
    });
    it('로그인 정식 저장(확정) 실패는 결과를 알 수 없으므로 세션과 기기 자격을 모두 정리한다', async () => {
        const store = vault();
        const { session, scopes, authenticate } = setup('ANDROID', store);
        await authenticate();
        store.commit.mockRejectedValueOnce(new Error('write failed'));
        await expect(
            authenticate({ ...androidLogin, account: { ...user, id: other } }),
        ).rejects.toThrow('저장');
        expect(store.value).toBeNull();
        expect(scopes.currentAccount).toBeNull();
        expect(session.liveAccessToken()).toBeNull();
    });
    it('회전 전 영속 자격 삭제 실패는 서버에 소비 요청을 보내지 않는다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        store.clear.mockRejectedValueOnce(new Error('clear failed'));
        expect(await session.gate.recover()).toBe('login-required');
        expect(fetcher).toHaveBeenCalledTimes(1);
        expect(store.value).toBeNull();
        expect(session.liveAccessToken()).toBeNull();
    });
    it('실행 중 회전의 새 Refresh를 저장한 뒤에만 새 Access를 사용한다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        fetcher.mockResolvedValueOnce(
            json({ ...androidLogin, accessToken: 'new', refreshToken: 'next' }),
        );
        const saved = deferred<void>();
        store.save.mockImplementationOnce(() => saved.promise);
        const rotation = session.gate.recover();
        // 로그인은 stage·commit을 쓰므로 회전 저장이 첫 save다.
        await vi.waitFor(() => expect(store.save).toHaveBeenCalledTimes(1));
        expect(session.liveAccessToken()).toBe('test-access');
        saved.resolve();
        expect(await rotation).toBe('refreshed');
        expect(session.liveAccessToken()).toBe('new');
    });
    it('회전 결과 저장 실패도 세션과 영속 자격을 폐기한다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        store.save.mockRejectedValueOnce(new Error('disk full'));
        fetcher.mockResolvedValueOnce(json(androidLogin));
        expect(await session.gate.recover()).toBe('login-required');
        expect(store.value).toBeNull();
        expect(session.liveAccessToken()).toBeNull();
    });
    it('실패한 새 로그인은 기존 영속 자격과 현재 계정을 유지한다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        fetcher.mockResolvedValueOnce(error(401, 'INVALID_CREDENTIALS'));
        await expect(session.authenticate('/auth/login', {})).rejects.toThrow();
        expect(store.value?.accountId).toBe(id);
        expect(store.value?.refreshToken).toBe('test-refresh');
        expect(session.snapshot().account?.id).toBe(id);
    });
    it('계정 전환은 이전 영속 자격을 교체하고 현재 참여 범위를 닫는다', async () => {
        const store = vault();
        const { session, scopes, authenticate } = setup('ANDROID', store);
        await authenticate();
        scopes.setParticipation({ eventId, memberId, participationRevision: '1' });
        await authenticate({
            ...androidLogin,
            account: { ...user, id: other },
            refreshToken: 'other-refresh',
        });
        expect(store.value).toEqual({ accountId: other, refreshToken: 'other-refresh' });
        expect(scopes.participation(eventId)).toBeNull();
        expect(session.snapshot().account?.id).toBe(other);
    });
    it('로그아웃 응답 유실에도 다음 실행에서 저장된 자격이 복구되지 않는다', async () => {
        const store = vault();
        const first = setup('ANDROID', store);
        await first.authenticate();
        first.fetcher.mockRejectedValueOnce(new TypeError('lost'));
        await expect(first.session.logout()).rejects.toThrow();
        expect(store.value).toBeNull();
        const next = setup('ANDROID', store);
        await next.session.bootstrap();
        expect(next.fetcher).not.toHaveBeenCalled();
    });
    it('기기 삭제 오류가 있어도 서버 로그아웃은 시도하고 정확한 실패를 안내한다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        store.clear.mockRejectedValueOnce(new Error('clear failed'));
        fetcher.mockResolvedValueOnce(new Response(null, { status: 204 }));
        await expect(session.logout()).rejects.toThrow();
        expect(fetcher.mock.calls[1][0]).toBe('/api/v1/auth/logout');
        expect(session.snapshot().account).toBeNull();
        expect(session.snapshot().notice).toContain('서버에서 로그아웃했지만');
    });
    it('복원 중 새 로그인은 Me 확인/저장이 끝난 뒤 새 계정으로 교체한다', async () => {
        const store = vault({ accountId: id, refreshToken: 'saved' });
        const { session, fetcher } = setup('ANDROID', store);
        const response = deferred<Response>();
        fetcher.mockResolvedValueOnce(json(androidLogin)).mockReturnValueOnce(response.promise);
        const boot = session.bootstrap();
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
        fetcher.mockResolvedValueOnce(
            json({ ...androidLogin, account: { ...user, id: other }, refreshToken: 'other' }),
        );
        const loginWork = session.authenticate('/auth/login', {});
        await Promise.resolve();
        expect(fetcher).toHaveBeenCalledTimes(2);
        response.resolve(json(user));
        await Promise.all([boot, loginWork]);
        expect(session.snapshot().account?.id).toBe(other);
        expect(store.value).toEqual({ accountId: other, refreshToken: 'other' });
    });
    it('WEB은 native 저장소를 주입해도 접근하지 않는다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('WEB', store);
        fetcher.mockResolvedValueOnce(json(user));
        await session.bootstrap();
        await authenticate();
        expect(store.take).not.toHaveBeenCalled();
        expect(store.save).not.toHaveBeenCalled();
        expect(store.stage).not.toHaveBeenCalled();
        expect(store.commit).not.toHaveBeenCalled();
        expect(store.clear).not.toHaveBeenCalled();
    });
    it('실패한 로그인 도중 옛 401이 도착해도 기존 세션의 회전을 잠그지 않는다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        const response = deferred<Response>();
        fetcher.mockReturnValueOnce(response.promise).mockResolvedValueOnce(json(androidLogin));
        const loginWork = session.authenticate('/auth/login', {});
        const rejected = expect(loginWork).rejects.toThrow();
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
        const recovery = session.gate.recover();
        await Promise.resolve();
        expect(fetcher).toHaveBeenCalledTimes(2);
        response.resolve(error(401, 'INVALID_CREDENTIALS'));
        await rejected;
        expect(await recovery).toBe('refreshed');
        expect(session.gate.loginRequired).toBe(false);
        expect(session.snapshot().account?.id).toBe(id);
        expect(store.value?.refreshToken).toBe('test-refresh');
    });
    it('새 로그인 도중 옛 401이 도착해도 새 계정의 자격을 재회전하지 않는다', async () => {
        const store = vault();
        const { session, authenticate, fetcher } = setup('ANDROID', store);
        await authenticate();
        const response = deferred<Response>();
        fetcher.mockReturnValueOnce(response.promise);
        const loginWork = session.authenticate('/auth/login', {});
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
        const recovery = session.gate.recover();
        response.resolve(
            json({ ...androidLogin, account: { ...user, id: other }, refreshToken: 'other' }),
        );
        await loginWork;
        expect(await recovery).toBe('refreshed');
        expect(fetcher).toHaveBeenCalledTimes(2);
        expect(session.snapshot().account?.id).toBe(other);
        expect(store.value?.refreshToken).toBe('other');
    });
});
