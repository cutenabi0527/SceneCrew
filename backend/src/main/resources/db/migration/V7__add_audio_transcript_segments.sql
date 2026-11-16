-- 임시 전사 구간 (S15P31A202-266): 0테이블 / 1필드 / 0물리 FK
-- 기준: docs/planning/records/STT-CONTRACT-TRANSITION.md Q22(2026-11-04). 공개 TranscriptionResult의 segments가 필수라
-- STT 결과2의 segments를 transcript와 같은 곳·같은 수명으로 저장한다(제출·본인 삭제·생성 후 24시간에 함께 지운다).
-- 형식: [{"startSec": 0.0, "endSec": 2.0, "text": "..."}]. 전사 전이거나 지운 뒤에는 NULL이다.
-- V6(장비 수집 관측, S15P31A202-330) 다음 번호다. NULL 허용이라 이 칸을 모르는 이전 이미지도 그대로 동작한다.

ALTER TABLE audio_asset ADD COLUMN transcript_segments JSONB;

ALTER TABLE audio_asset ADD CONSTRAINT chk_audio_asset_transcript_segments_array
    CHECK (transcript_segments IS NULL OR jsonb_typeof(transcript_segments) = 'array');
