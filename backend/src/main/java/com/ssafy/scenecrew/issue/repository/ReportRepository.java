package com.ssafy.scenecrew.issue.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.issue.domain.Report;

public interface ReportRepository extends JpaRepository<Report, UUID> {
}
