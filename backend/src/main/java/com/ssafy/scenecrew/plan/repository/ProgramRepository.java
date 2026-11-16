package com.ssafy.scenecrew.plan.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.plan.domain.Program;

public interface ProgramRepository extends JpaRepository<Program, UUID> {
}
