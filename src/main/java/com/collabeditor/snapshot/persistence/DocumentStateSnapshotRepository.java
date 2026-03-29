package com.collabeditor.snapshot.persistence;

import com.collabeditor.snapshot.persistence.entity.DocumentStateSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentStateSnapshotRepository extends JpaRepository<DocumentStateSnapshotEntity, UUID> {

    Optional<DocumentStateSnapshotEntity> findTopBySessionIdOrderByRevisionDesc(UUID sessionId);

    Optional<DocumentStateSnapshotEntity> findTopBySessionIdAndRevisionLessThanEqualOrderByRevisionDesc(UUID sessionId, long revision);
}
