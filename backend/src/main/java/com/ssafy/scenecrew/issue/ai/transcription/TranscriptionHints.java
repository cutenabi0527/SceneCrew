package com.ssafy.scenecrew.issue.ai.transcription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 전사 요청의 행사별 힌트(payload.hints, STT 명세 §5.1·Q16). 접수 시점의 구역·장비·프로그램·직무 그룹 이름을 한 번 모은다.
 *
 * <ul>
 *   <li>네 종류 모두 보내고 빈 배열을 허용한다. 사람 이름·자유 입력은 넣지 않는다.</li>
 *   <li>종류별 최대 50개, 30코드포인트를 넘는 이름은 빼고 보낸다(AI도 같은 상한을 검사해 넘으면 INVALID_REQUEST).</li>
 *   <li>같은 문자열은 하나만 두고 문자열 순서로 고정한다. 결과는 ai_job.input에 저장되어 재시도 때 그대로 쓰인다.</li>
 * </ul>
 */
@Component
public class TranscriptionHints {
    static final int MAX_PER_KIND = 50;
    static final int MAX_NAME_LENGTH = 30;

    private final JdbcTemplate jdbc;

    public TranscriptionHints(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {zones, equipment, programs, groups}. 키 순서는 AI 제안 우선순위(구역 → 장비 → 프로그램 → 직무 그룹)다. */
    public Map<String, List<String>> build(UUID eventId) {
        Map<String, List<String>> hints = new LinkedHashMap<>();
        hints.put("zones", names("select name from zone where event_id = ?", eventId));
        hints.put("equipment", names("select name from equipment_asset where event_id = ?", eventId));
        hints.put("programs", names("select name from program where event_id = ?", eventId));
        hints.put("groups", names("select name from work_group where event_id = ? and active", eventId));
        return hints;
    }

    private List<String> names(String sql, UUID eventId) {
        return pick(jdbc.queryForList(sql, String.class, eventId));
    }

    /** 앞뒤 공백을 자르고, 비었거나 30자를 넘는 이름을 빼고, 중복을 없애 이름순 최대 50개를 고른다. */
    static List<String> pick(List<String> raw) {
        List<String> sorted = new ArrayList<>();
        for (String name : raw) {
            String trimmed = name == null ? "" : name.strip();
            if (!trimmed.isEmpty() && trimmed.codePointCount(0, trimmed.length()) <= MAX_NAME_LENGTH) {
                sorted.add(trimmed);
            }
        }
        sorted.sort(String::compareTo);
        List<String> result = new ArrayList<>(new LinkedHashSet<>(sorted));
        return List.copyOf(result.size() > MAX_PER_KIND ? result.subList(0, MAX_PER_KIND) : result);
    }
}
