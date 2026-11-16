package com.ssafy.scenecrew.plan.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.plan.domain.Zone;

public interface ZoneRepository extends JpaRepository<Zone, UUID> {
}
