package com.ssafy.scenecrew.issue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
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

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.storage.AudioProbeException;
import com.ssafy.scenecrew.global.storage.InvalidAudioException;
import com.ssafy.scenecrew.global.storage.StorageKeys;
import com.ssafy.scenecrew.issue.service.AudioAssetService.UploadedAudio;
import com.ssafy.scenecrew.support.FakeAudioProbe;
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import com.ssafy.scenecrew.support.TestRows;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(FileTestBeans.class)
class AudioAssetServiceTest {
    /** WebM(EBML) 머리 4바이트 */
    static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4};
    /** m4a: 5~8번째 바이트가 ftyp */
    static final byte[] M4A = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};

    @Autowired
    private AudioAssetService service;

    @Autowired
    private FakeFileStorage storage;

    @Autowired
    private FakeAudioProbe probe;

    @Autowired
    private JdbcTemplate jdbc;

    private TestRows rows;
    private UUID eventId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        storage.reset();
        probe.reset();
        rows = new TestRows(jdbc);
        eventId = rows.event("LIVE");
        memberId = rows.member(eventId, rows.account(), "WORKER", true);
    }

    @Test
    void uploadStoresOriginalThenRegistersMeasuredLength() {
        probe.answer(content -> FakeAudioProbe.webm("12.345"));

        UploadedAudio uploaded = service.upload(eventId, memberId, WEBM);

        UUID id = uploaded.asset().getId();
        String key = StorageKeys.audio(eventId, id);
        assertThat(uploaded.sha256()).isEqualTo(FakeFileStorage.sha256(WEBM));
        assertThat(storage.objects()).containsOnlyKeys(key);
        assertThat(storage.objects().get(key).contentType()).isEqualTo("audio/webm");
        assertThat(row(id)).containsEntry("storage_key", key).containsEntry("mime", "audio/webm")
                .containsEntry("bytes", (long) WEBM.length).containsEntry("uploader_member_id", memberId);
        assertThat(row(id).get("duration_sec").toString()).isEqualTo("12.345");
    }

    @Test
    void m4aIsDetectedFromContentNotFromClient() {
        probe.answer(content -> FakeAudioProbe.m4a("3"));

        UploadedAudio uploaded = service.upload(eventId, memberId, M4A);

        assertThat(uploaded.asset().getMime()).isEqualTo("audio/mp4");
    }

    @Test
    void callbackRunsInSameTransactionAndItsFailureRemovesUpload() {
        String hash = service.upload(eventId, memberId, WEBM, uploaded -> uploaded.sha256());
        assertThat(hash).isEqualTo(FakeFileStorage.sha256(WEBM));

        assertThatThrownBy(() -> service.upload(eventId, memberId, WEBM, uploaded -> {
            throw new IllegalStateException("전사 작업 접수 실패 시험");
        })).isInstanceOf(IllegalStateException.class);

        // 콜백이 실패하면 음성 행도 저장되지 않고, 올린 원본도 지워진다.
        assertThat(count()).isEqualTo(1);
        assertThat(storage.objects()).hasSize(1);
    }

    @Test
    void tooLargeOrTooLongOrUnknownFormatIsRejectedBeforeStoring() {
        byte[] large = Arrays.copyOf(WEBM, (int) AudioAssetService.MAX_BYTES + 1);
        assertCode(() -> service.upload(eventId, memberId, large), ErrorCode.PAYLOAD_TOO_LARGE);
        assertCode(() -> service.upload(eventId, memberId, new byte[0]), ErrorCode.INVALID_REQUEST);
        assertCode(() -> service.upload(eventId, memberId, "ID3 mp3".getBytes()), ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        probe.answer(content -> FakeAudioProbe.webm("30.501"));
        assertCode(() -> service.upload(eventId, memberId, WEBM), ErrorCode.PAYLOAD_TOO_LARGE);

        // 머리는 WebM인데 ffprobe가 mp4로 읽으면 거절한다.
        probe.answer(content -> FakeAudioProbe.m4a("3"));
        assertCode(() -> service.upload(eventId, memberId, WEBM), ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        probe.answer(content -> {
            throw new InvalidAudioException("깨진 파일");
        });
        assertCode(() -> service.upload(eventId, memberId, WEBM), ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        assertThat(storage.objects()).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void thirtySecondsWithEncoderPaddingIsAccepted() {
        probe.answer(content -> FakeAudioProbe.webm("30.02"));

        assertThat(service.upload(eventId, memberId, WEBM).asset().getDurationSec()).isEqualByComparingTo("30.02");
    }

    @Test
    void probeOrStorageOutageIsServiceUnavailable() {
        probe.answer(content -> {
            throw new AudioProbeException("ffprobe 없음");
        });
        assertCode(() -> service.upload(eventId, memberId, WEBM), ErrorCode.SERVICE_UNAVAILABLE);

        probe.reset();
        storage.failPutAfter(0);
        assertCode(() -> service.upload(eventId, memberId, WEBM), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(count()).isZero();
    }

    @Test
    void onlyActiveMemberOfThatEventCanUploadBeforeEventEnds() {
        UUID otherEvent = rows.event("LIVE");
        assertCode(() -> service.upload(otherEvent, memberId, WEBM), ErrorCode.FORBIDDEN);

        UUID inactive = rows.member(eventId, rows.account(), "WORKER", false);
        assertCode(() -> service.upload(eventId, inactive, WEBM), ErrorCode.FORBIDDEN);

        rows.setEventState(eventId, "ENDED");
        assertCode(() -> service.upload(eventId, memberId, WEBM), ErrorCode.INVALID_STATE);
        assertThat(storage.objects()).isEmpty();
    }

    @Test
    void uploaderDeletesOriginalAndTranscriptButKeepsMetadata() {
        UUID id = service.upload(eventId, memberId, WEBM).asset().getId();
        jdbc.update("update audio_asset set transcript = '임시 전사문' where id = ?", id);
        UUID other = rows.member(eventId, rows.account(), "WORKER", true);

        assertCode(() -> service.delete(id, other), ErrorCode.FORBIDDEN);
        service.delete(id, memberId);
        service.delete(id, memberId); // 이미 지운 것도 성공

        assertThat(storage.objects()).isEmpty();
        assertThat(row(id)).containsEntry("storage_key", null).containsEntry("transcript", null)
                .containsEntry("mime", "audio/webm");
    }

    @Test
    void storageDeleteFailureKeepsDbForRetry() {
        UUID id = service.upload(eventId, memberId, WEBM).asset().getId();
        storage.failDelete(true);

        assertCode(() -> service.delete(id, memberId), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(row(id).get("storage_key")).isNotNull();
    }

    @Test
    void cleanupRemovesOriginalsOlderThan24Hours() {
        UUID old = service.upload(eventId, memberId, WEBM).asset().getId();
        UUID recent = service.upload(eventId, memberId, WEBM).asset().getId();
        jdbc.update("update audio_asset set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(AudioAssetService.RETENTION).minusSeconds(60)), old);

        int removed = service.cleanupExpired(Instant.now(), 100);

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(row(old).get("storage_key")).isNull();
        assertThat(row(recent).get("storage_key")).isNotNull();
        assertThat(storage.objects()).containsOnlyKeys(StorageKeys.audio(eventId, recent));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select * from audio_asset where id = ?", id);
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from audio_asset where event_id = ?", Long.class, eventId);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
