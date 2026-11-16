-- v9 API 기준선(MR !128, S15P31A202-327) 반영 (S15P31A202-331): 3필드 추가, 테이블·FK 변화 없음
-- 기준: docs/planning/be/12-erd-contract.md, docs/api-contract/drafts/v9-327/review128-resolution.md

-- 1. 장비 현황 조회 허용 그룹(V9-D13, S15P31A202-329).
--    기존 그룹과 AI가 새로 만든 그룹은 false로 시작하고, 확인된 행사별 그룹만 BE 설정으로 true로 바꾼다.
--    그룹 이름으로 권한을 자동 부여하지 않는다.
ALTER TABLE work_group ADD COLUMN equipment_view_allowed BOOLEAN NOT NULL DEFAULT false;

-- 2. 변경안이 지금 참조하는 근거와 복구 계산(공개 API 066~071의 evidenceRefs, simulationRefs[]).
--    기존 행은 근거를 알 수 없어 NULL로 두고, 서버 검증을 거친 변경안은 배열(빈 배열 포함)로 저장한다.
--    revision별 근거 이력(change_set_evidence_revision)은 보관 기간이 정해진 뒤 따로 추가한다.
ALTER TABLE change_set ADD COLUMN evidence_refs JSONB;
ALTER TABLE change_set ADD COLUMN simulation_refs JSONB;
ALTER TABLE change_set ADD CONSTRAINT chk_change_set_evidence_refs_array
    CHECK (evidence_refs IS NULL OR jsonb_typeof(evidence_refs) = 'array');
ALTER TABLE change_set ADD CONSTRAINT chk_change_set_simulation_refs_array
    CHECK (simulation_refs IS NULL OR jsonb_typeof(simulation_refs) = 'array');
