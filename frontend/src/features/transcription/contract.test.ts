import { describe, expect, it } from 'vitest';
import { ContractViolationError } from '../../shared/api/contract';
import { transcribeSpec } from './client';
import {
    parseTranscribeJob,
    parseTranscriptionAccepted,
    parseTranscriptionResult,
} from './contract';

const jobId = '6a93f560-90d5-5876-8a1f-36a5bf4f9a73';
const audioId = '03aed42b-6383-5d46-ba59-3a3697b556fb';
const eventId = 'ef60800c-c1ce-5d1c-b67c-01d948e7f137';
const result = {
    audioId,
    text: '장비에 문제가 발생했습니다.',
    durationSec: 3,
    segments: [{ startSec: 0, endSec: 3, text: '장비에 문제가 발생했습니다.' }],
    pipeline: 'DENOISED',
    fallbackReason: null,
};
const without = (key: string) =>
    Object.fromEntries(Object.entries(result).filter(([k]) => k !== key));
const job = { jobId, kind: 'TRANSCRIBE', state: 'SUCCEEDED', attempt: '1', result, error: null };

describe('공개 전사 결과(TranscriptionResult)', () => {
    it('pipeline과 fallbackReason을 함께 읽는다', () => {
        expect(parseTranscriptionResult(result)).toEqual(result);
        const raw = parseTranscriptionResult({
            ...result,
            pipeline: 'RAW',
            fallbackReason: 'DENOISE_FAILED',
        });
        expect(raw).toMatchObject({ pipeline: 'RAW', fallbackReason: 'DENOISE_FAILED' });
    });
    it.each([
        ['pipeline 누락', without('pipeline')],
        ['fallbackReason 누락', without('fallbackReason')],
        ['정의되지 않은 pipeline', { ...result, pipeline: 'CLEAN' }],
        ['소문자 pipeline', { ...result, pipeline: 'raw' }],
        ['빈 fallbackReason', { ...result, fallbackReason: '' }],
        ['숫자 fallbackReason', { ...result, fallbackReason: 1 }],
        ['30초 초과', { ...result, durationSec: 31 }],
        ['음수 길이', { ...result, durationSec: -1 }],
        [
            '끝이 시작보다 앞선 구간',
            { ...result, segments: [{ startSec: 2, endSec: 1, text: '' }] },
        ],
        ['추가 필드', { ...result, confidence: 0.9 }],
        ['구간 추가 필드', { ...result, segments: [{ ...result.segments[0], speaker: 'A' }] }],
        ['audioId 형식', { ...result, audioId: 'audio-1' }],
    ])('%s는 계약 위반으로 막는다', (_name, value) => {
        expect(() => parseTranscriptionResult(value)).toThrow(ContractViolationError);
    });
});

describe('전사 Job과 060 접수', () => {
    it('성공 Job의 결과를 strict하게 읽는다', () => {
        expect(parseTranscribeJob(job, jobId)).toMatchObject({
            state: 'SUCCEEDED',
            result: { pipeline: 'DENOISED', fallbackReason: null },
        });
        expect(() =>
            parseTranscribeJob({ ...job, result: { ...result, pipeline: undefined } }, jobId),
        ).toThrow(ContractViolationError);
    });
    it.each(['QUEUED', 'RUNNING'])('%s는 결과 없이 진행 중이다', (state) => {
        expect(parseTranscribeJob({ ...job, state, result: null }, jobId).state).toBe(state);
    });
    it('실패는 결과 없이 오류만 읽는다', () => {
        expect(
            parseTranscribeJob(
                {
                    ...job,
                    state: 'FAILED',
                    result: null,
                    error: { code: 'AI_TIMEOUT', retryable: false },
                },
                jobId,
            ),
        ).toMatchObject({ state: 'FAILED', error: { code: 'AI_TIMEOUT' } });
    });
    it.each([
        { ...job, kind: 'GENERATE_PLAN' },
        { ...job, jobId: audioId },
        { ...job, attempt: 1 },
        { ...job, state: 'SUCCEEDED', error: { code: 'X', retryable: false } },
    ])('다른 Job/형식은 막는다 (%#)', (value) => {
        expect(() => parseTranscribeJob(value, jobId)).toThrow(ContractViolationError);
    });
    it('접수 응답은 statusUrl과 audioId를 확인한다', () => {
        const ok = { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}`, audioId };
        expect(parseTranscriptionAccepted(ok)).toEqual(ok);
        expect(() =>
            parseTranscriptionAccepted({ ...ok, statusUrl: '/api/v1/ai-jobs/other' }),
        ).toThrow(ContractViolationError);
        expect(() => parseTranscriptionAccepted({ jobId, statusUrl: ok.statusUrl })).toThrow(
            ContractViolationError,
        );
    });
});

describe('060 전송 명세', () => {
    const audio = new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/webm;codecs=opus' });
    it('기본 전사는 원음만 보내고 pipeline을 붙이지 않는다', async () => {
        const spec = await transcribeSpec({ eventId, audio, fileName: 'r.webm', intentId: 'i1' });
        expect(spec).toMatchObject({
            method: 'POST',
            path: `/events/${eventId}/transcriptions`,
            response: 'job',
            recovery: 'same-key',
            idempotency: 'required',
        });
        expect(spec.form!.data.get('audio')).toBeInstanceOf(Blob);
        expect(spec.form!.data.has('pipeline')).toBe(false);
    });
    it('원음 재전사는 같은 원음에 pipeline=RAW를 붙이고 다른 지문이 된다', async () => {
        const base = await transcribeSpec({ eventId, audio, fileName: 'r.webm', intentId: 'i1' });
        const raw = await transcribeSpec({
            eventId,
            audio,
            fileName: 'r.webm',
            intentId: 'i2',
            pipeline: 'RAW',
        });
        expect(raw.form!.data.get('pipeline')).toBe('RAW');
        expect(raw.form!.fingerprint).not.toBe(base.form!.fingerprint);
    });
    it('허용하지 않은 형식은 보내지 않는다', async () => {
        await expect(
            transcribeSpec({
                eventId,
                audio: new Blob([], { type: 'audio/ogg' }),
                fileName: 'r.ogg',
                intentId: 'i3',
            }),
        ).rejects.toThrow();
    });
});
