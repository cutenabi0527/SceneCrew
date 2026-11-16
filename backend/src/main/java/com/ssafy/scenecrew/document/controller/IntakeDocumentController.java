package com.ssafy.scenecrew.document.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.document.service.DocumentDownload;
import com.ssafy.scenecrew.document.service.DocumentUpload;
import com.ssafy.scenecrew.document.service.IntakeDocumentService;
import com.ssafy.scenecrew.document.service.IntakeDocumentUploads;
import com.ssafy.scenecrew.document.service.IntakeDocumentUploads.UploadResult;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;

/**
 * SC-API-046 문서 등록·047 입력 문서 정리·048 다운로드. 규칙은 backend/docs/intake-documents.md.
 * 인증과 쓰기 검사(046: WEB 허용 Origin·multipart, 047: WEB 허용 Origin·JSON)는 PublicAuthFilter가 먼저 한다.
 * 여기서는 요청 형식만 검사하고(400), 권한·상태·한도·revision은 서비스가 트랜잭션 안에서 본다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class IntakeDocumentController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String REVISION = "0|[1-9][0-9]{0,18}";
    /** 계약 DocumentUpload: files 1~3개 */
    private static final int MAX_PARTS = IntakeDocumentService.MAX_FILES;

    private final IntakeDocumentUploads uploads;
    private final IntakeDocumentService documents;

    public IntakeDocumentController(IntakeDocumentUploads uploads, IntakeDocumentService documents) {
        this.uploads = uploads;
        this.documents = documents;
    }

    /** 046. multipart의 files 파트만 1~3개, 요청 키 필수. */
    @PostMapping(value = "/api/v1/events/{e}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResult> upload(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        UUID eventId = uuid(e);
        String key = key(request);
        if (!request.getParameterMap().isEmpty()) throw invalid();
        List<DocumentUpload> files = new ArrayList<>();
        List<Object> identity = new ArrayList<>();
        identity.add(eventId);
        for (Part part : parts(request)) {
            if (!"files".equals(part.getName()) || part.getSubmittedFileName() == null) throw invalid();
            if (files.size() == MAX_PARTS) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "문서는 한 번에 3개까지 올릴 수 있습니다.",
                        Map.of("maxFiles", MAX_PARTS));
            }
            byte[] content = bytes(part);
            files.add(new DocumentUpload(part.getSubmittedFileName(), content));
            // 요청 키 비교 기준: 파일 순서·원래 이름·선언 형식·실제 내용(계약 046 requestIdentity)
            identity.add(files.size() - 1);
            identity.add(part.getSubmittedFileName());
            identity.add(mediaType(part.getContentType()));
            identity.add(content);
        }
        if (files.isEmpty()) throw invalid();
        var result = uploads.accept(eventId, accountId(authentication), key,
                IdempotencyRequest.hash(identity.toArray()), files);
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    /** 047. 본문 {expectedEventRevision}. 내가 올린 입력 문서 원본을 모두 지우고 204. */
    @DeleteMapping(value = "/api/v1/events/{e}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> deleteAll(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        UUID eventId = uuid(e);
        if (!request.getParameterMap().isEmpty()) throw invalid();
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("expectedEventRevision")) throw invalid();
        JsonNode revision = body.get("expectedEventRevision");
        if (!revision.isString() || !revision.asString().matches(REVISION)) throw invalid();
        long expected;
        try {
            expected = Long.parseLong(revision.asString());
        } catch (NumberFormatException ex) {
            throw invalid();
        }
        documents.deleteAll(eventId, accountId(authentication), expected);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    /** 048. 원본 소유자이면서 지금 이 행사의 총괄이고 DRAFT일 때만. 만료·삭제된 원본은 404. */
    @GetMapping("/api/v1/intake-documents/{d}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("d") String d, HttpServletRequest request,
            Authentication authentication) {
        UUID documentId = uuid(d);
        if (!request.getParameterMap().isEmpty()) throw invalid();
        DocumentDownload download = documents.download(documentId, accountId(authentication));
        StreamingResponseBody body = out -> {
            try (download; InputStream in = download.content()) {
                in.transferTo(out);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType(download.mime()));
        headers.setContentLength(download.contentLength());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.originalName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** V9-D62. 입력 PDF n쪽을 그린 PNG(계획 작성 화면의 평면도 배경). 권한은 048과 같다. */
    @GetMapping("/api/v1/intake-documents/{d}/pages/{n}/image")
    public ResponseEntity<byte[]> pageImage(@PathVariable("d") String d, @PathVariable("n") String n,
            HttpServletRequest request, Authentication authentication) {
        UUID documentId = uuid(d);
        if (!request.getParameterMap().isEmpty() || !n.matches("[1-9][0-9]{0,3}")) throw invalid();
        var image = documents.pageImage(documentId, Integer.parseInt(n), accountId(authentication));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        // 평면도 그림이 놓인 영역(0~1, 왼쪽 위 기준). 화면은 이 영역만 크게 보인다
        if (image.crop() != null) headers.set("X-Floor-Plan-Crop", image.crop().header());
        return ResponseEntity.ok().headers(headers).body(image.png());
    }

    // ---- 입력 검사 -------------------------------------------------------------------------------------------

    /** 글자 문서는 UTF-8로 검사했으므로 charset을 밝힌다. */
    private static MediaType contentType(String mime) {
        MediaType type = MediaType.parseMediaType(mime);
        return "text".equals(type.getType()) ? new MediaType(type, StandardCharsets.UTF_8) : type;
    }

    /** 선언된 Content-Type을 형식 비교용으로만 정규화한다. 형식 판단은 서비스가 실제 내용으로 한다. */
    private static String mediaType(String contentType) {
        if (contentType == null) return null;
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return contentType.strip().toLowerCase(Locale.ROOT);
        }
    }

    private static Collection<Part> parts(HttpServletRequest request) {
        try {
            return request.getParts();
        } catch (IOException | ServletException | IllegalStateException e) {
            throw invalid();
        }
    }

    private static byte[] bytes(Part part) {
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw invalid();
        }
    }

    private static UUID uuid(String value) {
        if (!value.matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(value);
    }

    /** Idempotency-Key는 한 번만. UUID 형식 검사는 IdempotencyRequest가 한다. */
    private static String key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    private static UUID accountId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
