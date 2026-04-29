package com.example.demo.repository;

import com.example.demo.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
