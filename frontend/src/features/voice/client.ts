import type { CommandSpec } from '../../shared/api/api';
import { ContractViolationError, asUuid } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { sameKeyResult, type Result } from '../response/client';

// 음성 보고 전사(060 접수, 063 Job 조회, #306).
// - 녹음은 webm/Opus로만 보낸다(V9-D19-1). 최대 30초·10,485,760byte.
// - 접수는 같은 키 재요청이다(V9-D19-2). 응답을 잃으면 같은 녹음·같은 키로만 다시 보낸다.
//   같은 키 재요청이면 서버는 최초 202(audioId·jobId·statusUrl)를 24시간 그대로 돌려준다.
// - 전사는 항상 원음으로 한다(V9-D41). 원음 재전사(pipeline=RAW)는 보내지 않는다.

export const AUDIO_TYPE = 'audio/webm;codecs=opus';
export const MAX_AUDIO_BYTES = 10_485_760;
export const MAX_AUDIO_SECONDS = 30;

export interface Recording {
    // 이 녹음을 구별하는 값. 같은 녹음의 재전송인지 판단한다.
    id: string;
    blob: Blob;
}

export interface Accepted {
    audioId: string;
    jobId: string;
}

export interface Transcription {
    audioId: string;
    text: string;
    durationSec: number;
    segments: { startSec: number; endSec: number; text: string }[];
    pipeline: 'DENOISED' | 'RAW';
    fallbackReason: 'DENOISE_FAILED' | 'DENOISE_TIMEOUT' | null;
}

export type TranscribeJob = {
    jobId: string;
    kind: 'TRANSCRIBE';
    attempt: string;
} & (
    | { state: 'QUEUED' | 'RUNNING'; result: null; error: null }
    | { state: 'SUCCEEDED'; result: Transcription; error: null }
    | { state: 'FAILED'; result: null; error: { code: string; retryable: boolean } }
);

export function parseTranscribeJob(data: unknown, jobId: string): TranscribeJob {
    const job = parseV9<TranscribeJob>('V9JobTranscribe', data);
    if (job.jobId !== jobId)
        throw new ContractViolationError('job.jobId', '요청한 Job이어야 합니다.');
    return job;
}

type Spec = Omit<CommandSpec, 'operation' | 'intentId'>;

export class VoiceDesk {
    private intents = new Map<string, { id: string; spec: Spec }>();

    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {
        scope.onDispose(() => this.intents.clear());
    }

    private key(recording: Recording) {
        return recording.id;
    }

    pending(recording: Recording) {
        return this.intents.has(this.key(recording));
    }

    /** 사용자가 이 접수를 버리고 새로 하겠다고 했다 */
    forget(recording: Recording) {
        const key = this.key(recording);
        const intent = this.intents.get(key);
        if (intent) this.session.scopes.intentKeys.release(this.scope, `voice:${key}`, intent.id);
        this.intents.delete(key);
    }

    /** 060 전사 접수. 202면 audioId·jobId를 돌려준다. */
    async transcribe(recording: Recording): Promise<Result<Accepted>> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const blob = recording.blob;
        if (!blob.type.startsWith('audio/webm'))
            throw new ContractViolationError('audio', 'webm/Opus 녹음만 보낼 수 있습니다.');
        if (!blob.size || blob.size > MAX_AUDIO_BYTES)
            throw new ContractViolationError('audio', '녹음 파일이 비었거나 10MB를 넘습니다.');
        const key = this.key(recording);
        let intent = this.intents.get(key);
        if (!intent) {
            const data = new FormData();
            data.append('audio', blob, 'report.webm');
            intent = {
                id: crypto.randomUUID(),
                spec: {
                    method: 'POST',
                    path: `/events/${this.scope.eventId}/transcriptions`,
                    form: { data, fingerprint: `${recording.id}:${blob.size}:${blob.type}` },
                    response: 'job',
                },
            };
            this.intents.set(key, intent);
        }
        const outcome = await this.session.api.command(
            this.scope,
            {
                ...intent.spec,
                operation: `voice:${key}`,
                intentId: intent.id,
                recovery: 'same-key',
            },
            (d) => d,
        );
        if (outcome.status === 'accepted') {
            try {
                const body = parseV9<{ jobId: string; statusUrl: string; audioId: string }>(
                    'TranscriptionAccepted',
                    outcome.data,
                );
                if (body.jobId !== outcome.jobId)
                    throw new ContractViolationError('jobId', '접수 Job이 맞지 않습니다.');
                if (this.intents.get(key) === intent) this.intents.delete(key);
                return { kind: 'done', data: { audioId: body.audioId, jobId: body.jobId } };
            } catch {
                // 접수는 됐지만 본문이 계약과 다르다. 같은 키로 확인하게 한다.
                return { kind: 'check' };
            }
        }
        const { result, keep } = sameKeyResult(outcome);
        if (!keep && this.intents.get(key) === intent) this.intents.delete(key);
        // 202가 아닌 성공은 계약에 없다
        return result.kind === 'done' ? { kind: 'check' } : (result as Result<Accepted>);
    }

    /**
     * 061 서버의 음성 원본·임시 전사 정리. 녹음을 버릴 때 쓴다.
     * 이미 없으면(404) 지운 것으로 본다. 그 밖의 실패는 서버가 24시간 안에 지우므로 다시 보내지 않는다.
     */
    async remove(audioId: string): Promise<'deleted' | 'kept'> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const id = asUuid(audioId, 'audioId');
        const operation = `voice:delete:${id}`;
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(this.scope, {
            operation,
            intentId,
            method: 'DELETE',
            path: `/audio-assets/${id}`,
            idempotency: 'none',
        });
        this.session.scopes.intentKeys.release(this.scope, operation, intentId);
        if (outcome.status === 'succeeded') return 'deleted';
        if (outcome.status === 'failed' && outcome.failure.status === 404) return 'deleted';
        return 'kept';
    }

    job(jobId: string, signal?: AbortSignal) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const id = asUuid(jobId, 'jobId');
        return this.session.api.get(
            this.scope,
            `/ai-jobs/${id}`,
            (d) => parseTranscribeJob(d, id),
            {
                signal,
            },
        );
    }
}

const desks = new WeakMap<ParticipationScope, VoiceDesk>();
export function voiceDesk(session: IdentitySession, scope: ParticipationScope) {
    let value = desks.get(scope);
    if (!value) {
        value = new VoiceDesk(session, scope);
        desks.set(scope, value);
    }
    return value;
}
