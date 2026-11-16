import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { eventId, memberId } from '../../../tests/fixtures/workflow-data';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { VoiceDesk, parseTranscribeJob } from './client';

const jobId = '10000000-0000-4000-8000-000000000081';
const audioId = '10000000-0000-4000-8000-000000000082';
const accepted = { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}`, audioId };
const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const recording = (type = 'audio/webm;codecs=opus') => ({
    id: 'rec-1',
    blob: new Blob([new Uint8Array([1, 2, 3])], { type }),
});

describe('음성 전사 접수', () => {
    let scopes: ScopeManager;
    let scope: ParticipationScope;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    const desk = () =>
        new VoiceDesk(
            {
                api: createApi({ base: '/api/v1', transport, scopes }),
                scopes,
            } as unknown as IdentitySession,
            scope,
        );
    const sent = (n: number) => {
        const init = transport.mock.calls[n][1]!;
        return {
            key: new Headers(init.headers).get('Idempotency-Key'),
            form: init.body as FormData,
        };
    };

    beforeEach(() => {
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
        scope = scopes.setParticipation({ eventId, memberId, participationRevision: '1' }).scope!;
    });
    afterEach(() => scopes.dispose());

    it('응답을 잃으면 같은 녹음을 같은 키로만 다시 보내고, 202에서 audioId/jobId를 읽는다', async () => {
        const d = desk();
        const rec = recording();
        transport.mockRejectedValueOnce(new TypeError('network'));
        expect((await d.transcribe(rec)).kind).toBe('retry');
        transport.mockResolvedValueOnce(json(202, accepted));
        expect(await d.transcribe(rec)).toEqual({ kind: 'done', data: { audioId, jobId } });
        expect(sent(1).key).toBe(sent(0).key);
        expect(sent(0).form.get('audio')).toBeInstanceOf(Blob);
        expect(sent(0).form.has('pipeline')).toBe(false);
        expect(d.pending(rec)).toBe(false);
    });

    it('실서버의 503 거절 뒤에도 같은 녹음과 키를 유지하고 수동 재시도한다', async () => {
        const d = desk();
        const rec = recording();
        transport.mockResolvedValue(
            json(503, {
                code: 'SERVICE_UNAVAILABLE',
                message: '음성 전사를 사용할 수 없습니다.',
                requestId: 'local-validation',
            }),
        );
        expect((await d.transcribe(rec)).kind).toBe('retry');
        expect(d.pending(rec)).toBe(true);
        expect(transport).toHaveBeenCalledTimes(1);
        expect(sent(0).key).toMatch(/^[0-9a-f-]{36}$/);
        expect((await d.transcribe(rec)).kind).toBe('retry');
        expect(sent(1).key).toBe(sent(0).key);
        expect(sent(1).form).toBe(sent(0).form);
        expect(sent(1).form.get('audio')).toBeInstanceOf(Blob);
    });

    it('webm이 아니거나 빈 녹음은 보내지 않는다', async () => {
        await expect(desk().transcribe(recording('audio/mp4'))).rejects.toThrow();
        await expect(
            desk().transcribe({ id: 'x', blob: new Blob([], { type: 'audio/webm' }) }),
        ).rejects.toThrow();
        expect(transport).not.toHaveBeenCalled();
    });

    it('202 본문이 계약과 다르면 접수로 보지 않고 확인하게 한다', async () => {
        transport.mockResolvedValueOnce(
            json(202, { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }),
        );
        expect((await desk().transcribe(recording())).kind).toBe('check');
    });

    it('녹음을 버리면 061로 서버 음성을 지우고, 이미 없으면 지운 것으로 본다', async () => {
        const d = desk();
        transport.mockResolvedValueOnce(new Response(null, { status: 204 }));
        expect(await d.remove(audioId)).toBe('deleted');
        const [url, init] = transport.mock.calls[0];
        expect(String(url)).toBe(`/api/v1/audio-assets/${audioId}`);
        expect(init!.method).toBe('DELETE');
        expect(new Headers(init!.headers).has('Idempotency-Key')).toBe(false);

        transport.mockResolvedValueOnce(
            json(404, { code: 'NOT_FOUND', message: '없음', requestId: 'r' }),
        );
        expect(await d.remove(audioId)).toBe('deleted');

        transport.mockRejectedValueOnce(new TypeError('network'));
        expect(await d.remove(audioId)).toBe('kept');
        expect(scopes.intentKeys.pending(scope)).toEqual([]);
    });

    it('전사 Job은 결과의 경로 표시까지 계약대로 읽는다', () => {
        const job = {
            jobId,
            kind: 'TRANSCRIBE',
            state: 'SUCCEEDED',
            attempt: '0',
            result: {
                audioId,
                text: '무대 왼쪽 마이크가 끊겨요',
                durationSec: 4.2,
                segments: [],
                pipeline: 'RAW',
                fallbackReason: 'DENOISE_FAILED',
            },
            error: null,
        };
        expect(parseTranscribeJob(job, jobId).result?.pipeline).toBe('RAW');
        const { pipeline: _drop, ...noPipeline } = job.result;
        void _drop;
        expect(() => parseTranscribeJob({ ...job, result: noPipeline }, jobId)).toThrow();
        expect(() => parseTranscribeJob(job, audioId)).toThrow();
    });
});
