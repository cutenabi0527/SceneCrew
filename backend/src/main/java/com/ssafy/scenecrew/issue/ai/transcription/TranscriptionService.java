package com.ssafy.scenecrew.issue.ai.transcription;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.pipeline.AiJobService;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.issue.service.AudioAssetService;
import com.ssafy.scenecrew.issue.service.AudioAssetService.UploadedAudio;

/**
 * 음성 전사 접수(060 POST /events/{e}/transcriptions의 서비스). 음성 저장과 전사 작업 접수를 같은 트랜잭션에 남긴다.
 *
 * <p>payload(ai_job.input): audioId, audioUrl, audioSha256(업로드 때 계산한 원본 해시), hints, 그리고 사용자가
 * "원음으로 다시 전사"를 고른 경우에만 options.pipeline=RAW. 마감은 접수부터 90초(scenecrew.ai.job.deadlines)다.
 *
 * <p>요청 키(#340): 음성 업로드는 S3 저장을 트랜잭션 밖에서 한다(#218). 그래서 업로드 전에 완료 기록을 한 번 확인해
 * 흔한 재요청(응답 유실 뒤 재시도)은 업로드 없이 최초 결과를 돌려주고, 등록 트랜잭션에서 Idempotency.run으로 다시 확인한다.
 * 동시에 온 같은 요청은 등록 단계에서 하나만 남고, 늦은 쪽은 자기 등록을 롤백하고 원본을 지운 뒤 앞 결과를 돌려준다.
 * 전사 작업은 같은 키로 한 번만 접수된다. backend/docs/stt-transcription.md
 */
@Service
@EnableConfigurationProperties(TranscriptionProperties.class)
public class TranscriptionService {
    /** 요청 키 기록의 API 구분 */
    public static final String OPERATION = "SC-API-060";
    public static final String RESULT_TYPE = "TRANSCRIPTION_ACCEPTED";

    private final AudioAssetService audioAssets;
    private final AiJobService aiJobs;
    private final EventRepository events;
    private final TranscriptionHints hints;
    private final TranscriptionProperties properties;
    private final CurrentMembers currentMembers;
    private final Idempotency idempotency;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TranscriptionService(AudioAssetService audioAssets, AiJobService aiJobs, EventRepository events,
            TranscriptionHints hints, TranscriptionProperties properties, CurrentMembers currentMembers,
            Idempotency idempotency, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.audioAssets = audioAssets;
        this.aiJobs = aiJobs;
        this.events = events;
        this.hints = hints;
        this.properties = properties;
        this.currentMembers = currentMembers;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /** 접수한 음성과 전사 작업 */
    public record Submitted(UUID audioId, UUID jobId) {
    }

    /** 060 응답(202). 전사 결과는 statusUrl(GET /api/v1/ai-jobs/{jobId})로 확인한다. */
    public record TranscriptionAccepted(UUID audioId, UUID jobId, String statusUrl) {
        static TranscriptionAccepted of(Submitted submitted) {
            return new TranscriptionAccepted(submitted.audioId(), submitted.jobId(),
                    "/api/v1/ai-jobs/" + submitted.jobId());
        }
    }

    /**
     * 060: 로그인한 계정의 음성을 받아 전사를 접수한다. 이 행사의 활성 Member이고 음성 사용에 동의했어야 한다.
     * 같은 요청 키 재요청은 업로드·접수를 다시 하지 않고 최초 응답을 돌려준다.
     */
    public IdempotentResult<TranscriptionAccepted> accept(UUID eventId, UUID accountId, String key, byte[] content,
            boolean rawPipeline) {
        requireConfigured();
        String requestHash = IdempotencyRequest.hash(eventId, rawPipeline ? "RAW" : null, content);
        Prepared prepared = transaction.execute(status -> {
            Member me = currentMembers.require(eventId, accountId);
            requireVoiceConsent(accountId);
            var request = new IdempotencyRequest(accountId, OPERATION, key, requestHash, eventId, me.getId(),
                    me.getParticipationRevision());
            return new Prepared(me.getId(), request, idempotency.find(request, TranscriptionAccepted.class));
        });
        if (prepared.done().isPresent()) {
            return prepared.done().get();
        }
        try {
            return audioAssets.upload(eventId, prepared.memberId(), content, uploaded -> {
                // 등록 트랜잭션은 AudioAssetService가 Event를 먼저 잠근 상태다(Event → Member 순서).
                Member me = currentMembers.requireLocked(eventId, accountId);
                if (!me.getId().equals(prepared.memberId())) {
                    throw new BusinessException(ErrorCode.NOT_FOUND);
                }
                // 업로드하는 동안 이탈→재참여하면 Member id는 같고 회차만 오른다. 옛 회차에서 시작한 녹음을 새 회차로
                // 접수하지 않는다(#378). 요청 키 공통 처리의 회차 불일치와 같은 403이며, 등록 롤백·원본 정리는 upload가 한다.
                if (!me.getParticipationRevision().equals(prepared.request().participationRevision())) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                requireVoiceConsent(accountId);
                var result = idempotency.run(prepared.request(), TranscriptionAccepted.class, () -> {
                    Submitted submitted = acceptJob(eventId, me.getId(), uploaded, rawPipeline);
                    return IdempotentResult.of(202, TranscriptionAccepted.of(submitted), RESULT_TYPE,
                            Map.of("audioId", submitted.audioId(), "jobId", submitted.jobId()));
                });
                if (result.replayed()) {
                    // 같은 키의 앞 요청이 먼저 끝났다. 이번 등록과 원본은 버리고 앞 결과를 돌려준다.
                    throw new AlreadyAccepted(result);
                }
                return result;
            });
        } catch (AlreadyAccepted e) {
            return e.result;
        }
    }

    /**
     * 음성을 저장하고 전사 작업을 접수한다. 검사·저장 규칙은 AudioAssetService.upload를 따른다.
     * 로그인·동의·요청 키 확인 없이 Member id로 바로 접수하는 내부 경로다. 공개 API는 accept를 쓴다.
     *
     * @param rawPipeline 사용자가 "원음으로 다시 전사"를 골랐으면 true(060 pipeline=RAW). 새 음성·새 작업이다.
     */
    public Submitted submit(UUID eventId, UUID uploaderMemberId, byte[] content, boolean rawPipeline) {
        requireConfigured();
        return audioAssets.upload(eventId, uploaderMemberId, content,
                uploaded -> acceptJob(eventId, uploaderMemberId, uploaded, rawPipeline));
    }

    private Submitted acceptJob(UUID eventId, UUID uploaderMemberId, UploadedAudio uploaded, boolean rawPipeline) {
        long revision = events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))
                .getRevision();
        UUID audioId = uploaded.asset().getId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("audioId", audioId.toString());
        payload.put("audioUrl", properties.audioUrl(audioId));
        payload.put("audioSha256", uploaded.sha256());
        payload.put("hints", hints.build(eventId));
        if (rawPipeline) {
            payload.put("options", Map.of("pipeline", "RAW"));
        }
        AiJob job = aiJobs.accept(eventId, uploaderMemberId, TranscribeKind.KIND, revision, payload);
        return new Submitted(audioId, job.getId());
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "음성 전사를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 가입 때 받은 음성 사용 동의(account.consent.voice). JSON true일 때만 통과하고, 키가 없거나 null·false·다른 값이면 403. */
    private void requireVoiceConsent(UUID accountId) {
        boolean voice = jdbc.query("SELECT coalesce(consent -> 'voice' = 'true'::jsonb, false) FROM account WHERE id = ?",
                (r, n) -> r.getBoolean(1), accountId).stream().findFirst().orElse(false);
        if (!voice) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "음성 사용에 동의해야 녹음을 보낼 수 있습니다.");
        }
    }

    private record Prepared(UUID memberId, IdempotencyRequest request,
            Optional<IdempotentResult<TranscriptionAccepted>> done) {
    }

    /** 등록 트랜잭션을 롤백해 원본을 지우게 하면서 앞 요청의 결과를 밖으로 전달한다. */
    private static final class AlreadyAccepted extends RuntimeException {
        private final transient IdempotentResult<TranscriptionAccepted> result;

        AlreadyAccepted(IdempotentResult<TranscriptionAccepted> result) {
            super(null, null, false, false);
            this.result = result;
        }
    }
}
