package com.ssafy.scenecrew.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID workGroupId;

    @Column(nullable = false)
    private UUID senderMemberId;

    @Column(nullable = false)
    private Long sequence;

    @Column(nullable = false, columnDefinition = "text")
    private String clientMessageId;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(nullable = false)
    private Instant createdAt;
}
