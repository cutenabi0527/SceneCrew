package com.ssafy.scenecrew.plan.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.plan.domain.EventUnconfirmedDraft;

public interface EventUnconfirmedDraftRepository extends JpaRepository<EventUnconfirmedDraft, UUID> {
}
