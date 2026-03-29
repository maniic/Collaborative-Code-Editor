package com.collabeditor.ot.persistence;

import com.collabeditor.ot.persistence.entity.SessionOperationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionOperationRepository extends JpaRepository<SessionOperationEntity, UUID> {

    List<SessionOperationEntity> findBySessionIdAndRevisionGreaterThanOrderByRevisionAsc(UUID sessionId, long revision);

    Optional<SessionOperationEntity> findTopBySessionIdOrderByRevisionDesc(UUID sessionId);

    Optional<SessionOperationEntity> findBySessionIdAndRevision(UUID sessionId, long revision);
}
