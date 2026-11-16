-- #381(V9-D50): 보고 긴급도(NORMAL/URGENT) 기능을 폐지한다. 이슈의 긴급도 컬럼과 CHECK 제약을 지운다.
ALTER TABLE issue DROP CONSTRAINT IF EXISTS chk_issue_urgency;
ALTER TABLE issue DROP COLUMN IF EXISTS urgency;
