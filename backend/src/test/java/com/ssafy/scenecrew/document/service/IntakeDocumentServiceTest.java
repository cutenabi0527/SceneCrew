package com.ssafy.scenecrew.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ssafy.scenecrew.document.domain.IntakeDocument;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.storage.StorageKeys;
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import com.ssafy.scenecrew.support.TestRows;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(FileTestBeans.class)
class IntakeDocumentServiceTest {
    static final byte[] MD = "# 운영 안내\n입장은 10시부터".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private IntakeDocumentService service;

    @Autowired
    private FakeFileStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    private TestRows rows;
    private UUID eventId;
    private UUID chief;

    @BeforeEach
    void setUp() {
        storage.reset();
        rows = new TestRows(jdbc);
        eventId = rows.event("DRAFT");
        chief = rows.account();
        rows.member(eventId, chief, "CHIEF", true);
    }

    @Test
    void uploadStoresEachFileThenRegistersWithHashAndExpiry() {
        byte[] pdf = Pdfs.pages(2);

        List<IntakeDocument> saved = service.upload(eventId, chief,
                List.of(new DocumentUpload("안내.md", MD), new DocumentUpload("배치도.pdf", pdf)));

        assertThat(saved).hasSize(2);
        IntakeDocument md = saved.get(0);
        IntakeDocument pdfDoc = saved.get(1);
        assertThat(storage.objects()).containsOnlyKeys(StorageKeys.document(eventId, md.getId()),
                StorageKeys.document(eventId, pdfDoc.getId()));
        assertThat(row(md.getId())).containsEntry("mime", "text/markdown").containsEntry("page_count", null)
                .containsEntry("owner_account_id", chief).containsEntry("original_name", "안내.md")
                .containsEntry("sha256", FakeFileStorage.sha256(MD));
        assertThat(row(pdfDoc.getId())).containsEntry("mime", "application/pdf").containsEntry("page_count", 2);
        Instant expiresAt = instant(row(md.getId()).get("expires_at"));
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofDays(7).minusMinutes(1),
                Duration.ofDays(7));
    }

    @Test
    void newUploadExtendsExpiryOfWholeBundle() {
        UUID first = service.upload(eventId, chief, List.of(new DocumentUpload("a.md", MD))).get(0).getId();
        Instant shortened = Instant.now().plus(Duration.ofDays(1));
        jdbc.update("update intake_document set expires_at = ? where id = ?", Timestamp.from(shortened), first);

        UUID second = service.upload(eventId, chief, List.of(new DocumentUpload("b.md", MD))).get(0).getId();

        assertThat(row(first).get("expires_at")).isEqualTo(row(second).get("expires_at"));
    }

    @Test
    void bundleLimitsCountFilesAlreadyUploaded() {
        service.upload(eventId, chief, List.of(new DocumentUpload("a.md", MD), new DocumentUpload("b.md", MD)));

        assertCode(() -> service.upload(eventId, chief,
                List.of(new DocumentUpload("c.md", MD), new DocumentUpload("d.md", MD))), ErrorCode.PAYLOAD_TOO_LARGE);

        byte[] big = filled(15 * 1024 * 1024);
        UUID other = rows.event("DRAFT");
        rows.member(other, chief, "CHIEF", true);
        service.upload(other, chief, List.of(new DocumentUpload("big1.txt", big)));
        // 15MB + 15MB + 조금 > 30MB
        assertCode(() -> service.upload(other, chief,
                List.of(new DocumentUpload("big2.txt", big), new DocumentUpload("c.md", MD))),
                ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(storage.objects()).hasSize(3);
    }

    @Test
    void oneBadFileRejectsWholeRequestWithItsName() {
        assertThatThrownBy(() -> service.upload(eventId, chief,
                List.of(new DocumentUpload("a.md", MD), new DocumentUpload("이상한.hwp", MD))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getDetails()).containsEntry("fileName", "이상한.hwp");
                });

        assertThat(storage.objects()).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void storageFailureMidwayRemovesFilesAlreadyStored() {
        storage.failPutAfter(1);

        assertCode(() -> service.upload(eventId, chief,
                List.of(new DocumentUpload("a.md", MD), new DocumentUpload("b.md", MD))), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(storage.objects()).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void onlyCurrentChiefOfDraftEventWithoutRunningExtraction() {
        UUID worker = rows.account();
        rows.member(eventId, worker, "WORKER", true);
        assertCode(() -> service.upload(eventId, worker, List.of(new DocumentUpload("a.md", MD))), ErrorCode.FORBIDDEN);

        UUID extraction = runningExtraction();
        assertCode(() -> service.upload(eventId, chief, List.of(new DocumentUpload("a.md", MD))),
                ErrorCode.INVALID_STATE);
        jdbc.update("update ai_job set state = 'SUCCEEDED' where id = ?", extraction);

        rows.setEventState(eventId, "RECRUITING");
        assertCode(() -> service.upload(eventId, chief, List.of(new DocumentUpload("a.md", MD))),
                ErrorCode.INVALID_STATE);
        assertThat(storage.objects()).isEmpty();
    }

    @Test
    void ownerWhoIsStillChiefDownloadsOriginal() throws Exception {
        UUID id = service.upload(eventId, chief, List.of(new DocumentUpload("안내.md", MD))).get(0).getId();

        try (DocumentDownload download = service.download(id, chief)) {
            assertThat(download.originalName()).isEqualTo("안내.md");
            assertThat(download.mime()).isEqualTo("text/markdown");
            assertThat(download.content().readAllBytes()).isEqualTo(MD);
        }
    }

    @Test
    void downloadIsAllowedDuringExtractionButChangesAreNot() throws Exception {
        UUID id = service.upload(eventId, chief, List.of(new DocumentUpload("안내.md", MD))).get(0).getId();
        runningExtraction();

        try (DocumentDownload download = service.download(id, chief)) {
            assertThat(download.content().readAllBytes()).isEqualTo(MD);
        }
        assertCode(() -> service.deleteAll(eventId, chief), ErrorCode.INVALID_STATE);
        assertThat(row(id).get("storage_key")).isNotNull();
    }

    @Test
    void downloadIsClosedForOthersFormerChiefPublishedOrExpired() {
        UUID id = service.upload(eventId, chief, List.of(new DocumentUpload("안내.md", MD))).get(0).getId();

        assertCode(() -> service.download(id, rows.account()), ErrorCode.FORBIDDEN);
        assertCode(() -> service.download(UUID.randomUUID(), chief), ErrorCode.NOT_FOUND);

        jdbc.update("update intake_document set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertCode(() -> service.download(id, chief), ErrorCode.NOT_FOUND);

        rows.setEventState(eventId, "RECRUITING");
        assertCode(() -> service.download(id, chief), ErrorCode.INVALID_STATE);

        // 총괄을 넘기면 원본 소유자여도 받을 수 없다.
        jdbc.update("update member set active = false where event_id = ? and account_id = ?", eventId, chief);
        assertCode(() -> service.download(id, chief), ErrorCode.FORBIDDEN);
    }

    @Test
    void deleteAllRemovesMyOriginalsAndKeepsMetadata() {
        List<IntakeDocument> saved = service.upload(eventId, chief,
                List.of(new DocumentUpload("a.md", MD), new DocumentUpload("b.md", MD)));

        assertThat(service.deleteAll(eventId, chief)).isEqualTo(2);
        assertThat(service.deleteAll(eventId, chief)).isZero();

        assertThat(storage.objects()).isEmpty();
        saved.forEach(document -> assertThat(row(document.getId())).containsEntry("storage_key", null)
                .containsEntry("original_name", document.getOriginalName()));
        // 지운 뒤에는 묶음 한도에 세지 않는다.
        service.upload(eventId, chief, List.of(new DocumentUpload("c.md", MD), new DocumentUpload("d.md", MD),
                new DocumentUpload("e.md", MD)));
    }

    @Test
    void deleteFailureKeepsDbForRetry() {
        UUID id = service.upload(eventId, chief, List.of(new DocumentUpload("a.md", MD))).get(0).getId();
        storage.failDelete(true);

        assertCode(() -> service.deleteAll(eventId, chief), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(row(id).get("storage_key")).isNotNull();
    }

    @Test
    void cleanupRemovesOnlyExpiredOriginals() {
        List<IntakeDocument> saved = service.upload(eventId, chief,
                List.of(new DocumentUpload("old.md", MD), new DocumentUpload("new.md", MD)));
        UUID old = saved.get(0).getId();
        UUID fresh = saved.get(1).getId();
        jdbc.update("update intake_document set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), old);

        assertThat(service.cleanupExpired(Instant.now(), 100)).isGreaterThanOrEqualTo(1);

        assertThat(row(old).get("storage_key")).isNull();
        assertThat(row(fresh).get("storage_key")).isNotNull();
        assertThat(storage.objects()).containsOnlyKeys(StorageKeys.document(eventId, fresh));
    }

    private UUID runningExtraction() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into ai_job (id, event_id, kind, state, input_hash, context_revision, request_schema_version,
                                    input, attempt, execution_meta, auto_reanalysis_count, created_at, deadline_at)
                values (?, ?, 'EXTRACT_EVENT_INPUT', 'RUNNING', 'hash', 1, '1', '{}'::jsonb, 1, '{}'::jsonb, 0,
                        now(), now() + interval '10 minutes')
                """, id, eventId);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select * from intake_document where id = ?", id);
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from intake_document where event_id = ?", Long.class, eventId);
    }

    private static Instant instant(Object value) {
        return ((Timestamp) value).toInstant();
    }

    private static byte[] filled(int size) {
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) 'a');
        return content;
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
