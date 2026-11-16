package com.ssafy.scenecrew.account.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.account.domain.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
