package com.ssafy.scenecrew.equipment.collector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 노트북 수집 프로그램의 연결 코드 교환·찾은 수신기 보고(#394). 관측 수신과 같은 내부 경로이며 사용자 로그인·Cookie를 쓰지 않는다.
 * 본문은 문자열로 받아 서비스가 직접 읽는다(형식 오류를 수집기 오류 코드로 돌려주기 위해서다).
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.equipment.collector.enabled", havingValue = "true")
public class CollectorPairController {
    public static final String PAIR_PATH = "/internal/equipment-collectors/pair";
    public static final String RECEIVERS_PATH = "/internal/equipment-collectors/{collectorId}/receivers";

    private final CollectorPairingService service;

    public CollectorPairController(CollectorPairingService service) {
        this.service = service;
    }

    @PostMapping(path = PAIR_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CollectorPairingService.Paired> pair(@RequestBody(required = false) String body) {
        // 토큰 원문이 담긴 응답은 어디에도 캐시하지 않는다
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.pair(body));
    }

    @PutMapping(path = RECEIVERS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CollectorPairingService.ReceiversAck receivers(@PathVariable String collectorId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) String body) {
        return service.receivers(collectorId, authorization, body);
    }
}
