package com.collabeditor.session.service;

import com.collabeditor.session.api.dto.SessionResponse;
import com.collabeditor.session.persistence.CodingSessionRepository;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionService {

    private static final String INVITE_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_CODE_LENGTH = 8;
    private static final Set<String> VALID_LANGUAGES = Set.of("JAVA", "PYTHON");

    private final CodingSessionRepository codingSessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final int participantCap;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(CodingSessionRepository codingSessionRepository,
                          SessionParticipantRepository participantRepository,
                          @Value("${app.session.participant-cap:12}") int participantCap) {
        this.codingSessionRepository = codingSessionRepository;
        this.participantRepository = participantRepository;
        this.participantCap = participantCap;
    }

    @Transactional
    public SessionResponse createSession(UUID userId, String language) {
        if (!VALID_LANGUAGES.contains(language)) {
            throw new InvalidLanguageException("Language must be JAVA or PYTHON");
        }

        String inviteCode = generateInviteCode();
        UUID sessionId = UUID.randomUUID();

        CodingSessionEntity session = new CodingSessionEntity(
                sessionId, inviteCode, language, userId, participantCap);
        codingSessionRepository.save(session);

        SessionParticipantEntity ownerParticipant = new SessionParticipantEntity(
                sessionId, userId, "OWNER", "ACTIVE");
        participantRepository.save(ownerParticipant);

        long activeCount = participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE");

        return toResponse(session, activeCount);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(UUID userId) {
        List<CodingSessionEntity> sessions = codingSessionRepository.findSessionsForUser(userId);
        return sessions.stream()
                .map(session -> {
                    long activeCount = participantRepository.countBySessionIdAndStatus(
                            session.getId(), "ACTIVE");
                    return toResponse(session, activeCount);
                })
                .toList();
    }

    @Transactional
    public SessionResponse joinSession(UUID userId, String inviteCode) {
        CodingSessionEntity session = codingSessionRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new SessionNotFoundException("Session not found for invite code: " + inviteCode));

        // Idempotent: if already active, return existing session
        var existing = participantRepository.findBySessionIdAndUserId(session.getId(), userId);
        if (existing.isPresent() && "ACTIVE".equals(existing.get().getStatus())) {
            long activeCount = participantRepository.countBySessionIdAndStatus(session.getId(), "ACTIVE");
            return toResponse(session, activeCount);
        }

        // Check participant cap
        long activeCount = participantRepository.countBySessionIdAndStatus(session.getId(), "ACTIVE");
        if (activeCount >= participantCap) {
            throw new SessionFullException("Session is full (cap: " + participantCap + ")");
        }

        if (existing.isPresent()) {
            // Re-joining: reactivate the LEFT membership
            SessionParticipantEntity participant = existing.get();
            participant.setStatus("ACTIVE");
            participant.setLeftAt(null);
            participant.setJoinedAt(Instant.now());
            participantRepository.save(participant);
        } else {
            // New participant
            SessionParticipantEntity participant = new SessionParticipantEntity(
                    session.getId(), userId, "PARTICIPANT", "ACTIVE");
            participantRepository.save(participant);
        }

        // Clear empty-since / cleanup-after if the session was empty
        if (session.getEmptySince() != null) {
            session.setEmptySince(null);
            session.setCleanupAfter(null);
            codingSessionRepository.save(session);
        }

        long newActiveCount = participantRepository.countBySessionIdAndStatus(session.getId(), "ACTIVE");
        return toResponse(session, newActiveCount);
    }

    @Transactional
    public void leaveSession(UUID userId, UUID sessionId) {
        CodingSessionEntity session = codingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        SessionParticipantEntity participant = participantRepository
                .findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new SessionNotFoundException("Not a participant of session: " + sessionId));

        participant.setStatus("LEFT");
        participant.setLeftAt(Instant.now());
        participantRepository.save(participant);

        // Check remaining active participants
        List<SessionParticipantEntity> activeParticipants =
                participantRepository.findActiveBySessionIdOrdered(sessionId);

        if (activeParticipants.isEmpty()) {
            // Last participant left: set cleanup window
            session.setEmptySince(Instant.now());
            session.setCleanupAfter(Instant.now().plusSeconds(3600)); // 1 hour
            codingSessionRepository.save(session);
        } else if (session.getOwnerUserId().equals(userId)) {
            // Owner left with remaining participants: transfer ownership
            // Deterministic: earliest joined_at, then lexicographically smallest user_id (handled by query ORDER BY)
            SessionParticipantEntity newOwner = activeParticipants.get(0);
            newOwner.setRole("OWNER");
            participantRepository.save(newOwner);
            session.setOwnerUserId(newOwner.getUserId());
            codingSessionRepository.save(session);
        }
    }

    private String generateInviteCode() {
        // Generate invite code matching regex [A-Z2-9]{8} using SecureRandom
        StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            int idx = secureRandom.nextInt(INVITE_CODE_CHARS.length());
            sb.append(INVITE_CODE_CHARS.charAt(idx));
        }
        return sb.toString();
    }

    private SessionResponse toResponse(CodingSessionEntity session, long activeParticipants) {
        return new SessionResponse(
                session.getId(),
                session.getInviteCode(),
                session.getLanguage(),
                session.getOwnerUserId(),
                session.getParticipantCap(),
                activeParticipants,
                session.getCreatedAt()
        );
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) { super(message); }
    }

    public static class SessionFullException extends RuntimeException {
        public SessionFullException(String message) { super(message); }
    }

    public static class InvalidLanguageException extends RuntimeException {
        public InvalidLanguageException(String message) { super(message); }
    }
}
