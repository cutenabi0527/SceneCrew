package com.ssafy.scenecrew.change.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 변경안과 그 revision의 키. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeSetEvidenceRevisionId implements Serializable {
    @Column(nullable = false)
    private UUID changeSetId;

    @Column(nullable = false)
    private Long revision;
}
