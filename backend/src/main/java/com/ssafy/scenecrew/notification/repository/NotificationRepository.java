package com.ssafy.scenecrew.notification.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.notification.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, NotificationInbox {
}
