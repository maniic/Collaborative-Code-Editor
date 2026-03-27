package com.collabeditor.auth.persistence;

import com.collabeditor.auth.persistence.entity.RefreshSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSessionEntity, UUID> {

    Optional<RefreshSessionEntity> findByTokenHash(String tokenHash);

    @Query("SELECT rs FROM RefreshSessionEntity rs WHERE rs.userId = :userId AND rs.revokedAt IS NULL")
    List<RefreshSessionEntity> findActiveByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE RefreshSessionEntity rs SET rs.revokedAt = CURRENT_TIMESTAMP WHERE rs.userId = :userId AND rs.deviceId = :deviceId AND rs.revokedAt IS NULL")
    int revokeByUserIdAndDeviceId(@Param("userId") UUID userId, @Param("deviceId") UUID deviceId);
}
