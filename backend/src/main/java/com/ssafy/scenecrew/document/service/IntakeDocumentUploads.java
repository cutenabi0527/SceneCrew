package com.ssafy.scenecrew.document.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.document.domain.IntakeDocument;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;

/**
 * SC-API-046 문서 등록의 요청 키(Idempotency-Key) 처리. 규칙은 backend/docs/intake-documents.md.
 *
 * <p>음성 접수(060, TranscriptionService.accept)와 같은 구조다. S3 저장은 트랜잭션 밖이라 업로드 전에 완료 기록을 한 번 확인해
 * 흔한 재요청(응답 유실 뒤 재시도)은 업로드 없이 최초 결과를 돌려준다. 등록 트랜잭션 안에서 Idempotency.run으로 다시 확인하고,
 * 그사이 같은 키의 앞 요청이 끝났으면 이번 등록을 되돌리고 방금 올린 원본을 지운 뒤 앞 결과를 돌려준다(행사 잠금으로 순서가 정해진다).
 * 같은 키에 다른 파일(순서·이름·형식·내용)을 보내면 409 IDEMPOTENCY_MISMATCH다.
 *
 * <p>응답은 파일별 결과(UploadResult)다. 한 파일이라도 검사에 걸리면 전체를 등록하지 않으므로(V9-D42) 성공 응답의 결과는 모두 REGISTERED다.
 */
@Service
public class IntakeDocumentUploads {
    public static final String OPERATION = "SC-API-046";
    public static final String RESULT_TYPE = "DOCUMENTS_REGISTERED";

    private final IntakeDocumentService documents;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final TransactionTemplate transaction;

    public IntakeDocumentUploads(IntakeDocumentService documents, CurrentMembers members, Idempotency idempotency,
            TransactionTemplate transaction) {
        this.documents = documents;
        this.members = members;
        this.idempotency = idempotency;
        this.transaction = transaction;
    }

    /** 046 응답(UploadResult). eventRevision은 등록 뒤 행사 revision이다. */
    public record UploadResult(List<FileResult> files, String eventRevision) {
        static UploadResult of(IntakeDocumentService.Registered registered) {
            List<FileResult> files = new ArrayList<>();
            for (int i = 0; i < registered.documents().size(); i++) {
                files.add(new FileResult(i, "REGISTERED", DocumentView.of(registered.documents().get(i))));
            }
            return new UploadResult(List.copyOf(files), Long.toString(registered.eventRevision()));
        }
    }

    public record FileResult(int index, String status, DocumentView document) { }

    /** 등록 당시 메타데이터. 파일 내용·저장 위치는 담지 않는다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DocumentView(UUID id, String originalName, String bytes, Integer pageCount, Instant expiresAt) {
        static DocumentView of(IntakeDocument document) {
            return new DocumentView(document.getId(), document.getOriginalName(), Long.toString(document.getBytes()),
                    document.getPageCount(), document.getExpiresAt());
        }
    }

    /**
     * 046. requestHash는 요청의 의미 있는 입력(파일 순서·이름·선언 형식·내용)으로 만든 값이다(컨트롤러).
     * 이 행사의 활성 Member가 아니면 404, 총괄이 아니면 403(새로 실행할 때 서비스가 확인)이다.
     */
    public IdempotentResult<UploadResult> accept(UUID eventId, UUID accountId, String key, String requestHash,
            List<DocumentUpload> uploads) {
        Prepared prepared = transaction.execute(status -> {
            Member me = members.require(eventId, accountId);
            var request = new IdempotencyRequest(accountId, OPERATION, key, requestHash, eventId, me.getId(),
                    me.getParticipationRevision());
            return new Prepared(request, idempotency.find(request, UploadResult.class));
        });
        if (prepared.done().isPresent()) {
            return prepared.done().get();
        }
        try {
            return documents.upload(eventId, accountId, uploads, registered -> {
                var result = idempotency.run(prepared.request(), UploadResult.class, () -> {
                    UploadResult body = UploadResult.of(registered);
                    return IdempotentResult.of(200, body, RESULT_TYPE, Map.of("documentIds",
                            registered.documents().stream().map(d -> d.getId().toString()).toList(),
                            "eventRevision", body.eventRevision()));
                });
                if (result.replayed()) {
                    // 같은 키의 앞 요청이 먼저 끝났다. 이번 등록과 원본은 버리고 앞 결과를 돌려준다.
                    throw new AlreadyRegistered(result);
                }
                return result;
            });
        } catch (AlreadyRegistered e) {
            return e.result;
        }
    }

    private record Prepared(IdempotencyRequest request, java.util.Optional<IdempotentResult<UploadResult>> done) { }

    private static final class AlreadyRegistered extends RuntimeException {
        private final transient IdempotentResult<UploadResult> result;

        AlreadyRegistered(IdempotentResult<UploadResult> result) {
            super(null, null, false, false);
            this.result = result;
        }
    }
}
