package com.ssafy.scenecrew.safety.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ssafy.scenecrew.document.service.PdfPageImage;
import com.ssafy.scenecrew.global.storage.OriginalFiles;
import com.ssafy.scenecrew.global.storage.StoredFile;

/**
 * 안전 지도의 평면도 배경 그림(V9-D62, S15P31A202-372).
 *
 * 발행 때 미확정 초안의 평면도 근거가 입력 PDF의 한 쪽(sourceRef {@code document:<id>#page=<n>})이면, 그 쪽을 PNG로 그려
 * venue_layout_background에 저장한다. 입력 원본은 발행 7일 뒤 만료되므로 안전 화면은 이 그림을 쓴다.
 *
 * <ul>
 *   <li>발행 트랜잭션이 커밋된 뒤에 그린다. 그리는 동안 행사 잠금을 잡지 않고, 실패해도 발행은 그대로다(로그만 남김).</li>
 *   <li>같은 행사의 만료되지 않은 PDF 원본만 쓴다. 다른 근거(직접 입력·구역 이름 기본 배치)는 배경이 없다.</li>
 * </ul>
 */
@Component
public class VenueLayoutBackgrounds {
    private static final Logger log = LoggerFactory.getLogger(VenueLayoutBackgrounds.class);
    private static final Pattern SOURCE = Pattern.compile(
            "document:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})#page=([1-9][0-9]{0,3})");

    private final JdbcTemplate jdbc;
    private final OriginalFiles originals;

    public VenueLayoutBackgrounds(JdbcTemplate jdbc, OriginalFiles originals) {
        this.jdbc = jdbc;
        this.originals = originals;
    }

    /** 지금 트랜잭션이 커밋되면 배경을 저장한다. 트랜잭션 밖이면 바로 저장한다. */
    public void saveAfterCommit(UUID eventId, UUID layoutId, String sourceRef) {
        if (sourceRef == null || !SOURCE.matcher(sourceRef).matches()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            save(eventId, layoutId, sourceRef);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                save(eventId, layoutId, sourceRef);
            }
        });
    }

    /** 배경을 그려 저장하고 저장했는지 돌려준다. 예외를 밖으로 내지 않는다. */
    boolean save(UUID eventId, UUID layoutId, String sourceRef) {
        Matcher m = SOURCE.matcher(sourceRef);
        if (!m.matches()) return false;
        UUID documentId = UUID.fromString(m.group(1).toLowerCase(java.util.Locale.ROOT));
        int page = Integer.parseInt(m.group(2));
        try {
            String key = jdbc.query("""
                    SELECT storage_key FROM intake_document
                     WHERE id = ? AND event_id = ? AND mime = 'application/pdf' AND storage_key IS NOT NULL AND expires_at > now()""",
                    (r, n) -> r.getString(1), documentId, eventId).stream().findFirst().orElse(null);
            if (key == null) {
                log.info("평면도 배경의 원본 PDF가 없거나 만료돼 배경 없이 둡니다: eventId={}, documentId={}", eventId, documentId);
                return false;
            }
            byte[] pdf;
            try (StoredFile file = originals.get(key)) {
                pdf = file.content().readAllBytes();
            }
            PdfPageImage.Rendered image = PdfPageImage.render(pdf, page);
            PdfPageImage.Crop crop = image.crop();
            int saved = jdbc.update("""
                    INSERT INTO venue_layout_background (venue_layout_id, event_id, mime, width, height, image, source_ref, created_at,
                                                         crop_x, crop_y, crop_width, crop_height)
                    VALUES (?, ?, 'image/png', ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    layoutId, eventId, image.width(), image.height(), image.png(), sourceRef,
                    Instant.now().atOffset(ZoneOffset.UTC), crop == null ? null : crop.x(), crop == null ? null : crop.y(),
                    crop == null ? null : crop.width(), crop == null ? null : crop.height());
            return saved == 1;
        } catch (Exception e) {
            log.warn("평면도 배경을 저장하지 못했습니다(발행은 유지): eventId={}, sourceRef={}", eventId, sourceRef, e);
            return false;
        }
    }

    /** 저장된 배경 그림과 평면도 영역 */
    public record Background(byte[] png, PdfPageImage.Crop crop) {
    }

    /** 저장된 배경. 없으면 null */
    Background read(UUID eventId) {
        return jdbc.query("SELECT image, crop_x, crop_y, crop_width, crop_height FROM venue_layout_background WHERE event_id = ?",
                (r, n) -> {
                    double w = r.getDouble("crop_width");
                    PdfPageImage.Crop crop = r.wasNull() ? null
                            : new PdfPageImage.Crop(r.getDouble("crop_x"), r.getDouble("crop_y"), w, r.getDouble("crop_height"));
                    return new Background(r.getBytes("image"), crop);
                }, eventId).stream().findFirst().orElse(null);
    }
}
