package com.ssafy.scenecrew.equipment.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.equipment.service.EquipmentViews.AssetView;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.ConnectionView;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.RequirementView;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.ReservationView;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.global.web.SignedCursor;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 장비 현황 조회 EXT01(목록)·EXT03(상세)·EXT05(예약 시간표). 자격은 EquipmentAccess(V9-D13)가 본다.
 *
 * <p>정렬: 장비는 이름(바이트 순)·id, 예약은 시작 시각·id. cursor는 마지막 항목 위치를 서명한 값이고 행사·Member에 묶인다.
 * 응답에는 수집기 토큰·장비 IP·수집 원시값을 넣지 않는다(V9-D13). 미확인 값은 null·빈 목록 그대로 둔다.
 */
public class EquipmentReadService {
    public record AssetPage(List<AssetView> items, String nextCursor) { }

    public record ReservationPage(List<ReservationView> items, String nextCursor) { }

    public record Detail(AssetView asset, List<ConnectionView> connections, List<RequirementView> requirements,
            List<ReservationView> reservations, String eventRevision) { }

    private final JdbcTemplate jdbc;
    private final EquipmentAccess access;
    private final SignedCursor assetCursor;
    private final SignedCursor reservationCursor;

    public EquipmentReadService(JdbcTemplate jdbc, EquipmentAccess access, byte[] signingKey) {
        this.jdbc = jdbc;
        this.access = access;
        this.assetCursor = new SignedCursor(signingKey, "equipment-assets");
        this.reservationCursor = new SignedCursor(signingKey, "equipment-reservations");
    }

    /** EXT01 */
    @Transactional(readOnly = true)
    public AssetPage assets(UUID eventId, UUID accountId, String cursor, int limit) {
        Member member = access.requireReader(eventId, accountId);
        String scope = scope(eventId, member);
        SignedCursor.Position after = assetCursor.decode(scope, cursor);
        List<Object> args = new ArrayList<>(List.of(eventId));
        String where = "";
        if (after != null) {
            where = " AND (a.name COLLATE \"C\", a.id) > (CAST(? AS text) COLLATE \"C\", ?)";
            args.add(decodeName(after.sortKey()));
            args.add(after.id());
        }
        args.add(limit + 1);
        List<AssetView> rows = jdbc.query("SELECT " + EquipmentViews.ASSET_COLUMNS + " FROM equipment_asset a WHERE a.event_id = ?"
                + where + " ORDER BY a.name COLLATE \"C\", a.id LIMIT ?", EquipmentViews::asset, args.toArray());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            AssetView last = rows.getLast();
            next = assetCursor.encode(scope, new SignedCursor.Position(encodeName(last.name()), last.id()));
        }
        return new AssetPage(withAuto(eventId, rows), next);
    }

    /** EXT03 */
    @Transactional(readOnly = true)
    public Detail detail(UUID assetId, UUID accountId) {
        UUID eventId = access.eventOfAsset(assetId);
        access.requireReader(eventId, accountId);
        AssetView asset = jdbc.query("SELECT " + EquipmentViews.ASSET_COLUMNS + " FROM equipment_asset a WHERE a.id = ?",
                EquipmentViews::asset, assetId).getFirst();
        asset = withAuto(eventId, List.of(asset)).getFirst();
        List<ConnectionView> connections = jdbc.query("SELECT " + EquipmentViews.CONNECTION_COLUMNS + """
                 FROM equipment_connection c
                WHERE c.event_id = ? AND c.active AND (c.from_asset_id = ? OR c.to_asset_id = ?)
                ORDER BY c.confirmed_at, c.id""", EquipmentViews::connection, eventId, assetId, assetId);
        // 요구 항목의 장비·호환·구성품 목록 어디에든 이 장비가 있으면 관련 요구다(id 문자열 그대로 저장하므로 포함 검색).
        List<RequirementView> requirements = jdbc.query("SELECT " + EquipmentViews.REQUIREMENT_COLUMNS + """
                 FROM equipment_requirement q
                WHERE q.event_id = ? AND strpos(q.requirements::text, ?) > 0
                ORDER BY q.ready_by, q.id""", EquipmentViews::requirement, eventId, "\"" + assetId + "\"");
        List<ReservationView> reservations = jdbc.query("SELECT " + EquipmentViews.RESERVATION_COLUMNS + """
                 FROM equipment_reservation v
                WHERE v.event_id = ? AND EXISTS (SELECT 1 FROM equipment_reservation_asset ra
                                                  WHERE ra.reservation_id = v.id AND ra.asset_id = ?)
                ORDER BY v.start_at, v.id""", EquipmentViews::reservation, eventId, assetId);
        return new Detail(asset, connections, requirements, reservations, Long.toString(eventRevision(eventId)));
    }

    /** EXT05 */
    @Transactional(readOnly = true)
    public ReservationPage reservations(UUID eventId, UUID accountId, String cursor, int limit) {
        Member member = access.requireReader(eventId, accountId);
        String scope = scope(eventId, member);
        SignedCursor.Position after = reservationCursor.decode(scope, cursor);
        List<Object> args = new ArrayList<>(List.of(eventId));
        String where = "";
        if (after != null) {
            where = " AND (v.start_at, v.id) > (?::timestamptz, ?)";
            args.add(after.sortKey());
            args.add(after.id());
        }
        args.add(limit + 1);
        List<ReservationView> rows = jdbc.query("SELECT " + EquipmentViews.RESERVATION_COLUMNS
                + " FROM equipment_reservation v WHERE v.event_id = ?" + where + " ORDER BY v.start_at, v.id LIMIT ?",
                EquipmentViews::reservation, args.toArray());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            ReservationView last = rows.getLast();
            next = reservationCursor.encode(scope, new SignedCursor.Position(last.startAt().toString(), last.id()));
        }
        return new ReservationPage(List.copyOf(rows), next);
    }

    /** 자동 관측 종류가 있는 장비에 상태·핵심 값을 붙인다(#394, AutoObservations). */
    private List<AssetView> withAuto(UUID eventId, List<AssetView> rows) {
        var auto = AutoObservations.of(jdbc, eventId, rows.stream().map(AssetView::id).toList(), java.time.Instant.now());
        return rows.stream().map(a -> auto.containsKey(a.id()) ? a.with(auto.get(a.id())) : a).toList();
    }

    private long eventRevision(UUID eventId) {
        return jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, eventId);
    }

    /** cursor는 이 행사·이 참여(Member·회차)에서만 쓴다. */
    private static String scope(UUID eventId, Member member) {
        return eventId + ":" + member.getId() + ":" + member.getParticipationRevision();
    }

    private static String encodeName(String name) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeName(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw StrictJson.invalid("cursor", "FORMAT");
        }
    }
}
