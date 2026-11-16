-- 카메라 웹 연결(#393, V9-D58·D60).
-- 웹으로 연결하는 카메라는 연결 이름(source_binding_ref) 없이 만들 수 있고, 연결 전에는 UNLINKED(등록 안됨)이다.
-- 입력 종류: BROWSER(웹 연결 중) · COLLECTOR(서버 설정 경로, 예비) · NULL(아직 입력 없음). 한 카메라는 둘 중 하나만 쓴다.
-- 웹 연결 정보(link_id·link_member_id·linked_at)는 함께 있거나 함께 없다. link_id는 연결한 브라우저만 아는 값이다.
-- link_task_id는 이 카메라의 연결 담당 업무다(장비 equipment_asset.link_task_id와 같은 방식).

ALTER TABLE safety_camera ALTER COLUMN source_binding_ref DROP NOT NULL;
ALTER TABLE safety_camera ADD COLUMN input_kind VARCHAR(16);
ALTER TABLE safety_camera ADD COLUMN link_id UUID;
ALTER TABLE safety_camera ADD COLUMN link_member_id UUID;
ALTER TABLE safety_camera ADD COLUMN linked_at TIMESTAMPTZ;
ALTER TABLE safety_camera ADD COLUMN link_task_id UUID;

UPDATE safety_camera SET input_kind = 'COLLECTOR';

ALTER TABLE safety_camera DROP CONSTRAINT chk_safety_camera_connection_state;
ALTER TABLE safety_camera ADD CONSTRAINT chk_safety_camera_connection_state
    CHECK (connection_state IN ('UNLINKED', 'DISABLED', 'CONNECTING', 'CONNECTED', 'OFFLINE', 'ERROR'));
ALTER TABLE safety_camera ADD CONSTRAINT chk_safety_camera_input_kind CHECK (input_kind IN ('BROWSER', 'COLLECTOR'));
ALTER TABLE safety_camera ADD CONSTRAINT chk_safety_camera_link
    CHECK ((link_id IS NULL AND link_member_id IS NULL AND linked_at IS NULL)
        OR (link_id IS NOT NULL AND link_member_id IS NOT NULL AND linked_at IS NOT NULL AND input_kind = 'BROWSER'));
ALTER TABLE safety_camera ADD CONSTRAINT chk_safety_camera_collector_binding
    CHECK (input_kind <> 'COLLECTOR' OR source_binding_ref IS NOT NULL);
ALTER TABLE safety_camera ADD CONSTRAINT fk_safety_camera_link_member_id FOREIGN KEY (link_member_id) REFERENCES member (id);
ALTER TABLE safety_camera ADD CONSTRAINT fk_safety_camera_link_task_id FOREIGN KEY (link_task_id) REFERENCES task (id);
CREATE INDEX ix_safety_camera_link_task ON safety_camera (link_task_id);
