package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.global.outbox.OutboxPublisher;

/**
 * AI 작업 접수. 업무 서비스가 자기 트랜잭션 안에서 부르면 ai_job(QUEUED)과 실행 요청(outbox)이 업무와 함께 저장된다.
 * 실제 호출은 커밋 뒤 outbox relay → AiJobDispatcher → AiJobRunner가 한다.
 */
@Service
@RequiredArgsConstructor
public class AiJobService {
    /** 공통 요청4 */
    public static final String REQUEST_SCHEMA_VERSION = "4";

    private final AiJobRepository repository;
    private final AiJobKinds kinds;
    private final AiJobProperties properties;
    private final OutboxPublisher outbox;
    private final JsonMapper jsonMapper;
    private final AiJobGate gate;

    /**
     * 작업을 접수한다. 마감은 지금부터 종류별 마감(scenecrew.ai.job.deadlines)이고 재시도·재시작으로 늘리지 않는다.
     * 먼저 공통 관문(AiJobGate)이 외부 AI 동의(403)와 진행 중 작업 한도(429 Retry-After)를 본다(V9-D43).
     *
     * @param requestedByMemberId 요청한 Member. 시스템이 시작한 작업이면 null
     * @param contextRevision     입력을 만든 시점의 Event.revision
     * @param payload             종류별 입력. JSON 객체로 직렬화되는 값이며 ai_job.input에 저장하고 AI에 그대로 보낸다.
     */
    @Transactional
    public AiJob accept(UUID eventId, UUID requestedByMemberId, String kind, long contextRevision, Object payload) {
        AiJobKind registered = kinds.find(kind)
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 AI 작업 종류입니다: " + kind));
        Duration deadline = properties.deadline(kind)
                .orElseThrow(() -> new IllegalStateException("마감이 정해지지 않은 AI 작업 종류입니다: " + kind));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        gate.checkAccept(eventId, requestedByMemberId, registered, now);
        String input = AiJobInputs.canonicalJson(jsonMapper, payload);
        AiJob job = repository.save(AiJob.queued(eventId, requestedByMemberId, kind, REQUEST_SCHEMA_VERSION, input,
                AiJobInputs.sha256(input), contextRevision, now, now.plus(deadline)));
        outbox.publish(eventId, AiJobOutboxTypes.REQUESTED, job.getId(), Map.of("jobId", job.getId()));
        return job;
    }
}
