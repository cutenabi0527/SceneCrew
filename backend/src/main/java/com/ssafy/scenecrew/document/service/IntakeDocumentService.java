package com.ssafy.scenecrew.document.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.document.domain.IntakeDocument;
import com.ssafy.scenecrew.document.repository.IntakeDocumentRepository;
import com.ssafy.scenecrew.document.service.DocumentInspector.Inspected;
import com.ssafy.scenecrew.event.domain.Event;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.storage.ExpiredFileCleaner;
import com.ssafy.scenecrew.global.storage.FileStorageException;
import com.ssafy.scenecrew.global.storage.OriginalFiles;
import com.ssafy.scenecrew.global.storage.StorageKeys;
import com.ssafy.scenecrew.global.storage.StoredFile;
import com.ssafy.scenecrew.global.storage.StoredFileNotFoundException;

/**
 * 행사 DRAFT 입력 문서의 등록·다운로드·삭제·만료 정리 (BE05, backend/docs/file-storage.md).
 * 모든 동작은 원본 소유자이면서 현재 총괄이고 행사가 DRAFT일 때만 된다.
 * 공개 API(046 POST·047 DELETE /events/{e}/documents, 048 GET /intake-documents/{d}/download)는
 * document.controller.IntakeDocumentController가 붙인다. 046의 같은 키 재요청 복구는 IntakeDocumentUploads가 감싼다.
 * 등록·정리로 입력 묶음이 바뀌면 행사 revision과 last_input_modified_at을 올린다(계약 046·047, 옛 추출 적용 금지).
 * 호출하는 쪽은 로그인한 계정 id를 넘긴다.
 *
 * <p>입력 묶음을 바꾸는 일(등록·삭제·만료 정리)은 모두 행사 행을 잠그고(FOR UPDATE) 한다.
 * 그래서 등록이 만료를 늘리는 것과 정리가 지우는 것, 삭제와 추출 접수가 서로 끼어들지 않는다.
 * 문서 추출(EXTRACT_EVENT_INPUT)을 접수하는 쪽도 같은 잠금을 잡아야 한다.
 * 삭제는 잠금을 잡은 채 S3 → DB 순서로 지운다. 행사당 문서가 3개 이하라 잠금 시간은 짧다.
 */
@Service
public class IntakeDocumentService implements ExpiredFileCleaner {
    /** 한 행사 입력 묶음의 최대 파일 수(P08) */
    public static final int MAX_FILES = 3;
    /** 한 행사 입력 묶음의 최대 합계 크기(P08) */
    public static final long MAX_TOTAL_BYTES = 31_457_280L;
    /** 한 행사 입력 묶음의 PDF 쪽수 합계(P08) */
    public static final int MAX_TOTAL_PDF_PAGES = 30;
    /** 마지막 입력 수정 후 보관 기간(BE05) */
    public static final Duration RETENTION = Duration.ofDays(7);
    /** 이 종류의 작업이 대기·실행 중이면 파일을 바꾸지 못한다(BE05) */
    static final String EXTRACT_KIND = "EXTRACT_EVENT_INPUT";

    private static final Logger log = LoggerFactory.getLogger(IntakeDocumentService.class);
    private final IntakeDocumentRepository documents;
    private final EventRepository events;
    private final MemberRepository members;
    private final AiJobRepository aiJobs;
    private final OriginalFiles originals;
    private final TransactionTemplate transaction;

    public IntakeDocumentService(IntakeDocumentRepository documents, EventRepository events, MemberRepository members,
            AiJobRepository aiJobs, OriginalFiles originals, TransactionTemplate transaction) {
        this.documents = documents;
        this.events = events;
        this.members = members;
        this.aiJobs = aiJobs;
        this.originals = originals;
        this.transaction = transaction;
    }

    /**
     * 문서를 입력 묶음에 더한다. 한 파일이라도 검사에 걸리면 아무것도 올리지 않는다(오류 details.fileName).
     * 검사 → S3 저장(트랜잭션 밖) → 행사를 잠그고 다시 검사한 뒤 DB 등록.
     * 등록이 확실히 실패하면 올린 객체를 지운다. 커밋 결과가 불확실하면 남기고 만료 정리에 맡긴다.
     * 묶음이 바뀌었으므로 남아 있는 문서의 만료도 지금부터 7일로 다시 정한다.
     */
    public List<IntakeDocument> upload(UUID eventId, UUID actorAccountId, List<DocumentUpload> uploads) {
        return upload(eventId, actorAccountId, uploads, Registered::documents);
    }

    /** 등록한 문서와 등록 뒤 행사 revision */
    public record Registered(List<IntakeDocument> documents, long eventRevision) {
    }

    /**
     * 위 upload와 같고, inSameTransaction은 문서 등록과 같은 트랜잭션에서 실행된다(046 요청 키 기록 등).
     * inSameTransaction이 예외를 던지면 등록을 되돌리고 방금 올린 원본을 지운다.
     */
    public <T> T upload(UUID eventId, UUID actorAccountId, List<DocumentUpload> uploads,
            Function<Registered, T> inSameTransaction) {
        if (uploads == null || uploads.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "올릴 문서가 없습니다.");
        }
        transaction.executeWithoutResult(status -> {
            checkChiefOfDraft(eventId, actorAccountId, false);
            checkNoExtraction(eventId);
            checkBundleLimits(documents.findLiveByEventId(eventId, Instant.now()), uploads.size(),
                    uploads.stream().mapToLong(u -> u.content() == null ? 0 : u.content().length).sum(), 0);
        });
        List<Inspected> inspected = new ArrayList<>();
        for (int i = 0; i < uploads.size(); i++) inspected.add(inspect(uploads.get(i), i));
        int addPages = pages(inspected);
        transaction.executeWithoutResult(status -> checkBundleLimits(documents.findLiveByEventId(eventId, Instant.now()),
                inspected.size(), inspected.stream().mapToLong(f -> f.content().length).sum(), addPages));

        List<UUID> ids = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        AtomicBoolean registered = new AtomicBoolean();
        try {
            for (Inspected file : inspected) {
                UUID id = UUID.randomUUID();
                String key = StorageKeys.document(eventId, id);
                hashes.add(originals.put(key, file.content(), file.mime(), file.originalName()));
                ids.add(id);
                keys.add(key);
            }
            return transaction.execute(status -> {
                Event event = checkChiefOfDraft(eventId, actorAccountId, true);
                checkNoExtraction(eventId);
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                List<IntakeDocument> live = documents.findLiveByEventId(eventId, now);
                checkBundleLimits(live, inspected.size(),
                        inspected.stream().mapToLong(f -> f.content().length).sum(), addPages);
                Instant expiresAt = now.plus(RETENTION);
                live.forEach(document -> document.expireAt(expiresAt));
                List<IntakeDocument> saved = new ArrayList<>();
                for (int i = 0; i < inspected.size(); i++) {
                    Inspected file = inspected.get(i);
                    saved.add(documents.save(IntakeDocument.create(ids.get(i), eventId, actorAccountId,
                            file.originalName(), file.mime(), file.content().length, file.pageCount(),
                            hashes.get(i), keys.get(i), now, expiresAt)));
                }
                // 입력 묶음이 바뀌었으므로 옛 입력으로 만든 추출 결과를 적용하지 않게 한다.
                event.markInputChanged(now);
                // 제약 위반은 여기서 드러나게 바로 반영한다. 그 뒤의 오류만 커밋 결과가 불확실한 경우다.
                documents.flush();
                events.flush();
                T result = inSameTransaction.apply(new Registered(List.copyOf(saved), event.getRevision()));
                registered.set(true);
                return result;
            });
        } catch (RuntimeException e) {
            if (registered.get()) {
                log.warn("문서 등록 커밋 결과를 알 수 없어 원본을 남깁니다: {}", keys, e);
            } else {
                keys.forEach(originals::discardUnregistered);
            }
            throw e;
        }
    }

    /** 원본을 연다. 호출한 쪽이 응답으로 흘려보낸 뒤 닫는다. 읽기만 하므로 추출 중에도 된다. */
    public DocumentDownload download(UUID documentId, UUID actorAccountId) {
        IntakeDocument document = transaction.execute(status -> {
            IntakeDocument found = documents.findById(documentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if (!found.getOwnerAccountId().equals(actorAccountId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            checkChiefOfDraft(found.getEventId(), actorAccountId, false);
            if (found.getStorageKey() == null || found.isExpired(Instant.now())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "보관 기간이 끝났거나 삭제된 문서입니다.");
            }
            return found;
        });
        try {
            StoredFile file = originals.get(document.getStorageKey());
            return new DocumentDownload(document.getOriginalName(), document.getMime(), file);
        } catch (StoredFileNotFoundException e) {
            log.warn("DB에는 있지만 저장소에 없는 문서입니다: {}", documentId);
            throw new BusinessException(ErrorCode.NOT_FOUND, "보관 기간이 끝났거나 삭제된 문서입니다.");
        } catch (FileStorageException e) {
            log.warn("문서를 읽지 못했습니다: {}", documentId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "파일을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 입력 PDF 한 쪽을 그린 PNG(V9-D62, 계획 작성 화면의 평면도 배경). 권한·만료는 원본 내려받기(048)와 같고,
     * PDF가 아니거나 그 쪽이 없으면 404다.
     */
    public PdfPageImage.Rendered pageImage(UUID documentId, int pageNumber, UUID actorAccountId) {
        try (DocumentDownload download = download(documentId, actorAccountId)) {
            if (!"application/pdf".equals(download.mime())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "PDF 문서가 아닙니다.");
            }
            byte[] pdf = download.content().readAllBytes();
            return PdfPageImage.render(pdf, pageNumber);
        } catch (PdfPageImage.PageNotFound e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("문서를 읽지 못했습니다: {}", documentId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "파일을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 내가 올린 입력 문서를 모두 지운다(DELETE /events/{e}/documents). 지운 개수를 돌려준다.
     * 행사를 잠근 채 S3를 먼저 지우고 DB storage_key를 비운다. 일부가 S3에서 지워지지 않으면
     * 지운 것만 반영하고 오류를 낸다.
     */
    public int deleteAll(UUID eventId, UUID actorAccountId) {
        return deleteAll(eventId, actorAccountId, null);
    }

    /**
     * 047. expectedEventRevision이 있으면 행사를 잠근 뒤 현재 revision과 같아야 한다(다르면 409 VERSION_CONFLICT).
     * 실제로 지운 원본이 있으면 입력 묶음이 바뀌었으므로 revision과 last_input_modified_at을 올린다.
     * 공개 API(expectedEventRevision이 있는 호출)는 이 행사의 활성 Member가 아니면 404로 행사 존재를 알리지 않는다(be/08).
     */
    public int deleteAll(UUID eventId, UUID actorAccountId, Long expectedEventRevision) {
        int[] result = transaction.execute(status -> {
            if (expectedEventRevision != null && members.findActive(eventId, actorAccountId).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            Event event = checkChiefOfDraft(eventId, actorAccountId, true);
            if (expectedEventRevision != null && event.getRevision() != expectedEventRevision.longValue()) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "event"));
            }
            checkNoExtraction(eventId);
            List<IntakeDocument> mine =
                    documents.findByEventIdAndOwnerAccountIdAndStorageKeyIsNotNull(eventId, actorAccountId);
            int removed = 0;
            for (IntakeDocument document : mine) {
                if (originals.delete(document.getStorageKey())) {
                    document.removeOriginal();
                    removed++;
                }
            }
            if (removed > 0) event.markInputChanged(Instant.now().truncatedTo(ChronoUnit.MICROS));
            return new int[] {removed, mine.size()};
        });
        if (result[0] < result[1]) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "일부 문서를 지우지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return result[0];
    }

    /**
     * 만료된 원본을 행사별로 지운다. 행사를 잠근 뒤 만료를 다시 확인하므로, 대상을 고른 뒤
     * 새 업로드로 만료가 늘어난 문서는 지우지 않는다.
     */
    @Override
    public int cleanupExpired(Instant now, int limit) {
        Map<UUID, List<UUID>> byEvent = new LinkedHashMap<>();
        for (IntakeDocumentRepository.ExpiredDocument expired : documents.findExpired(now, PageRequest.of(0, limit))) {
            byEvent.computeIfAbsent(expired.getEventId(), e -> new ArrayList<>()).add(expired.getId());
        }
        int removed = 0;
        for (Map.Entry<UUID, List<UUID>> entry : byEvent.entrySet()) {
            Integer count = transaction.execute(status -> {
                events.findForUpdate(entry.getKey());
                int n = 0;
                for (IntakeDocument document : documents.findAllById(entry.getValue())) {
                    if (document.getStorageKey() != null && document.isExpired(now)
                            && originals.delete(document.getStorageKey())) {
                        document.removeOriginal();
                        n++;
                    }
                }
                return n;
            });
            removed += count == null ? 0 : count;
        }
        return removed;
    }

    /** 현재 총괄이고 행사가 DRAFT인지. lock이면 행사 행을 잠근다. */
    private Event checkChiefOfDraft(UUID eventId, UUID actorAccountId, boolean lock) {
        Event event = (lock ? events.findForUpdate(eventId) : events.findById(eventId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (members.findActiveChief(eventId, actorAccountId).isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (event.getState() != EventState.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "행사를 준비하는 동안(DRAFT)에만 문서를 다룰 수 있습니다.");
        }
        return event;
    }

    /** 문서 추출이 대기·실행 중이면 파일을 바꾸지 못한다(BE05). */
    private void checkNoExtraction(UUID eventId) {
        if (aiJobs.existsByEventIdAndKindAndStateIn(eventId, EXTRACT_KIND,
                List.of(AiJobState.QUEUED, AiJobState.RUNNING))) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "문서에서 조건을 추출하는 중에는 문서를 바꿀 수 없습니다.");
        }
    }

    /** 파일 하나를 검사한다. 걸리면 그 파일의 요청 순서(index)를 details에 더한다. */
    private static Inspected inspect(DocumentUpload upload, int index) {
        try {
            return DocumentInspector.inspect(upload);
        } catch (BusinessException e) {
            Map<String, Object> details = new LinkedHashMap<>(e.getDetails() == null ? Map.of() : e.getDetails());
            details.put("index", index);
            throw new BusinessException(e.getErrorCode(), e.getMessage(), details);
        }
    }

    private static int pages(List<Inspected> inspected) {
        return inspected.stream().mapToInt(f -> f.pageCount() == null ? 0 : f.pageCount()).sum();
    }

    private static void checkBundleLimits(List<IntakeDocument> live, int addCount, long addBytes, int addPages) {
        if (live.size() + addCount > MAX_FILES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "문서는 행사당 3개까지 올릴 수 있습니다.",
                    Map.of("maxFiles", MAX_FILES, "currentFiles", live.size()));
        }
        long current = live.stream().mapToLong(IntakeDocument::getBytes).sum();
        if (current + addBytes > MAX_TOTAL_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "문서는 합계 30MB까지 올릴 수 있습니다.",
                    Map.of("maxTotalBytes", MAX_TOTAL_BYTES, "currentBytes", current));
        }
        // PDF 쪽수는 묶음 합계로 센다(P08 "PDF합계30쪽"). 파일 하나의 30쪽 상한은 DocumentInspector가 본다.
        int currentPages = live.stream().mapToInt(d -> d.getPageCount() == null ? 0 : d.getPageCount()).sum();
        if (currentPages + addPages > MAX_TOTAL_PDF_PAGES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "PDF는 합계 30쪽까지 올릴 수 있습니다.",
                    Map.of("maxTotalPages", MAX_TOTAL_PDF_PAGES, "currentPages", currentPages));
        }
    }
}
