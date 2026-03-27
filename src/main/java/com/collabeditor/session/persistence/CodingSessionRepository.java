package com.collabeditor.session.persistence;

import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodingSessionRepository extends JpaRepository<CodingSessionEntity, UUID> {

    Optional<CodingSessionEntity> findByInviteCode(String inviteCode);

    @Query("""
        SELECT cs FROM CodingSessionEntity cs
        WHERE cs.ownerUserId = :userId
           OR cs.id IN (
               SELECT sp.sessionId FROM SessionParticipantEntity sp
               WHERE sp.userId = :userId AND sp.status = 'ACTIVE'
           )
        """)
    List<CodingSessionEntity> findSessionsForUser(@Param("userId") UUID userId);

    @Query("""
        SELECT cs FROM CodingSessionEntity cs
        WHERE cs.cleanupAfter IS NOT NULL AND cs.cleanupAfter <= :now
        """)
    List<CodingSessionEntity> findExpiredEmptySessions(@Param("now") Instant now);
}
