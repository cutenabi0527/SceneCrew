-- 씬크루 초기 업무 스키마 (ERD v7.1: 22테이블 / 239필드 / 52물리 FK)
-- 기준: docs/planning/be/12-erd-contract.md, 03-data-dictionary-a.md, 03-data-dictionary-b.md
-- 공식 사실 보호를 위해 FK에 CASCADE를 쓰지 않는다. 같은 행사 소속 검사는 Service에서 한다.
-- 논리 참조(change_set.source_job_id 등)는 FK로 강제하지 않는다.

CREATE TABLE account (
    id UUID PRIMARY KEY,
    display_name VARCHAR(20) NOT NULL,
    status TEXT NOT NULL,
    signup_completed BOOLEAN NOT NULL,
    token_version BIGINT NOT NULL,
    consent JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE auth_identity (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    provider TEXT NOT NULL,
    subject TEXT NOT NULL,
    password_hash TEXT,
    email TEXT,
    email_verified BOOLEAN NOT NULL,
    CONSTRAINT uq_auth_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_auth_identity_account_id_provider UNIQUE (account_id, provider)
);

CREATE TABLE event (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    date DATE,
    prep_start_at TIMESTAMPTZ,
    open_at TIMESTAMPTZ,
    close_at TIMESTAMPTZ,
    teardown_end_at TIMESTAMPTZ,
    location_name TEXT,
    address TEXT,
    total_capacity BIGINT,
    expected_visitors BIGINT,
    peak_visitors BIGINT,
    desired_reserve_count BIGINT NOT NULL DEFAULT 0,
    confirmed_input JSONB NOT NULL DEFAULT '{}'::JSONB,
    instructions TEXT NOT NULL DEFAULT '',
    state VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL,
    invite_token_hash TEXT,
    last_input_modified_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    end_reason TEXT
);

CREATE TABLE member (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    account_id UUID NOT NULL,
    work_group_id UUID,
    crew_no BIGINT NOT NULL,
    event_role VARCHAR(20) NOT NULL,
    display_name TEXT NOT NULL,
    available BOOLEAN NOT NULL,
    is_reserve BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    participation_revision BIGINT NOT NULL,
    CONSTRAINT uq_member_event_id_account_id UNIQUE (event_id, account_id),
    CONSTRAINT uq_member_event_id_crew_no UNIQUE (event_id, crew_no)
);

CREATE TABLE work_group (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    name TEXT NOT NULL,
    instructions TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT uq_work_group_event_id_name UNIQUE (event_id, name)
);

CREATE TABLE zone (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    aliases JSONB NOT NULL,
    CONSTRAINT uq_zone_event_id_name UNIQUE (event_id, name)
);

CREATE TABLE program (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    zone_id UUID,
    name TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE task (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    work_group_id UUID NOT NULL,
    bundle_key TEXT NOT NULL,
    assignee_id UUID,
    zone_id UUID NOT NULL,
    program_id UUID,
    source_issue_id UUID,
    title TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    steps JSONB NOT NULL,
    checklist JSONB NOT NULL,
    status VARCHAR(24) NOT NULL,
    first_assigned_at TIMESTAMPTZ,
    actual_started_at TIMESTAMPTZ,
    actual_ended_at TIMESTAMPTZ
);

CREATE TABLE intake_document (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    original_name TEXT NOT NULL,
    mime TEXT NOT NULL,
    bytes BIGINT NOT NULL,
    page_count INTEGER,
    sha256 TEXT NOT NULL,
    storage_key TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE audio_asset (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    uploader_member_id UUID NOT NULL,
    storage_key TEXT,
    mime TEXT NOT NULL,
    bytes BIGINT NOT NULL,
    duration_sec NUMERIC NOT NULL,
    transcript TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE issue (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    work_group_id UUID NOT NULL,
    task_id UUID,
    zone_id UUID NOT NULL,
    title TEXT NOT NULL,
    reporter_member_id UUID NOT NULL,
    urgency VARCHAR(12) NOT NULL,
    status VARCHAR(32) NOT NULL,
    related_issue_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ
);

CREATE TABLE report (
    id UUID PRIMARY KEY,
    issue_id UUID NOT NULL,
    author_member_id UUID NOT NULL,
    kind VARCHAR(20) NOT NULL,
    text VARCHAR(1000) NOT NULL,
    normalized_text TEXT NOT NULL,
    audio_id UUID,
    quick_template_key TEXT,
    analysis JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_report_audio_id UNIQUE (audio_id)
);

CREATE TABLE change_set (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    issue_id UUID,
    source_job_id UUID,
    created_by_member_id UUID,
    mode VARCHAR(12) NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    base_revision BIGINT NOT NULL,
    operations JSONB NOT NULL,
    diff JSONB,
    preview_hash TEXT,
    rationale TEXT NOT NULL,
    approved_by_member_id UUID,
    applied_at TIMESTAMPTZ
);

CREATE TABLE operation_log (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    type TEXT NOT NULL,
    actor_member_id UUID,
    resource_type TEXT NOT NULL,
    resource_id UUID NOT NULL,
    changes JSONB NOT NULL,
    reason TEXT,
    change_set_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    work_group_id UUID,
    sender_member_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    client_message_id TEXT NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    participation_revision BIGINT NOT NULL,
    event_id UUID NOT NULL,
    change_set_id UUID,
    task_id UUID,
    acknowledgment_id UUID,
    type TEXT NOT NULL,
    payload JSONB NOT NULL,
    dedupe_key TEXT NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_dedupe_key UNIQUE (dedupe_key)
);

CREATE TABLE notification_delivery (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    device_token_id UUID NOT NULL,
    attempt BIGINT NOT NULL,
    state TEXT NOT NULL,
    provider_message_id TEXT,
    last_error TEXT,
    sent_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    CONSTRAINT uq_notification_delivery_notification_device UNIQUE (notification_id, device_token_id)
);

CREATE TABLE change_acknowledgment (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    change_set_id UUID NOT NULL,
    member_id UUID NOT NULL,
    participation_revision BIGINT NOT NULL,
    targets JSONB NOT NULL,
    targets_hash TEXT NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_targets JSONB,
    CONSTRAINT uq_change_ack_change_set_member_revision UNIQUE (change_set_id, member_id, participation_revision)
);

CREATE TABLE device_token (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    device_id TEXT NOT NULL,
    fcm_token TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_device_token_member_id_device_id UNIQUE (member_id, device_id)
);

CREATE TABLE ai_job (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    requested_by_member_id UUID,
    kind TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    input_hash TEXT NOT NULL,
    context_revision BIGINT NOT NULL,
    request_schema_version TEXT NOT NULL,
    result_schema_version TEXT,
    model_version TEXT,
    input JSONB NOT NULL,
    result JSONB,
    error JSONB,
    attempt BIGINT NOT NULL,
    execution_meta JSONB NOT NULL,
    trigger_key TEXT,
    analysis_flow_id TEXT,
    auto_reanalysis_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    first_started_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    attempts BIGINT NOT NULL,
    lease_until TIMESTAMPTZ,
    processed_at TIMESTAMPTZ
);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    actor_account_id UUID NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    event_id UUID,
    actor_member_id UUID,
    participation_revision BIGINT,
    result_type TEXT NOT NULL,
    result_refs JSONB NOT NULL,
    http_status INTEGER NOT NULL,
    receipt JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    replay_expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_record_actor_operation_key UNIQUE (actor_account_id, operation, idempotency_key)
);

-- 물리 FK 52개
ALTER TABLE auth_identity ADD CONSTRAINT fk_auth_identity_account_id FOREIGN KEY (account_id) REFERENCES account (id);
ALTER TABLE member ADD CONSTRAINT fk_member_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE member ADD CONSTRAINT fk_member_account_id FOREIGN KEY (account_id) REFERENCES account (id);
ALTER TABLE member ADD CONSTRAINT fk_member_work_group_id FOREIGN KEY (work_group_id) REFERENCES work_group (id);
ALTER TABLE work_group ADD CONSTRAINT fk_work_group_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE zone ADD CONSTRAINT fk_zone_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE program ADD CONSTRAINT fk_program_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE program ADD CONSTRAINT fk_program_zone_id FOREIGN KEY (zone_id) REFERENCES zone (id);
ALTER TABLE task ADD CONSTRAINT fk_task_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE task ADD CONSTRAINT fk_task_work_group_id FOREIGN KEY (work_group_id) REFERENCES work_group (id);
ALTER TABLE task ADD CONSTRAINT fk_task_assignee_id FOREIGN KEY (assignee_id) REFERENCES member (id);
ALTER TABLE task ADD CONSTRAINT fk_task_zone_id FOREIGN KEY (zone_id) REFERENCES zone (id);
ALTER TABLE task ADD CONSTRAINT fk_task_program_id FOREIGN KEY (program_id) REFERENCES program (id);
ALTER TABLE task ADD CONSTRAINT fk_task_source_issue_id FOREIGN KEY (source_issue_id) REFERENCES issue (id);
ALTER TABLE intake_document ADD CONSTRAINT fk_intake_document_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE intake_document ADD CONSTRAINT fk_intake_document_owner_account_id FOREIGN KEY (owner_account_id) REFERENCES account (id);
ALTER TABLE audio_asset ADD CONSTRAINT fk_audio_asset_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE audio_asset ADD CONSTRAINT fk_audio_asset_uploader_member_id FOREIGN KEY (uploader_member_id) REFERENCES member (id);
ALTER TABLE issue ADD CONSTRAINT fk_issue_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE issue ADD CONSTRAINT fk_issue_work_group_id FOREIGN KEY (work_group_id) REFERENCES work_group (id);
ALTER TABLE issue ADD CONSTRAINT fk_issue_task_id FOREIGN KEY (task_id) REFERENCES task (id);
ALTER TABLE issue ADD CONSTRAINT fk_issue_zone_id FOREIGN KEY (zone_id) REFERENCES zone (id);
ALTER TABLE issue ADD CONSTRAINT fk_issue_reporter_member_id FOREIGN KEY (reporter_member_id) REFERENCES member (id);
ALTER TABLE issue ADD CONSTRAINT fk_issue_related_issue_id FOREIGN KEY (related_issue_id) REFERENCES issue (id);
ALTER TABLE report ADD CONSTRAINT fk_report_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id);
ALTER TABLE report ADD CONSTRAINT fk_report_author_member_id FOREIGN KEY (author_member_id) REFERENCES member (id);
ALTER TABLE report ADD CONSTRAINT fk_report_audio_id FOREIGN KEY (audio_id) REFERENCES audio_asset (id);
ALTER TABLE change_set ADD CONSTRAINT fk_change_set_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE change_set ADD CONSTRAINT fk_change_set_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id);
ALTER TABLE change_set ADD CONSTRAINT fk_change_set_created_by_member_id FOREIGN KEY (created_by_member_id) REFERENCES member (id);
ALTER TABLE change_set ADD CONSTRAINT fk_change_set_approved_by_member_id FOREIGN KEY (approved_by_member_id) REFERENCES member (id);
ALTER TABLE operation_log ADD CONSTRAINT fk_operation_log_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE operation_log ADD CONSTRAINT fk_operation_log_actor_member_id FOREIGN KEY (actor_member_id) REFERENCES member (id);
ALTER TABLE operation_log ADD CONSTRAINT fk_operation_log_change_set_id FOREIGN KEY (change_set_id) REFERENCES change_set (id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_work_group_id FOREIGN KEY (work_group_id) REFERENCES work_group (id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_sender_member_id FOREIGN KEY (sender_member_id) REFERENCES member (id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_member_id FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_change_set_id FOREIGN KEY (change_set_id) REFERENCES change_set (id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_task_id FOREIGN KEY (task_id) REFERENCES task (id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_acknowledgment_id FOREIGN KEY (acknowledgment_id) REFERENCES change_acknowledgment (id);
ALTER TABLE notification_delivery ADD CONSTRAINT fk_notification_delivery_notification_id FOREIGN KEY (notification_id) REFERENCES notification (id);
ALTER TABLE notification_delivery ADD CONSTRAINT fk_notification_delivery_device_token_id FOREIGN KEY (device_token_id) REFERENCES device_token (id);
ALTER TABLE change_acknowledgment ADD CONSTRAINT fk_change_acknowledgment_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE change_acknowledgment ADD CONSTRAINT fk_change_acknowledgment_change_set_id FOREIGN KEY (change_set_id) REFERENCES change_set (id);
ALTER TABLE change_acknowledgment ADD CONSTRAINT fk_change_acknowledgment_member_id FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE device_token ADD CONSTRAINT fk_device_token_member_id FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE ai_job ADD CONSTRAINT fk_ai_job_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE ai_job ADD CONSTRAINT fk_ai_job_requested_by_member_id FOREIGN KEY (requested_by_member_id) REFERENCES member (id);
ALTER TABLE outbox_event ADD CONSTRAINT fk_outbox_event_event_id FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE idempotency_record ADD CONSTRAINT fk_idempotency_record_actor_account_id FOREIGN KEY (actor_account_id) REFERENCES account (id);

-- 확정 상태값 CHECK (12-erd-contract.md)
ALTER TABLE member ADD CONSTRAINT chk_member_event_role CHECK (event_role IN ('CHIEF', 'WORKER'));
ALTER TABLE event ADD CONSTRAINT chk_event_state CHECK (state IN ('DRAFT', 'RECRUITING', 'LIVE', 'ENDED'));
ALTER TABLE task ADD CONSTRAINT chk_task_status CHECK (status IN ('WAITING', 'IN_PROGRESS', 'DONE', 'CANCELED'));
ALTER TABLE issue ADD CONSTRAINT chk_issue_status CHECK (status IN ('RECEIVED', 'RESPONDING', 'RESOLUTION_REPORTED', 'CLOSED'));
ALTER TABLE issue ADD CONSTRAINT chk_issue_urgency CHECK (urgency IN ('NORMAL', 'URGENT'));
ALTER TABLE report ADD CONSTRAINT chk_report_kind CHECK (kind IN ('INITIAL', 'FOLLOWUP', 'RESOLUTION'));
ALTER TABLE change_set ADD CONSTRAINT chk_change_set_mode CHECK (mode IN ('MANUAL', 'AI'));
ALTER TABLE change_set ADD CONSTRAINT chk_change_set_status CHECK (status IN ('DRAFT', 'READY', 'APPLIED', 'DISCARDED'));
ALTER TABLE ai_job ADD CONSTRAINT chk_ai_job_state CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'STALE_CONTEXT'));

-- 행사별 활성 CHIEF 최대 1명 (최소 1명은 Service 트랜잭션)
CREATE UNIQUE INDEX uq_member_active_chief
    ON member (event_id)
    WHERE event_role = 'CHIEF' AND active = TRUE;

-- LOCAL 계정: 이메일과 비밀번호 해시 필수, 정규화 이메일 유일 (03-data-dictionary-a.md)
ALTER TABLE auth_identity ADD CONSTRAINT chk_local_email_hash_required
    CHECK (provider <> 'LOCAL' OR (email IS NOT NULL AND BTRIM(email) <> '' AND password_hash IS NOT NULL));
CREATE UNIQUE INDEX uq_local_email_normalized
    ON auth_identity (LOWER(BTRIM(email)))
    WHERE provider = 'LOCAL';

-- 채팅: 채널별 순번과 발신자 메시지 중복 방지 (전체 채널은 work_group_id IS NULL)
CREATE UNIQUE INDEX uq_chat_message_event_sequence
    ON chat_message (event_id, sequence)
    WHERE work_group_id IS NULL;
CREATE UNIQUE INDEX uq_chat_message_group_sequence
    ON chat_message (event_id, work_group_id, sequence)
    WHERE work_group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_chat_message_event_client_message
    ON chat_message (event_id, sender_member_id, client_message_id)
    WHERE work_group_id IS NULL;
CREATE UNIQUE INDEX uq_chat_message_group_client_message
    ON chat_message (event_id, work_group_id, sender_member_id, client_message_id)
    WHERE work_group_id IS NOT NULL;
