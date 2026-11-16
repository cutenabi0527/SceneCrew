package com.ssafy.scenecrew.account.repository;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.account.domain.AuthIdentity;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {
    @Query(value = """
            SELECT * FROM auth_identity
            WHERE provider = 'LOCAL' AND lower(btrim(email)) = :email
            """, nativeQuery = true)
    Optional<AuthIdentity> findLocalByNormalizedEmail(@Param("email") String email);
    /** One statement snapshot prevents old password + post-reset tokenVersion combinations. */
    @Query(value = """
            SELECT a.id AS "accountId", a.display_name AS "displayName", a.status AS "status",
                   a.signup_completed AS "signupCompleted", a.token_version AS "tokenVersion",
                   i.id AS "identityId", i.email AS "email", i.email_verified AS "emailVerified",
                   i.password_hash AS "passwordHash"
            FROM auth_identity i JOIN account a ON a.id=i.account_id
            WHERE i.provider='LOCAL' AND lower(btrim(i.email))=:email
            """, nativeQuery=true)
    Optional<LocalCredentials> findLocalCredentials(@Param("email") String email);

    interface LocalCredentials {
        UUID getAccountId();
        UUID getIdentityId();
        String getDisplayName();
        String getStatus();
        Boolean getSignupCompleted();
        Long getTokenVersion();
        String getEmail();
        Boolean getEmailVerified();
        String getPasswordHash();
    }
}
