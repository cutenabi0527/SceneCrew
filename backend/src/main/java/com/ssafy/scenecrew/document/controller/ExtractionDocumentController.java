package com.ssafy.scenecrew.document.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.aijob.client.AiServerProperties;
import com.ssafy.scenecrew.document.ai.extraction.ExtractEventInputKind;
import com.ssafy.scenecrew.document.ai.extraction.ExtractionProperties;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.storage.FileStorageException;
import com.ssafy.scenecrew.global.storage.OriginalFiles;
import com.ssafy.scenecrew.global.storage.StoredFile;
import com.ssafy.scenecrew.global.storage.StoredFileNotFoundException;
import com.ssafy.scenecrew.plan.ai.generate.GeneratePlanKind;

/**
 * AI 서버(문서 추출·계획 생성의 평면도)가 원본 문서를 내려받는 내부 경로. 음성 전사의 TranscriptionAudioController와 같은 규칙이다.
 * 사용자 로그인이 아니라 서비스 토큰(AI_SERVICE_TOKEN)으로 확인하고, 운영에서는 업무 서버 nginx가 /internal/을 AI 서버 IP에만 연다.
 *
 * <p>요청마다 확인한다(#180 합의): 서비스 토큰, X-SceneCrew-Job-Id·X-SceneCrew-Job-Attempt가 지금 실행 중인 추출 작업과 회차인지,
 * 그 작업이 마감 전이고 이 문서를 입력에 넣었는지, 행사가 아직 DRAFT이고 요청한 사람이 지금도 총괄인지, 문서 원본이 남아 있고 만료 전인지.
 * 입력에 넣었다는 것은 문서 추출이면 documents[].documentId, 계획 생성(V9-D54, #391)이면 floorPlan.documentId다.
 * 토큰이 틀리면 401, 그 밖의 조건이 맞지 않으면 이유를 구별하지 않고 404, DB·저장소 일시 장애는 503이다. 응답은 no-store로 보낸다.
 */
@RestController
public class ExtractionDocumentController {
    public static final String PATH = ExtractionProperties.DOCUMENT_PATH + "{documentId}";
    public static final String JOB_ID = "X-SceneCrew-Job-Id";
    public static final String JOB_ATTEMPT = "X-SceneCrew-Job-Attempt";
    private static final Logger log = LoggerFactory.getLogger(ExtractionDocumentController.class);

    private final AiServerProperties server;
    private final JdbcTemplate jdbc;
    private final OriginalFiles originals;

    public ExtractionDocumentController(AiServerProperties server, JdbcTemplate jdbc, OriginalFiles originals) {
        this.server = server;
        this.jdbc = jdbc;
        this.originals = originals;
    }

    private record Downloadable(String storageKey, String mime) { }

    @GetMapping(PATH)
    public void download(@PathVariable String documentId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = JOB_ID, required = false) String jobIdHeader,
            @RequestHeader(name = JOB_ATTEMPT, required = false) String attemptHeader,
            HttpServletResponse response) throws IOException {
        checkServiceToken(authorization);
        UUID id = parse(documentId);
        UUID jobId = parse(jobIdHeader);
        long attempt = attempt(attemptHeader);
        Downloadable doc = find(id, jobId, attempt).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        try (StoredFile file = originals.get(doc.storageKey())) {
            response.setContentType(doc.mime());
            response.setContentLengthLong(file.contentLength());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            try (InputStream in = file.content(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (StoredFileNotFoundException e) {
            log.warn("DB에는 있지만 저장소에 없는 문서입니다: {}", id);
            throw new BusinessException(ErrorCode.NOT_FOUND);
        } catch (FileStorageException e) {
            log.warn("문서를 읽지 못했습니다: {}", id, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 내려받을 수 있는 문서를 찾는다. DB 연결 실패·시간 초과 같은 일시 장애는 #180 계약대로 503이고,
     * AI는 5xx만 다시 시도한다. 그 밖의 DB 오류는 코드 결함이라 그대로 500으로 둔다.
     */
    private Optional<Downloadable> find(UUID id, UUID jobId, long attempt) {
        Timestamp now = Timestamp.from(Instant.now());
        try {
            return jdbc.query("""
                    SELECT d.storage_key, d.mime FROM intake_document d
                      JOIN ai_job j ON j.event_id = d.event_id
                      JOIN event e ON e.id = d.event_id
                      JOIN member m ON m.id = j.requested_by_member_id
                     WHERE d.id = ? AND d.storage_key IS NOT NULL AND d.expires_at > ?
                       AND j.id = ? AND j.attempt = ? AND j.state = 'RUNNING' AND j.deadline_at > ?
                       AND ((j.kind = ? AND j.input -> 'documents' @> jsonb_build_array(jsonb_build_object('documentId', ?::text)))
                         OR (j.kind = ? AND j.input -> 'floorPlan' ->> 'documentId' = ?::text))
                       AND e.state = 'DRAFT'
                       AND m.active AND m.event_role = 'CHIEF'""",
                    (r, n) -> new Downloadable(r.getString(1), r.getString(2)),
                    id, now, jobId, attempt, now, ExtractEventInputKind.KIND, id.toString(), GeneratePlanKind.KIND,
                    id.toString()).stream().findFirst();
        } catch (TransientDataAccessException | DataAccessResourceFailureException e) {
            log.warn("문서 내려받기 조회 중 DB 일시 장애: documentId={}", id, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** Bearer 토큰을 일정한 시간으로 비교한다. 토큰이 설정되지 않은 서버는 내려주지 않는다. */
    private void checkServiceToken(String authorization) {
        String expected = server.serviceToken();
        if (expected == null || expected.isBlank()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        String given = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : "";
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "서비스 인증에 실패했습니다.");
        }
    }

    /** 1 이상의 10진수만 받는다. 아니면 404다. */
    private static long attempt(String value) {
        if (value != null && value.matches("[1-9][0-9]{0,8}")) return Long.parseLong(value);
        throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    private static UUID parse(String value) {
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException ignored) {
            // 아래에서 404
        }
        throw new BusinessException(ErrorCode.NOT_FOUND);
    }
}
