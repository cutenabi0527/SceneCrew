import type { CommandSpec, createApi } from '../../shared/api/api';
import { asUuid } from '../../shared/api/contract';
import { scopedKey, type ParticipationScope } from '../../shared/scope/scope';
import {
    parseTranscribeJob,
    parseTranscriptionAccepted,
    type TranscriptionAccepted,
} from './contract';

type Api = Pick<ReturnType<typeof createApi>, 'command' | 'get'>;

/** 060이 받는 오디오 형식(AudioUpload의 audio part) */
export const AUDIO_TYPES = ['audio/wav', 'audio/mpeg', 'audio/mp4', 'audio/webm'] as const;

export interface TranscribeInput {
    eventId: string;
    /** 녹음한 원음. 재생과 원음 재전사는 모두 이 원음을 쓴다 */
    audio: Blob;
    fileName: string;
    /** 계약에 남은 원음 지정 값. 서버 기본이 원음 전사라(V9-D41) 화면에서는 보내지 않는다 */
    pipeline?: 'RAW';
    /** 같은 의도의 재전송은 같은 값을 쓴다. 새 녹음·원음 재전사는 새 값으로 시작한다 */
    intentId: string;
}

async function sha256(blob: Blob) {
    const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * SC-API-060 음성 전사 접수 명령. multipart의 audio part에 원음을 넣고, 원음 재전사이면 pipeline=RAW를 붙인다.
 * 멱등 키는 의도당 한 번이며(REQUIRED, 24h) 처리 불명·만료에 새 키로 자동 재실행하지 않는다.
 */
export async function transcribeSpec(input: TranscribeInput): Promise<CommandSpec> {
    const type = input.audio.type.split(';')[0].trim();
    if (!(AUDIO_TYPES as readonly string[]).includes(type))
        throw new Error('이 녹음 형식은 보낼 수 없습니다. 텍스트로 보고해주세요.');
    const form = new FormData();
    form.append('audio', input.audio, input.fileName);
    if (input.pipeline) form.append('pipeline', input.pipeline);
    return {
        operation: 'transcription:submit',
        intentId: input.intentId,
        method: 'POST',
        path: `/events/${asUuid(input.eventId, 'eventId')}/transcriptions`,
        form: {
            data: form,
            fingerprint: JSON.stringify([
                input.fileName,
                type,
                input.audio.size,
                await sha256(input.audio),
                input.pipeline ?? null,
            ]),
        },
        response: 'job',
        recovery: 'same-key',
        idempotency: 'required',
    };
}

export type TranscribeOutcome =
    | { status: 'accepted'; receipt: TranscriptionAccepted }
    | Exclude<Awaited<ReturnType<Api['command']>>, { status: 'accepted' | 'succeeded' }>;

/** 접수(202)면 audioId까지 계약대로 읽는다. 실패·처리 미확인·만료는 그대로 돌려 호출자가 안내한다. */
export async function submitTranscription(
    api: Api,
    scope: ParticipationScope,
    input: TranscribeInput,
): Promise<TranscribeOutcome> {
    const outcome = await api.command(scope, await transcribeSpec(input));
    if (outcome.status === 'accepted') {
        const receipt = parseTranscriptionAccepted(outcome.data);
        return { status: 'accepted', receipt };
    }
    if (outcome.status === 'succeeded')
        // response: 'job'은 202만 성공으로 본다. 공통 층이 막지만 형식상 남겨 둔다.
        return { status: 'reconcile' };
    return outcome;
}

export function transcribeJobQuery(api: Api, scope: ParticipationScope, jobId: string) {
    const id = asUuid(jobId, 'jobId');
    return {
        queryKey: scopedKey(scope, 'transcribe-job', id),
        queryFn: ({ signal }: { signal: AbortSignal }) =>
            api.get(scope, `/ai-jobs/${id}`, (data) => parseTranscribeJob(data, id), { signal }),
        retry: false as const,
        staleTime: 0,
    };
}
