package com.ssafy.scenecrew.change.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.change.domain.ChangeSet;

public interface ChangeSetRepository extends JpaRepository<ChangeSet, UUID> {
}
