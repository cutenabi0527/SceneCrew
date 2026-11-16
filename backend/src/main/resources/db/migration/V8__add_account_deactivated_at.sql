-- #334 V9-D39: 탈퇴가 곧 개인정보 삭제 요청이다. 탈퇴 시각을 남기고, 접수 번호·처리 기한(+24시간)은 이 값과 계정 ID로 계산한다.
-- 계정 행은 Member·보고·채팅이 가리키므로 지우지 않고 익명화한다. 익명화가 끝나면 로그인 수단(auth_identity)이 남지 않는다.
ALTER TABLE account ADD COLUMN deactivated_at TIMESTAMPTZ;
