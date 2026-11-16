-- 장비 수집 관측 (S15P31A202-330): 2테이블 / 26필드 / 3물리 FK
-- 기준: docs/api-contract/drafts/v9-327/internal-collector-proposed.yaml, docs/planning/be/13-equipment-safety-contracts.md
-- 수집기·토큰·sourceRef 연결·run 목록은 서버 설정(scenecrew.equipment.collector.registrations)에 둔다.
-- equipment_asset은 바꾸지 않는다(technical_state·state_evidence·revision 그대로. state_evidence는 공개 V9Evidence 형식이라
-- 계측을 넣지 않는다).
--
-- equipment_observation: 수집기가 보낸 기록 하나가 한 행. (collector_id, run_id, sequence)가 중복 판정 키다.
--   보관(V9-D17, 2026-11-05 오세진 확인): 주기 계측(READING SAMPLE)과 값이 그대로인 기록은 24시간,
--   상태 변화(REPORT 값 변화·LINK·STATUS 변화·UNPARSED)는 30일 뒤 행을 지운다. 중복 판정도 같은 기간 동안만 한다.
-- equipment_observation_latest: 장비 항목(채널·기록 종류·항목명)마다 최신 기록 1행. 기록을 지워도 최신 값은 남는다.
--   종료된 행사는 마지막 수신 후 30일 뒤 지운다.

CREATE TABLE equipment_observation (
    collector_id TEXT NOT NULL,
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_id UUID NOT NULL,
    equipment_asset_id UUID NOT NULL,
    record_type VARCHAR(12) NOT NULL,
    channel INTEGER NOT NULL,
    item TEXT,
    session_id UUID,
    collector_received_at TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL,
    content_hash TEXT NOT NULL,
    payload JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_equipment_observation PRIMARY KEY (collector_id, run_id, sequence)
);

-- item은 READING의 param이며 그 밖의 기록은 ''이다(기본키에 쓰기 위해 NULL 대신 빈 문자열).
CREATE TABLE equipment_observation_latest (
    equipment_asset_id UUID NOT NULL,
    channel INTEGER NOT NULL,
    record_type VARCHAR(12) NOT NULL,
    item TEXT NOT NULL,
    collector_id TEXT NOT NULL,
    run_id UUID NOT NULL,
    run_order INTEGER NOT NULL,
    sequence BIGINT NOT NULL,
    session_id UUID,
    collector_received_at TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT pk_equipment_observation_latest PRIMARY KEY (equipment_asset_id, channel, record_type, item)
);

ALTER TABLE equipment_observation ADD CONSTRAINT fk_equipment_observation_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_observation ADD CONSTRAINT fk_equipment_observation_equipment_asset_id FOREIGN KEY (equipment_asset_id) REFERENCES equipment_asset (id);
ALTER TABLE equipment_observation_latest ADD CONSTRAINT fk_equipment_observation_latest_equipment_asset_id FOREIGN KEY (equipment_asset_id) REFERENCES equipment_asset (id);

ALTER TABLE equipment_observation ADD CONSTRAINT chk_equipment_observation_record_type CHECK (record_type IN ('READING', 'LINK', 'STATUS', 'UNPARSED'));
ALTER TABLE equipment_observation ADD CONSTRAINT chk_equipment_observation_sequence CHECK (sequence >= 1);
ALTER TABLE equipment_observation ADD CONSTRAINT chk_equipment_observation_channel CHECK (channel >= 1);
ALTER TABLE equipment_observation_latest ADD CONSTRAINT chk_equipment_observation_latest_record_type CHECK (record_type IN ('READING', 'LINK', 'STATUS'));
ALTER TABLE equipment_observation_latest ADD CONSTRAINT chk_equipment_observation_latest_run_order CHECK (run_order >= 0);

CREATE INDEX ix_equipment_observation_expires ON equipment_observation (expires_at);
CREATE INDEX ix_equipment_observation_asset_received ON equipment_observation (equipment_asset_id, server_received_at);
