-- 보고 작성 당시 작성자의 참여 회차 (S15P31A202-321, 기획 V9-D12)
-- 보고 응답의 participationRevision이 필수인데 저장할 열이 없어 추가한다.
-- 보고 API가 아직 없어 기존 행이 없다. 행이 있으면 추정값으로 채우지 않고 마이그레이션을 실패시킨다.
ALTER TABLE report ADD COLUMN participation_revision BIGINT NOT NULL;
