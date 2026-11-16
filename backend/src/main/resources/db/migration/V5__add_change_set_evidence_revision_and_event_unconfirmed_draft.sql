-- 변경안 근거 이력과 미확정 계획 초안 (S15P31A202-332): 2테이블 / 11필드 / 3물리 FK
-- 기준: 오세진 정책 결정(2026-11-04, MR !134 댓글), docs/api-contract/drafts/v9-327/contract-decisions-v9.md(R2, Q3-A)
-- 두 테이블 모두 행사 종료 후 2년 보관한다. 정리 작업은 값을 쓰는 기능과 함께 구현한다.
-- 공식 사실 보호를 위해 FK에 CASCADE를 쓰지 않는다.

-- 1. 변경안 근거 이력(R2). 변경안 생성·편집·검토로 revision이 생길 때마다 당시 근거를 한 행씩 남긴다.
--    적용·폐기·미적용과 관계없이 보관하고, 한 번 저장한 근거는 고치거나 현재 근거로 덮어쓰지 않는다.
--    change_set.evidence_refs/simulation_refs는 현재 참조, 이 테이블은 revision별 당시 내용이다.
CREATE TABLE change_set_evidence_revision (
    change_set_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_change_set_evidence_revision PRIMARY KEY (change_set_id, revision),
    CONSTRAINT chk_change_set_evidence_revision_revision CHECK (revision >= 0),
    CONSTRAINT chk_change_set_evidence_revision_snapshot CHECK (jsonb_typeof(snapshot) = 'object')
);

ALTER TABLE change_set_evidence_revision ADD CONSTRAINT fk_change_set_evidence_revision_change_set_id
    FOREIGN KEY (change_set_id) REFERENCES change_set (id);

-- 저장한 근거는 고치지 않는다. 보관 기간이 지난 행의 삭제만 허용한다.
CREATE FUNCTION reject_change_set_evidence_revision_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'change_set_evidence_revision은 수정할 수 없습니다';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_change_set_evidence_revision_immutable
    BEFORE UPDATE ON change_set_evidence_revision
    FOR EACH ROW EXECUTE FUNCTION reject_change_set_evidence_revision_update();

-- 2. 미확정 계획 초안(Q3-A). 행사별 최신 공유본 1개만 둔다. 과거 초안은 남기지 않는다.
--    계획 확정 전에는 revision을 비교하는 조건부 갱신으로 편집하고(동시 편집 충돌은 사용자에게 알림),
--    확정 후에는 읽기만 허용한다(Service). AI 원본은 ai_job.result, 확정 사실은 기존 계획 테이블에 둔다.
--    source_job_id는 만료된 Job도 가리킬 수 있는 논리 참조라 FK를 걸지 않는다(change_set.source_job_id와 같다).
CREATE TABLE event_unconfirmed_draft (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    source_job_id UUID,
    payload JSONB NOT NULL,
    saved_by_member_id UUID NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_event_unconfirmed_draft_event_id UNIQUE (event_id),
    CONSTRAINT chk_event_unconfirmed_draft_revision CHECK (revision >= 0),
    CONSTRAINT chk_event_unconfirmed_draft_payload CHECK (jsonb_typeof(payload) = 'object')
);

ALTER TABLE event_unconfirmed_draft ADD CONSTRAINT fk_event_unconfirmed_draft_event_id
    FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE event_unconfirmed_draft ADD CONSTRAINT fk_event_unconfirmed_draft_saved_by_member_id
    FOREIGN KEY (saved_by_member_id) REFERENCES member (id);
