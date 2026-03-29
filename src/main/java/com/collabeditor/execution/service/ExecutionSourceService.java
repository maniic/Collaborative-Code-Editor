package com.collabeditor.execution.service;

import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.UserEntity;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.session.persistence.CodingSessionRepository;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.snapshot.service.SnapshotRecoveryService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Captures a canonical room-source snapshot for execution.
 *
 * <p>Validates that the requester is an ACTIVE participant, loads the persisted
 * canonical document and revision from {@link SnapshotRecoveryService}, and
 * resolves the requester email from the user repository.
 */
@Service
public class ExecutionSourceService {

    private final CodingSessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final SnapshotRecoveryService snapshotRecoveryService;

    public ExecutionSourceService(CodingSessionRepository sessionRepository,
                                   SessionParticipantRepository participantRepository,
                                   UserRepository userRepository,
                                   SnapshotRecoveryService snapshotRecoveryService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.snapshotRecoveryService = snapshotRecoveryService;
    }

    /**
     * Captures the canonical room source for an execution request.
     *
     * <p>Validates session existence, active participant membership, and then
     * reads the canonical document/revision from the snapshot recovery service.
     *
     * @param sessionId         the coding session to execute against
     * @param requestedByUserId the user requesting execution
     * @return an immutable snapshot of the room state at capture time
     * @throws IllegalArgumentException if the session does not exist
     * @throws IllegalStateException    if the requester is not an ACTIVE participant
     */
    public ExecutionSourceSnapshot capture(UUID sessionId, UUID requestedByUserId) {
        // Validate session exists
        CodingSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session not found: " + sessionId));

        // Validate requester is an ACTIVE participant
        SessionParticipantEntity participant = participantRepository
                .findBySessionIdAndUserId(sessionId, requestedByUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + requestedByUserId + " is not a participant of session " + sessionId));

        if (!"ACTIVE".equals(participant.getStatus())) {
            throw new IllegalStateException(
                    "User " + requestedByUserId + " is not an ACTIVE participant of session " + sessionId);
        }

        // Resolve requester email
        UserEntity user = userRepository.findById(requestedByUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found: " + requestedByUserId));

        // Read the canonical document and revision
        CollaborationSessionRuntime runtime = snapshotRecoveryService.loadRuntime(sessionId);
        DocumentSnapshot snapshot = runtime.snapshot();

        return new ExecutionSourceSnapshot(
                sessionId,
                requestedByUserId,
                user.getEmail(),
                session.getLanguage(),
                snapshot.revision(),
                snapshot.document()
        );
    }
}
