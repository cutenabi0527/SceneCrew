package com.ssafy.scenecrew.equipment.collector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.equipment.collector.CollectorIngestService.Ack;

/**
 * 장비 수집기 전용 내부 경로(#330). 공개 /api/v1과 분리하며 사용자 로그인·Cookie를 쓰지 않는다.
 * 본문은 문자열로 받아 직접 읽는다. 형식 오류를 계약의 수집기 오류 코드로 돌려주기 위해서다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.equipment.collector.enabled", havingValue = "true")
public class CollectorIngestController {
    public static final String PATH = "/internal/equipment-collectors/{collectorId}/observations";

    private final CollectorIngestService service;

    public CollectorIngestController(CollectorIngestService service) {
        this.service = service;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Ack ingest(@PathVariable String collectorId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) String body) {
        return service.ingest(collectorId, authorization, body);
    }
}
