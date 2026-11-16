import { parseTranscriptionResult, type TranscriptionResult } from './contract';

// 데모 전사: 060 → Job 조회가 돌려줄 TranscriptionResult와 같은 모양을 strict parser로 검증해 쓴다.
// 실제 STT 연동 시 submitTranscription·transcribeJobQuery 결과로 교체한다.
const DEMO_AUDIO_ID = '03aed42b-6383-5d46-ba59-3a3697b556fb';
const TEXT = '스테이지 앞에 관객이 많이 몰려서 통로가 좁아졌어요. 지원 한 명 필요합니다.';

/** 전사는 항상 원음으로 한다(V9-D41). */
export function demoTranscribe(): TranscriptionResult {
    const text = TEXT;
    return parseTranscriptionResult({
        audioId: DEMO_AUDIO_ID,
        text,
        durationSec: 6,
        segments: [{ startSec: 0, endSec: 6, text }],
        pipeline: 'RAW',
        fallbackReason: null,
    });
}
