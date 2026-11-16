package com.ssafy.scenecrew.issue.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.storage.AudioProbe;
import com.ssafy.scenecrew.global.storage.AudioProbeException;
import com.ssafy.scenecrew.global.storage.AudioProbeResult;
import com.ssafy.scenecrew.global.storage.ExpiredFileCleaner;
import com.ssafy.scenecrew.global.storage.InvalidAudioException;
import com.ssafy.scenecrew.global.storage.OriginalFiles;
import com.ssafy.scenecrew.global.storage.StorageKeys;
import com.ssafy.scenecrew.issue.domain.AudioAsset;
import com.ssafy.scenecrew.issue.repository.AudioAssetRepository;

/**
 * 음성 보고 원본의 저장·삭제·만료 정리 (backend/docs/file-storage.md).
 * 업로드 API(POST /events/{e}/transcriptions)는 전사 작업 접수와 함께 붙인다(S15P31A202-266).
 * 호출하는 쪽은 로그인한 사용자의 이 행사 Member id를 넘긴다.
 */
@Service
public class AudioAssetService implements ExpiredFileCleaner {
    /** 음성 파일 최대 크기(P08) */
    public static final long MAX_BYTES = 10_485_760L;
    /** 음성 최대 길이(P08) */
    public static final BigDecimal MAX_DURATION_SEC = BigDecimal.valueOf(30);
    /** 인코더가 끝에 붙이는 짧은 여백은 허용한다. 30초 녹음이 30.02초로 재지는 경우가 있다. */
    static final BigDecimal DURATION_TOLERANCE_SEC = new BigDecimal("0.5");
    /** 원본 보관 기간: 생성 후 24시간(BE05) */
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(AudioAssetService.class);
    private final AudioAssetRepository audioAssets;
    private final MemberRepository members;
    private final EventRepository events;
    private final OriginalFiles originals;
    private final AudioProbe probe;
    private final TransactionTemplate transaction;
    private final CurrentMembers currentMembers;

    public AudioAssetService(AudioAssetRepository audioAssets, MemberRepository members, EventRepository events,
            OriginalFiles originals, AudioProbe probe, TransactionTemplate transaction, CurrentMembers currentMembers) {
        this.audioAssets = audioAssets;
        this.members = members;
        this.events = events;
        this.originals = originals;
        this.probe = probe;
        this.transaction = transaction;
        this.currentMembers = currentMembers;
    }

    /** 저장한 음성과 원본의 SHA-256(16진수). 전사 작업의 inputHash로 쓴다. */
    public record UploadedAudio(AudioAsset asset, String sha256) {
    }

    public UploadedAudio upload(UUID eventId, UUID uploaderMemberId, byte[] content) {
        return upload(eventId, uploaderMemberId, content, Function.identity());
    }

    /**
     * 검사 → S3 저장 → DB 등록 순서로 올린다. S3 호출은 트랜잭션 밖에서 한다.
     * inSameTransaction은 audio_asset 저장과 같은 트랜잭션에서 실행된다(전사 작업 접수 등).
     * 등록이 실패하면 방금 올린 S3 객체를 지운다.
     */
    public <T> T upload(UUID eventId, UUID uploaderMemberId, byte[] content,
            Function<UploadedAudio, T> inSameTransaction) {
        transaction.executeWithoutResult(status -> checkUploader(eventId, uploaderMemberId));
        AudioFormat format = checkContent(content);
        BigDecimal duration = measure(content, format);

        UUID id = UUID.randomUUID();
        String key = StorageKeys.audio(eventId, id);
        String sha256 = originals.put(key, content, format.mime, null);
        AtomicBoolean registered = new AtomicBoolean();
        try {
            return transaction.execute(status -> {
                // 행사를 먼저 잠근다(Event → Member → 업무 행, !198 AUD-005). 음성 행 INSERT의 FK 공유 잠금보다 먼저 잡아,
                // 같은 행사의 다른 쓰기가 Event를 잠그려 할 때 서로 기다리는 교착을 피한다.
                events.findForUpdate(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                checkUploader(eventId, uploaderMemberId);
                // 제약 위반은 여기서 드러나게 바로 반영한다. 그 뒤의 오류만 커밋 결과가 불확실한 경우다.
                AudioAsset asset = audioAssets.saveAndFlush(AudioAsset.create(id, eventId, uploaderMemberId, key, format.mime,
                        content.length, duration, Instant.now().truncatedTo(ChronoUnit.MICROS)));
                T result = inSameTransaction.apply(new UploadedAudio(asset, sha256));
                registered.set(true);
                return result;
            });
        } catch (RuntimeException e) {
            if (registered.get()) {
                // 커밋 단계 오류: 실제로 반영됐을 수 있어 원본을 남긴다. 남은 원본은 24시간 정리가 지운다.
                log.warn("음성 등록 커밋 결과를 알 수 없어 원본을 남깁니다: {}", key, e);
            } else {
                originals.discardUnregistered(key);
            }
            throw e;
        }
    }

    /** 올린 본인이 지운다(DELETE /audio-assets/{a}). 이미 지운 원본이면 그대로 성공이다. */
    public void delete(UUID audioAssetId, UUID actorMemberId) {
        AudioAsset asset = audioAssets.findById(audioAssetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!asset.getUploaderMemberId().equals(actorMemberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!removeOriginal(audioAssetId)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "파일을 지우지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 원본과 임시 전사문을 지운다. 보고 제출처럼 권한 확인이 끝난 곳에서 부른다.
     * S3를 먼저 지우고 DB를 비운다. S3 삭제가 실패하면 false이고 DB는 그대로 둔다.
     */
    /**
     * 061: 업로더 본인이 원본·임시 전사를 지운다(Report.text는 그대로). 업로더가 아니거나, 이 행사의 활성 Member가 아니거나,
     * 없는 음성이면 404로 존재를 숨긴다. 이미 지운 원본은 그대로 성공이다. S3 삭제가 실패하면 503.
     */
    public void deleteOwn(UUID audioAssetId, UUID accountId) {
        transaction.executeWithoutResult(status -> {
            AudioAsset asset = audioAssets.findById(audioAssetId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            Member me = currentMembers.require(asset.getEventId(), accountId);
            if (!asset.getUploaderMemberId().equals(me.getId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        });
        if (!removeOriginal(audioAssetId)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "파일을 지우지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public boolean removeOriginal(UUID audioAssetId) {
        return removeOriginal(audioAssetId, null);
    }

    @Override
    public int cleanupExpired(Instant now, int limit) {
        int removed = 0;
        for (AudioAssetRepository.StoredAudio expired : audioAssets.findExpired(now.minus(RETENTION),
                PageRequest.of(0, limit))) {
            if (removeOriginal(expired.getId(), expired.getStorageKey())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 음성 행을 잠근 채 S3 → DB 순서로 지운다. 전사 결과 반영(TranscribeKind.apply)도 같은 행을 잠그므로,
     * 삭제 도중 결과가 오면 삭제가 끝난 뒤 AUDIO_DELETED로 끝나고 "작업은 성공인데 전사문은 없는" 상태가 생기지 않는다.
     * 잠금은 S3 삭제 한 번 동안만 잡는다. expectedKey가 있으면 그 키일 때만 지운다(정리 작업이 고른 뒤 바뀐 경우 건너뜀).
     */
    private boolean removeOriginal(UUID audioAssetId, String expectedKey) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            String key = audioAssets.findForUpdate(audioAssetId).map(AudioAsset::getStorageKey).orElse(null);
            if (key == null || (expectedKey != null && !expectedKey.equals(key))) {
                return true;
            }
            if (!originals.delete(key)) {
                return false;
            }
            audioAssets.clearOriginal(audioAssetId, key);
            return true;
        }));
    }

    private void checkUploader(UUID eventId, UUID memberId) {
        Member member = members.findById(memberId)
                .filter(m -> m.getEventId().equals(eventId) && m.getActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        EventState state = events.findById(member.getEventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).getState();
        if (state == EventState.ENDED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 행사에는 음성을 올릴 수 없습니다.");
        }
    }

    private static AudioFormat checkContent(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "빈 파일입니다.");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "음성 파일은 10MB까지 올릴 수 있습니다.",
                    Map.of("maxBytes", MAX_BYTES));
        }
        return AudioFormat.detect(content);
    }

    private BigDecimal measure(byte[] content, AudioFormat format) {
        AudioProbeResult result;
        try {
            result = probe.probe(content);
        } catch (InvalidAudioException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "음성 파일을 열 수 없습니다.");
        } catch (AudioProbeException e) {
            log.warn("음성 길이를 재지 못했습니다.", e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "음성을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (!format.matches(result.formatName())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "m4a 또는 webm 음성만 올릴 수 있습니다.");
        }
        if (result.durationSec().compareTo(MAX_DURATION_SEC.add(DURATION_TOLERANCE_SEC)) > 0) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "음성은 30초까지 올릴 수 있습니다.",
                    Map.of("maxDurationSec", MAX_DURATION_SEC));
        }
        return result.durationSec();
    }

    /** 파일 앞부분(시그니처)으로 판단한 형식. 확장자와 클라이언트 Content-Type은 쓰지 않는다. */
    enum AudioFormat {
        M4A("audio/mp4", "mp4"),
        WEBM("audio/webm", "webm");

        final String mime;
        private final String ffprobeFormat;

        AudioFormat(String mime, String ffprobeFormat) {
            this.mime = mime;
            this.ffprobeFormat = ffprobeFormat;
        }

        static AudioFormat detect(byte[] content) {
            if (content.length >= 4 && (content[0] & 0xFF) == 0x1A && (content[1] & 0xFF) == 0x45
                    && (content[2] & 0xFF) == 0xDF && (content[3] & 0xFF) == 0xA3) {
                return WEBM;
            }
            if (content.length >= 8 && content[4] == 'f' && content[5] == 't' && content[6] == 'y'
                    && content[7] == 'p') {
                return M4A;
            }
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "m4a 또는 webm 음성만 올릴 수 있습니다.");
        }

        /** ffprobe가 알아낸 형식이 시그니처와 같은 계열인지 */
        boolean matches(String formatName) {
            return formatName != null && List.of(formatName.split(",")).contains(ffprobeFormat);
        }
    }
}
