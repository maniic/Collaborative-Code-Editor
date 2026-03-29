package com.collabeditor.execution.persistence;

import com.collabeditor.execution.persistence.entity.ExecutionHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionHistoryRepository extends JpaRepository<ExecutionHistoryEntity, UUID> {

    List<ExecutionHistoryEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    Optional<ExecutionHistoryEntity> findByIdAndSessionId(UUID id, UUID sessionId);
}
