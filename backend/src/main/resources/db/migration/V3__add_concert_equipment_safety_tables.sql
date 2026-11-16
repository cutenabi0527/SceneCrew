-- 콘서트 v9 장비·안전·상황 테이블 (S15P31A202-326): 13테이블 / 133필드 / 35물리 FK
-- 기준: docs/planning/be/12-erd-contract.md, 03-data-dictionary-b.md, concert-contract-v9.json models
-- 공식 사실 보호를 위해 FK에 CASCADE를 쓰지 않는다. 같은 행사 소속 검사는 Service에서 한다.
-- 논리 참조(safety_camera.latest_observation_id, operational_dependency.from_id/to_id, JSONB 안의 id)는 FK로 강제하지 않는다.
-- 카메라 원본 영상·프레임은 저장하지 않는다(V9-D09).

CREATE TABLE venue_layout (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    coordinate_system TEXT NOT NULL,
    width NUMERIC NOT NULL,
    height NUMERIC NOT NULL,
    zone_polygons JSONB NOT NULL,
    source JSONB NOT NULL,
    confirmed_by_member_id UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_venue_layout_event_id UNIQUE (event_id)
);

CREATE TABLE equipment_asset (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    name TEXT NOT NULL,
    model TEXT,
    technical_state VARCHAR(20) NOT NULL,
    state_evidence JSONB NOT NULL,
    component_asset_ids JSONB NOT NULL,
    ports JSONB NOT NULL,
    verified_compatibility JSONB NOT NULL,
    storage_zone_id UUID,
    holder_member_id UUID,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_equipment_asset_event_id_name UNIQUE (event_id, name)
);

CREATE TABLE equipment_connection (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    from_asset_id UUID NOT NULL,
    from_port TEXT NOT NULL,
    to_asset_id UUID NOT NULL,
    to_port TEXT NOT NULL,
    confirmed_by_member_id UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL,
    active BOOLEAN NOT NULL
);

CREATE TABLE equipment_connection_port (
    connection_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    port TEXT NOT NULL,
    CONSTRAINT pk_equipment_connection_port PRIMARY KEY (connection_id, asset_id, port),
    CONSTRAINT uq_equipment_connection_port_asset_id_port UNIQUE (asset_id, port)
);

CREATE TABLE equipment_requirement (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    program_id UUID,
    task_ids JSONB NOT NULL,
    requirements JSONB NOT NULL,
    ready_by TIMESTAMPTZ NOT NULL,
    schedule_policy JSONB NOT NULL,
    time_evidence JSONB NOT NULL,
    revision BIGINT NOT NULL
);

CREATE TABLE equipment_reservation (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    program_id UUID,
    task_id UUID,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(12) NOT NULL,
    source_change_set_id UUID,
    revision BIGINT NOT NULL
);

CREATE TABLE equipment_reservation_asset (
    reservation_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    CONSTRAINT pk_equipment_reservation_asset PRIMARY KEY (reservation_id, asset_id)
);

CREATE TABLE operational_dependency (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    from_kind VARCHAR(12) NOT NULL,
    from_id UUID NOT NULL,
    to_kind VARCHAR(12) NOT NULL,
    to_id UUID NOT NULL,
    lag_sec NUMERIC NOT NULL,
    evidence JSONB NOT NULL,
    confirmed_by_member_id UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_operational_dependency_key UNIQUE (event_id, from_kind, from_id, to_kind, to_id)
);

CREATE TABLE recovery_simulation (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    issue_id UUID,
    target_asset_ids JSONB NOT NULL,
    created_by_member_id UUID NOT NULL,
    base_revision BIGINT NOT NULL,
    input_hash TEXT NOT NULL,
    evidence_refs JSONB NOT NULL,
    policy_version TEXT NOT NULL,
    assumptions JSONB NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL,
    candidates JSONB NOT NULL
);

CREATE TABLE safety_zone_profile (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    layout_id UUID NOT NULL,
    layout_revision BIGINT NOT NULL,
    effective_area_m2 NUMERIC,
    area_evidence JSONB,
    risk_rule JSONB,
    minimum_crew_rule JSONB,
    confirmed_by_member_id UUID,
    confirmed_at TIMESTAMPTZ,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_safety_zone_profile_zone_id UNIQUE (zone_id)
);

CREATE TABLE safety_camera (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    name TEXT NOT NULL,
    source_binding_ref TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    coverage JSONB NOT NULL,
    configured_by_member_id UUID NOT NULL,
    configured_at TIMESTAMPTZ NOT NULL,
    connection_state VARCHAR(16) NOT NULL,
    stream_session_id UUID,
    last_sequence BIGINT,
    last_captured_at TIMESTAMPTZ,
    last_received_at TIMESTAMPTZ,
    last_processed_at TIMESTAMPTZ,
    latest_observation_id UUID,
    freshness VARCHAR(20) NOT NULL,
    CONSTRAINT uq_safety_camera_event_id_name UNIQUE (event_id, name)
);

CREATE TABLE safety_observation (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    camera_id UUID NOT NULL,
    camera_revision BIGINT NOT NULL,
    stream_session_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    captured_at TIMESTAMPTZ,
    capture_time_basis TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    input_hash TEXT NOT NULL,
    coverage JSONB NOT NULL,
    state VARCHAR(16) NOT NULL,
    freshness VARCHAR(20) NOT NULL,
    estimates JSONB NOT NULL,
    model_version TEXT,
    processed_at TIMESTAMPTZ,
    metadata_expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_safety_observation_camera_id_stream_session_id_sequence UNIQUE (camera_id, stream_session_id, sequence)
);

CREATE TABLE operational_situation (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    issue_id UUID,
    primary_type VARCHAR(32),
    related_types JSONB NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_refs JSONB NOT NULL,
    confirmed_by_member_id UUID,
    confirmed_at TIMESTAMPTZ
);

-- 물리 FK 35개
ALTER TABLE venue_layout ADD CONSTRAINT fk_venue_layout_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE venue_layout ADD CONSTRAINT fk_venue_layout_confirmed_by_member_id FOREIGN KEY (confirmed_by_member_id) REFERENCES member (id);
ALTER TABLE equipment_asset ADD CONSTRAINT fk_equipment_asset_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_asset ADD CONSTRAINT fk_equipment_asset_storage_zone_id FOREIGN KEY (storage_zone_id) REFERENCES zone (id);
ALTER TABLE equipment_asset ADD CONSTRAINT fk_equipment_asset_holder_member_id FOREIGN KEY (holder_member_id) REFERENCES member (id);
ALTER TABLE equipment_connection ADD CONSTRAINT fk_equipment_connection_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_connection ADD CONSTRAINT fk_equipment_connection_from_asset_id FOREIGN KEY (from_asset_id) REFERENCES equipment_asset (id);
ALTER TABLE equipment_connection ADD CONSTRAINT fk_equipment_connection_to_asset_id FOREIGN KEY (to_asset_id) REFERENCES equipment_asset (id);
ALTER TABLE equipment_connection ADD CONSTRAINT fk_equipment_connection_confirmed_by_member_id FOREIGN KEY (confirmed_by_member_id) REFERENCES member (id);
ALTER TABLE equipment_connection_port ADD CONSTRAINT fk_equipment_connection_port_connection_id FOREIGN KEY (connection_id) REFERENCES equipment_connection (id);
ALTER TABLE equipment_connection_port ADD CONSTRAINT fk_equipment_connection_port_asset_id FOREIGN KEY (asset_id) REFERENCES equipment_asset (id);
ALTER TABLE equipment_requirement ADD CONSTRAINT fk_equipment_requirement_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_requirement ADD CONSTRAINT fk_equipment_requirement_program_id FOREIGN KEY (program_id) REFERENCES program (id);
ALTER TABLE equipment_reservation ADD CONSTRAINT fk_equipment_reservation_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE equipment_reservation ADD CONSTRAINT fk_equipment_reservation_program_id FOREIGN KEY (program_id) REFERENCES program (id);
ALTER TABLE equipment_reservation ADD CONSTRAINT fk_equipment_reservation_task_id FOREIGN KEY (task_id) REFERENCES task (id);
ALTER TABLE equipment_reservation ADD CONSTRAINT fk_equipment_reservation_source_change_set_id FOREIGN KEY (source_change_set_id) REFERENCES change_set (id);
ALTER TABLE equipment_reservation_asset ADD CONSTRAINT fk_equipment_reservation_asset_reservation_id FOREIGN KEY (reservation_id) REFERENCES equipment_reservation (id);
ALTER TABLE equipment_reservation_asset ADD CONSTRAINT fk_equipment_reservation_asset_asset_id FOREIGN KEY (asset_id) REFERENCES equipment_asset (id);
ALTER TABLE operational_dependency ADD CONSTRAINT fk_operational_dependency_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE operational_dependency ADD CONSTRAINT fk_operational_dependency_confirmed_by_member_id FOREIGN KEY (confirmed_by_member_id) REFERENCES member (id);
ALTER TABLE recovery_simulation ADD CONSTRAINT fk_recovery_simulation_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE recovery_simulation ADD CONSTRAINT fk_recovery_simulation_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id);
ALTER TABLE recovery_simulation ADD CONSTRAINT fk_recovery_simulation_created_by_member_id FOREIGN KEY (created_by_member_id) REFERENCES member (id);
ALTER TABLE safety_zone_profile ADD CONSTRAINT fk_safety_zone_profile_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE safety_zone_profile ADD CONSTRAINT fk_safety_zone_profile_zone_id FOREIGN KEY (zone_id) REFERENCES zone (id);
ALTER TABLE safety_zone_profile ADD CONSTRAINT fk_safety_zone_profile_layout_id FOREIGN KEY (layout_id) REFERENCES venue_layout (id);
ALTER TABLE safety_zone_profile ADD CONSTRAINT fk_safety_zone_profile_confirmed_by_member_id FOREIGN KEY (confirmed_by_member_id) REFERENCES member (id);
ALTER TABLE safety_camera ADD CONSTRAINT fk_safety_camera_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE safety_camera ADD CONSTRAINT fk_safety_camera_configured_by_member_id FOREIGN KEY (configured_by_member_id) REFERENCES member (id);
ALTER TABLE safety_observation ADD CONSTRAINT fk_safety_observation_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE safety_observation ADD CONSTRAINT fk_safety_observation_camera_id FOREIGN KEY (camera_id) REFERENCES safety_camera (id);
ALTER TABLE operational_situation ADD CONSTRAINT fk_operational_situation_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE operational_situation ADD CONSTRAINT fk_operational_situation_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id);
ALTER TABLE operational_situation ADD CONSTRAINT fk_operational_situation_confirmed_by_member_id FOREIGN KEY (confirmed_by_member_id) REFERENCES member (id);

-- 확정 상태값 CHECK (concert-contract-v9.json enums)
ALTER TABLE equipment_asset ADD CONSTRAINT chk_equipment_asset_technical_state CHECK (technical_state IN ('UNKNOWN', 'CHECK_REQUIRED', 'VERIFIED_USABLE', 'UNUSABLE'));
ALTER TABLE equipment_reservation ADD CONSTRAINT chk_equipment_reservation_status CHECK (status IN ('PLANNED', 'ACTIVE', 'RELEASED', 'CANCELED'));
ALTER TABLE operational_dependency ADD CONSTRAINT chk_operational_dependency_from_kind CHECK (from_kind IN ('PROGRAM', 'TASK'));
ALTER TABLE operational_dependency ADD CONSTRAINT chk_operational_dependency_to_kind CHECK (to_kind IN ('PROGRAM', 'TASK'));
ALTER TABLE recovery_simulation ADD CONSTRAINT chk_recovery_simulation_state CHECK (state IN ('READY', 'NEEDS_INFO', 'FAILED', 'STALE'));
ALTER TABLE safety_camera ADD CONSTRAINT chk_safety_camera_connection_state CHECK (connection_state IN ('DISABLED', 'CONNECTING', 'CONNECTED', 'OFFLINE', 'ERROR'));
ALTER TABLE safety_camera ADD CONSTRAINT chk_safety_camera_freshness CHECK (freshness IN ('UNKNOWN', 'FRESH', 'STALE', 'INVALID_MAPPING'));
ALTER TABLE safety_observation ADD CONSTRAINT chk_safety_observation_state CHECK (state IN ('RECEIVED', 'PROCESSING', 'READY', 'NEEDS_REVIEW', 'FAILED', 'EXPIRED'));
ALTER TABLE safety_observation ADD CONSTRAINT chk_safety_observation_freshness CHECK (freshness IN ('UNKNOWN', 'FRESH', 'STALE', 'INVALID_MAPPING'));
ALTER TABLE operational_situation ADD CONSTRAINT chk_operational_situation_primary_type CHECK (primary_type IN ('SCHEDULE_DELAY', 'STAFF_GAP', 'TASK_CONFLICT', 'EQUIPMENT_ANOMALY', 'PERFORMANCE_ORDER_CHANGE', 'AUDIENCE_SAFETY'));
ALTER TABLE operational_situation ADD CONSTRAINT chk_operational_situation_source_kind CHECK (source_kind IN ('FORMAL_REPORT', 'CHIEF_REQUEST', 'PLAN_CHECK', 'SAFETY_OBSERVATION'));

-- 값 범위 CHECK (BE13 "중첩 값의 계약" 수치 조건)
ALTER TABLE venue_layout ADD CONSTRAINT chk_venue_layout_size CHECK (width > 0 AND height > 0);
ALTER TABLE equipment_connection ADD CONSTRAINT chk_equipment_connection_not_self CHECK (from_asset_id <> to_asset_id);
ALTER TABLE equipment_reservation ADD CONSTRAINT chk_equipment_reservation_time CHECK (start_at < end_at);
ALTER TABLE operational_dependency ADD CONSTRAINT chk_operational_dependency_lag CHECK (lag_sec >= 0);
ALTER TABLE safety_zone_profile ADD CONSTRAINT chk_safety_zone_profile_area CHECK (effective_area_m2 IS NULL OR effective_area_m2 > 0);

-- 이슈당 대표 상황 1개 (포트당 활성 연결 1개는 equipment_connection_port의 UNIQUE)
CREATE UNIQUE INDEX uq_operational_situation_issue
    ON operational_situation (issue_id)
    WHERE issue_id IS NOT NULL;

-- 같은 장비 예약 조회, 최신 관측 조회, 30일 정리
CREATE INDEX ix_equipment_reservation_asset_asset ON equipment_reservation_asset (asset_id);
CREATE INDEX ix_recovery_simulation_event_calculated ON recovery_simulation (event_id, calculated_at);
CREATE INDEX ix_safety_observation_camera_received ON safety_observation (camera_id, received_at);
CREATE INDEX ix_safety_observation_expires ON safety_observation (metadata_expires_at);
