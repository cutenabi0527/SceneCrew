import {
    ContractViolationError,
    asArray,
    asBigIntString,
    asRecord,
    asString,
    asUuid,
} from '../../shared/api/contract';

// 공개 전사 결과(TranscriptionResult). BE 합의로 pipeline(필수)·fallbackReason(값 또는 null)이 추가됐다.
//  - pipeline: 실제로 전사에 쓴 입력. DENOISED=소음 처리본, RAW=원음.
//  - fallbackReason: 소음 처리를 쓰지 못해 원음으로 전사한 이유. 지금은 항상 원음 전사라(V9-D41) null이며 화면에 표시하지 않는다.
// 전사는 임시 결과이며 Report 제출이 아니다. 사용자가 확인·수정한 뒤 명시 제출한다.
export type TranscriptionPipeline = 'DENOISED' | 'RAW';
export interface TranscriptionSegment {
    startSec: number;
    endSec: number;
    text: string;
}
export interface TranscriptionResult {
    audioId: string;
    text: string;
    durationSec: number;
    segments: TranscriptionSegment[];
    pipeline: TranscriptionPipeline;
    fallbackReason: string | null;
}
export interface TranscriptionAccepted {
    jobId: string;
    statusUrl: string;
    audioId: string;
}
export type TranscribeJob = { jobId: string; kind: 'TRANSCRIBE'; attempt: string } & (
    | { state: 'QUEUED' | 'RUNNING'; result: null; error: null }
    | { state: 'SUCCEEDED'; result: TranscriptionResult; error: null }
    | { state: 'FAILED'; result: null; error: { code: string; retryable: boolean } }
);

/** 오디오 한 건의 최대 길이(초). 계약의 durationSec 상한과 같다. */
export const MAX_AUDIO_SEC = 30;

function fail(path: string): never {
    throw new ContractViolationError(path, '공개 전사 계약과 다릅니다.');
}
/** 정의된 필드만, 그리고 필수 필드는 모두 있어야 한다(additionalProperties: false + required) */
function keys(body: Record<string, unknown>, allowed: readonly string[], path: string) {
    if (Object.keys(body).some((key) => !allowed.includes(key))) fail(path);
    if (allowed.some((key) => !(key in body))) fail(path);
}
function seconds(value: unknown, path: string, max?: number) {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) fail(path);
    if (max !== undefined && value > max) fail(path);
    return value;
}

export function parseTranscriptionResult(value: unknown): TranscriptionResult {
    const body = asRecord(value, 'result');
    keys(
        body,
        ['audioId', 'text', 'durationSec', 'segments', 'pipeline', 'fallbackReason'],
        'result',
    );
    const durationSec = seconds(body.durationSec, 'result.durationSec', MAX_AUDIO_SEC);
    const segments = asArray(body.segments, 'result.segments').map((item, index) => {
        const path = `result.segments[${index}]`;
        const s = asRecord(item, path);
        keys(s, ['startSec', 'endSec', 'text'], path);
        const startSec = seconds(s.startSec, `${path}.startSec`);
        const endSec = seconds(s.endSec, `${path}.endSec`);
        if (endSec < startSec) fail(`${path}.endSec`);
        return { startSec, endSec, text: asString(s.text, `${path}.text`) };
    });
    if (body.pipeline !== 'DENOISED' && body.pipeline !== 'RAW') fail('result.pipeline');
    let fallbackReason: string | null = null;
    if (body.fallbackReason !== null) {
        fallbackReason = asString(body.fallbackReason, 'result.fallbackReason');
        // 빈 문자열은 "이유 없음"과 구별할 수 없으므로 이유가 없으면 null이어야 한다
        if (!fallbackReason.trim()) fail('result.fallbackReason');
    }
    return {
        audioId: asUuid(body.audioId, 'result.audioId'),
        text: asString(body.text, 'result.text'),
        durationSec,
        segments,
        pipeline: body.pipeline,
        fallbackReason,
    };
}

/** 060 접수(202): Job 조회 주소와 같은 행사의 audioId를 함께 받는다. */
export function parseTranscriptionAccepted(value: unknown): TranscriptionAccepted {
    const body = asRecord(value, '응답');
    keys(body, ['jobId', 'statusUrl', 'audioId'], '응답');
    const jobId = asUuid(body.jobId, '응답.jobId');
    const statusUrl = asString(body.statusUrl, '응답.statusUrl');
    if (statusUrl.toLowerCase() !== `/api/v1/ai-jobs/${jobId}`.toLowerCase())
        fail('응답.statusUrl');
    return { jobId, statusUrl, audioId: asUuid(body.audioId, '응답.audioId') };
}

export function parseTranscribeJob(value: unknown, expectedJobId: string): TranscribeJob {
    const body = asRecord(value, 'job');
    keys(body, ['jobId', 'kind', 'state', 'attempt', 'result', 'error'], 'job');
    const jobId = asUuid(body.jobId, 'job.jobId');
    if (jobId.toLowerCase() !== asUuid(expectedJobId, 'jobId').toLowerCase()) fail('job.jobId');
    if (body.kind !== 'TRANSCRIBE') fail('job.kind');
    const common = {
        jobId,
        kind: 'TRANSCRIBE' as const,
        attempt: asBigIntString(body.attempt, 'job.attempt'),
    };
    if (body.state === 'QUEUED' || body.state === 'RUNNING') {
        if (body.result !== null || body.error !== null) fail('job');
        return { ...common, state: body.state, result: null, error: null };
    }
    if (body.state === 'SUCCEEDED') {
        if (body.error !== null) fail('job.error');
        return {
            ...common,
            state: 'SUCCEEDED',
            result: parseTranscriptionResult(body.result),
            error: null,
        };
    }
    if (body.state === 'FAILED') {
        if (body.result !== null) fail('job.result');
        const error = asRecord(body.error, 'job.error');
        keys(error, ['code', 'retryable'], 'job.error');
        const code = asString(error.code, 'job.error.code');
        if (!code) fail('job.error.code');
        if (typeof error.retryable !== 'boolean') fail('job.error.retryable');
        return {
            ...common,
            state: 'FAILED',
            result: null,
            error: { code, retryable: error.retryable },
        };
    }
    return fail('job.state');
}
