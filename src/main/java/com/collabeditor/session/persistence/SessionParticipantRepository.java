package com.collabeditor.session.persistence;

import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.session.persistence.entity.SessionParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionParticipantRepository extends JpaRepository<SessionParticipantEntity, SessionParticipantId> {

    @Query("""
        SELECT sp FROM SessionParticipantEntity sp
        WHERE sp.sessionId = :sessionId AND sp.status = 'ACTIVE'
        ORDER BY sp.joinedAt ASC, sp.userId ASC
        """)
    List<SessionParticipantEntity> findActiveBySessionIdOrdered(@Param("sessionId") UUID sessionId);

    long countBySessionIdAndStatus(UUID sessionId, String status);

    Optional<SessionParticipantEntity> findBySessionIdAndUserId(UUID sessionId, UUID userId);
}
