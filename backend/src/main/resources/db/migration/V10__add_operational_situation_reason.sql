-- #359 V9-D48: 총괄이 운영 상황을 정정할 때 사유를 필수로 남긴다. 최종값·확인자·시각·사유만 보관한다.
ALTER TABLE operational_situation ADD COLUMN reason TEXT;
ALTER TABLE operational_situation ADD CONSTRAINT chk_operational_situation_confirmed_reason
    CHECK (confirmed_at IS NULL OR (reason IS NOT NULL AND btrim(reason) <> ''));
