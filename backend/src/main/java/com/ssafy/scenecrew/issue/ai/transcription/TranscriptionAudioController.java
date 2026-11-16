package com.ssafy.scenecrew.issue.ai.transcription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.aijob.client.AiServerProperties;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.storage.FileStorage;
import com.ssafy.scenecrew.global.storage.FileStorageException;
import com.ssafy.scenecrew.global.storage.StoredFile;
import com.ssafy.scenecrew.global.storage.StoredFileNotFoundException;
import com.ssafy.scenecrew.issue.domain.AudioAsset;
import com.ssafy.scenecrew.issue.repository.AudioAssetRepository;
import com.ssafy.scenecrew.issue.service.AudioAssetService;

/**
 * AI 서버(STT)가 전사할 음성을 내려받는 내부 경로(STT 명세 Q9). 사용자 로그인이 아니라 서비스 토큰(AI_SERVICE_TOKEN)으로 확인한다.
 * 운영에서는 업무 서버 nginx가 /internal/을 AI 서버 IP에만 연다.
 *
 * <p>요청마다 확인한다: 서비스 토큰, 음성이 남아 있고 보관 기간 안인지, 이 음성을 입력으로 하는 전사 작업이 대기·실행 중이고 마감 전인지.
 * 조건이 맞지 않으면 이유를 구별하지 않고 404다. URL 자체에는 유효기간이 없다. 응답은 저장하지 않게 no-store로 보낸다.
 */
@RestController
public class TranscriptionAudioController {
    public static final String PATH = TranscriptionProperties.AUDIO_PATH + "{audioId}";
    private static final Logger log = LoggerFactory.getLogger(TranscriptionAudioController.class);

    private final AiServerProperties server;
    private final AudioAssetRepository audioAssets;
    private final AiJobRepository aiJobs;
    private final FileStorage storage;
    private final TransactionTemplate transaction;

    public TranscriptionAudioController(AiServerProperties server, AudioAssetRepository audioAssets,
            AiJobRepository aiJobs, FileStorage storage, TransactionTemplate transaction) {
        this.server = server;
        this.audioAssets = audioAssets;
        this.aiJobs = aiJobs;
        this.storage = storage;
        this.transaction = transaction;
    }

    @GetMapping(PATH)
    public void download(@PathVariable String audioId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletResponse response) throws IOException {
        checkServiceToken(authorization);
        AudioAsset asset = transaction.execute(status -> downloadable(parse(audioId)));
        try (StoredFile file = storage.get(asset.getStorageKey())) {
            response.setContentType(asset.getMime());
            response.setContentLengthLong(file.contentLength());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            try (InputStream in = file.content(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (StoredFileNotFoundException e) {
            log.warn("DB에는 있지만 저장소에 없는 음성입니다: {}", asset.getId());
            throw new BusinessException(ErrorCode.NOT_FOUND);
        } catch (FileStorageException e) {
            log.warn("음성을 읽지 못했습니다: {}", asset.getId(), e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private AudioAsset downloadable(UUID audioId) {
        AudioAsset asset = audioAssets.findById(audioId)
                .filter(a -> a.isOriginalAvailable(Instant.now(), AudioAssetService.RETENTION))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!aiJobs.existsActiveForAudio(TranscribeKind.KIND, asset.getEventId(), audioId.toString(), Instant.now())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return asset;
    }

    /** Bearer 토큰을 일정한 시간으로 비교한다. 토큰이 설정되지 않은 서버는 내려주지 않는다. */
    private void checkServiceToken(String authorization) {
        String expected = server.serviceToken();
        if (expected == null || expected.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        String given = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : "";
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "서비스 인증에 실패했습니다.");
        }
    }

    private static UUID parse(String audioId) {
        try {
            UUID id = UUID.fromString(audioId);
            if (id.toString().equalsIgnoreCase(audioId)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 404
        }
        throw new BusinessException(ErrorCode.NOT_FOUND);
    }
}
