-- #372(V9-D62): 발행 때 PDF 평면도 쪽을 그린 그림을 안전 지도의 배경으로 함께 저장한다.
-- 입력 원본은 발행 7일 뒤 만료되므로 안전 화면(W05)의 배경은 이 표에서 읽는다. 평면도를 다시 저장(EXT11)해도 유지한다.
CREATE TABLE venue_layout_background (
    venue_layout_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    mime TEXT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    image BYTEA NOT NULL,
    source_ref TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_venue_layout_background_event_id UNIQUE (event_id),
    CONSTRAINT chk_venue_layout_background_mime CHECK (mime = 'image/png'),
    CONSTRAINT chk_venue_layout_background_size CHECK (width > 0 AND height > 0)
);
ALTER TABLE venue_layout_background ADD CONSTRAINT fk_venue_layout_background_layout_id
    FOREIGN KEY (venue_layout_id) REFERENCES venue_layout (id) ON DELETE CASCADE;
ALTER TABLE venue_layout_background ADD CONSTRAINT fk_venue_layout_background_event_id
    FOREIGN KEY (event_id) REFERENCES event (id);
