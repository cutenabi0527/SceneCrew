-- 장비 웹 연결(#394, V9-D59·D60). 서버 설정(COLLECTOR_REGISTRATIONS) 대신 연결 코드로 수집기를 DB에 등록한다.
-- equipment_collector: 코드를 바꿔 받은 노트북 수집 프로그램. 토큰은 SHA-256만 둔다. id는 web-로 시작해 설정 등록과 겹치지 않는다.
--   program_version·host_label은 프로그램이 교환 때 알린 버전·노트북 이름(선택, 화면 표시용).
--   last_seen_at은 프로그램의 마지막 신호(관측 묶음·수신기 보고), last_batch_at은 마지막 관측 묶음 시각이다.
-- equipment_collector_code: 10분짜리 일회용 연결 코드(원문은 발급 응답에만). 쓰면 used_at과 만든 수집기를 남긴다.
-- equipment_collector_receiver: 수집기가 찾은 수신기(불투명 키·이름·모델·채널 수). IP는 저장하지 않는다.
-- equipment_asset 연결 칸: 연결한 수집기·수신기 키·채널·연결한 사람·시각. 함께 있거나 함께 없고, 자동 관측 장비만 연결한다.

CREATE TABLE equipment_collector (
    id TEXT PRIMARY KEY,
    event_id UUID NOT NULL,
    member_id UUID NOT NULL,
    token_sha256 TEXT NOT NULL,
    run_id UUID NOT NULL,
    program_version TEXT,
    host_label TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ,
    last_batch_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_equipment_collector_token_sha256 UNIQUE (token_sha256)
);

CREATE TABLE equipment_collector_code (
    code_sha256 TEXT PRIMARY KEY,
    event_id UUID NOT NULL,
    member_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    collector_id TEXT
);

CREATE TABLE equipment_collector_receiver (
    collector_id TEXT NOT NULL,
    receiver_key TEXT NOT NULL,
    name TEXT,
    model TEXT,
    channels INTEGER NOT NULL,
    seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_equipment_collector_receiver PRIMARY KEY (collector_id, receiver_key)
);

ALTER TABLE equipment_asset ADD COLUMN link_collector_id TEXT;
ALTER TABLE equipment_asset ADD COLUMN link_source_ref TEXT;
ALTER TABLE equipment_asset ADD COLUMN link_channel INTEGER;
ALTER TABLE equipment_asset ADD COLUMN link_member_id UUID;
ALTER TABLE equipment_asset ADD COLUMN linked_at TIMESTAMPTZ;

ALTER TABLE equipment_collector ADD CONSTRAINT fk_equipment_collector_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_collector ADD CONSTRAINT fk_equipment_collector_member_id FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE equipment_collector_code ADD CONSTRAINT fk_equipment_collector_code_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_collector_code ADD CONSTRAINT fk_equipment_collector_code_member_id FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE equipment_collector_code ADD CONSTRAINT fk_equipment_collector_code_collector_id FOREIGN KEY (collector_id) REFERENCES equipment_collector (id);
ALTER TABLE equipment_collector_receiver ADD CONSTRAINT fk_equipment_collector_receiver_collector_id FOREIGN KEY (collector_id) REFERENCES equipment_collector (id);
ALTER TABLE equipment_asset ADD CONSTRAINT fk_equipment_asset_link_collector_id FOREIGN KEY (link_collector_id) REFERENCES equipment_collector (id);
ALTER TABLE equipment_asset ADD CONSTRAINT fk_equipment_asset_link_member_id FOREIGN KEY (link_member_id) REFERENCES member (id);

ALTER TABLE equipment_collector_receiver ADD CONSTRAINT chk_equipment_collector_receiver_channels CHECK (channels BETWEEN 1 AND 4);
ALTER TABLE equipment_asset ADD CONSTRAINT chk_equipment_asset_link
    CHECK ((link_collector_id IS NULL AND link_source_ref IS NULL AND link_channel IS NULL AND link_member_id IS NULL AND linked_at IS NULL)
        OR (link_collector_id IS NOT NULL AND link_source_ref IS NOT NULL AND link_channel IS NOT NULL AND link_member_id IS NOT NULL
            AND linked_at IS NOT NULL AND auto_observation_kind IS NOT NULL));

-- 한 수신기는 한 장비 칸에만 연결한다(관측 기록의 sourceRef로 장비를 찾는다).
CREATE UNIQUE INDEX uq_equipment_asset_link_source ON equipment_asset (link_collector_id, link_source_ref)
    WHERE link_collector_id IS NOT NULL;
CREATE INDEX ix_equipment_collector_event ON equipment_collector (event_id);
CREATE INDEX ix_equipment_collector_code_member ON equipment_collector_code (member_id);
