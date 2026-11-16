package com.ssafy.scenecrew.issue.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.issue.domain.Issue;

public interface IssueRepository extends JpaRepository<Issue, UUID> {
}
