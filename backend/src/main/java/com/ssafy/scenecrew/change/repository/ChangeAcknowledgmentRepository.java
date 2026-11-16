package com.ssafy.scenecrew.change.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.change.domain.ChangeAcknowledgment;

public interface ChangeAcknowledgmentRepository extends JpaRepository<ChangeAcknowledgment, UUID> {
}
