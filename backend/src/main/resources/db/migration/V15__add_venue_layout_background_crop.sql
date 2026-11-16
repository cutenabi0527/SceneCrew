-- #372(V9-D62 후속): 평면도 배경 그림 안에서 실제 평면도(쪽 안의 가장 큰 그림)가 놓인 영역(0~1, 왼쪽 위 기준).
-- 화면은 쪽 전체 대신 이 영역만 크게 보인다. 평면도로 보이는 그림이 없으면 null이다.
ALTER TABLE venue_layout_background ADD COLUMN crop_x DOUBLE PRECISION;
ALTER TABLE venue_layout_background ADD COLUMN crop_y DOUBLE PRECISION;
ALTER TABLE venue_layout_background ADD COLUMN crop_width DOUBLE PRECISION;
ALTER TABLE venue_layout_background ADD COLUMN crop_height DOUBLE PRECISION;
