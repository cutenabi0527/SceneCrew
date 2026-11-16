-- 장비 자동 관측 종류와 연결 담당 업무(#395, V9-D59·D60).
-- auto_observation_kind: 웹 연결로 자동 수집할 수 있는 장비 종류. 지금은 QLXD4(무선 수신기)만이고, 없으면 사람이 상태를 기록하는 장비다.
-- link_task_id: 이 장비의 연결 담당 업무("장비 자동 수집 연결"). 카메라 safety_camera.link_task_id와 같은 방식이다.

ALTER TABLE equipment_asset ADD COLUMN auto_observation_kind VARCHAR(16);
ALTER TABLE equipment_asset ADD COLUMN link_task_id UUID;

ALTER TABLE equipment_asset ADD CONSTRAINT chk_equipment_asset_auto_observation_kind CHECK (auto_observation_kind IN ('QLXD4'));
ALTER TABLE equipment_asset ADD CONSTRAINT fk_equipment_asset_link_task_id FOREIGN KEY (link_task_id) REFERENCES task (id);
CREATE INDEX ix_equipment_asset_link_task ON equipment_asset (link_task_id);
