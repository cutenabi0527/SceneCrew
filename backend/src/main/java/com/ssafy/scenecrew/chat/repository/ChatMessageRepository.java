package com.ssafy.scenecrew.chat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.chat.domain.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
}
