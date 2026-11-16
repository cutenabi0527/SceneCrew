package com.ssafy.scenecrew.notification.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.notification.domain.NotificationDelivery;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {
}
