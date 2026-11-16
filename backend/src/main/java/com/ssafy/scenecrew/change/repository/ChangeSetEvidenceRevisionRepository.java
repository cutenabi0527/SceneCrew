package com.ssafy.scenecrew.change.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.change.domain.ChangeSetEvidenceRevision;
import com.ssafy.scenecrew.change.domain.ChangeSetEvidenceRevisionId;

public interface ChangeSetEvidenceRevisionRepository
        extends JpaRepository<ChangeSetEvidenceRevision, ChangeSetEvidenceRevisionId> {
}
