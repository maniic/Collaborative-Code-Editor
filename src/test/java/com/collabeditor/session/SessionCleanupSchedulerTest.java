package com.collabeditor.session;

import com.collabeditor.session.persistence.CodingSessionRepository;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import com.collabeditor.session.service.SessionCleanupScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionCleanupSchedulerTest {

    @Mock private CodingSessionRepository codingSessionRepository;
    @Mock private SessionParticipantRepository participantRepository;

    private SessionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SessionCleanupScheduler(codingSessionRepository, participantRepository);
    }

    @Test
    void shouldDeleteExpiredEmptySession() {
        UUID sessionId = UUID.randomUUID();
        CodingSessionEntity expiredSession = new CodingSessionEntity(
                sessionId, "EXPD1234", "JAVA", UUID.randomUUID(), 12);
        expiredSession.setEmptySince(Instant.now().minusSeconds(7200));
        expiredSession.setCleanupAfter(Instant.now().minusSeconds(3600));

        when(codingSessionRepository.findExpiredEmptySessions(any(Instant.class)))
                .thenReturn(List.of(expiredSession));
        when(participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE"))
                .thenReturn(0L);

        scheduler.cleanupExpiredEmptySessions();

        verify(codingSessionRepository).delete(expiredSession);
    }

    @Test
    void shouldNotDeleteSessionWithActiveParticipants() {
        UUID sessionId = UUID.randomUUID();
        CodingSessionEntity session = new CodingSessionEntity(
                sessionId, "ACTV5678", "PYTHON", UUID.randomUUID(), 12);
        session.setEmptySince(Instant.now().minusSeconds(7200));
        session.setCleanupAfter(Instant.now().minusSeconds(3600));

        when(codingSessionRepository.findExpiredEmptySessions(any(Instant.class)))
                .thenReturn(List.of(session));
        when(participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE"))
                .thenReturn(1L);
        when(codingSessionRepository.save(any(CodingSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scheduler.cleanupExpiredEmptySessions();

        verify(codingSessionRepository, never()).delete(any());
        verify(codingSessionRepository).save(session);
    }
}
