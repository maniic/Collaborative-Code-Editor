package com.collabeditor.session;

import com.collabeditor.session.api.dto.SessionResponse;
import com.collabeditor.session.persistence.CodingSessionRepository;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.session.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock private CodingSessionRepository codingSessionRepository;
    @Mock private SessionParticipantRepository participantRepository;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(codingSessionRepository, participantRepository, 12);
    }

    @Test
    void shouldCreateSessionAndListOnlyOwnedOrJoined() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        // Stub save to return the entity passed in
        when(codingSessionRepository.save(any(CodingSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.save(any(SessionParticipantEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.countBySessionIdAndStatus(any(), eq("ACTIVE")))
                .thenReturn(1L);

        // Create a session
        SessionResponse created = sessionService.createSession(ownerId, "JAVA");

        // Verify session has UUID and invite code matching [A-Z2-9]{8}
        assertThat(created.sessionId()).isNotNull();
        assertThat(created.inviteCode()).matches("[A-Z2-9]{8}");
        assertThat(created.language()).isEqualTo("JAVA");
        assertThat(created.ownerUserId()).isEqualTo(ownerId);
        assertThat(created.participantCap()).isEqualTo(12);

        // Verify owner participant membership was created
        ArgumentCaptor<SessionParticipantEntity> participantCaptor =
                ArgumentCaptor.forClass(SessionParticipantEntity.class);
        verify(participantRepository).save(participantCaptor.capture());
        SessionParticipantEntity savedParticipant = participantCaptor.getValue();
        assertThat(savedParticipant.getUserId()).isEqualTo(ownerId);
        assertThat(savedParticipant.getRole()).isEqualTo("OWNER");
        assertThat(savedParticipant.getStatus()).isEqualTo("ACTIVE");

        // Verify session entity was persisted with correct owner
        ArgumentCaptor<CodingSessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(CodingSessionEntity.class);
        verify(codingSessionRepository).save(sessionCaptor.capture());
        CodingSessionEntity savedSession = sessionCaptor.getValue();
        assertThat(savedSession.getOwnerUserId()).isEqualTo(ownerId);
        assertThat(savedSession.getLanguage()).isEqualTo("JAVA");

        // List sessions returns only sessions for the user
        CodingSessionEntity sessionEntity = new CodingSessionEntity(
                created.sessionId(), created.inviteCode(), "JAVA", ownerId, 12);
        when(codingSessionRepository.findSessionsForUser(ownerId))
                .thenReturn(List.of(sessionEntity));
        when(codingSessionRepository.findSessionsForUser(otherUserId))
                .thenReturn(List.of());
        when(participantRepository.countBySessionIdAndStatus(created.sessionId(), "ACTIVE"))
                .thenReturn(1L);

        List<SessionResponse> ownerSessions = sessionService.listSessions(ownerId);
        assertThat(ownerSessions).hasSize(1);
        assertThat(ownerSessions.get(0).sessionId()).isEqualTo(created.sessionId());

        List<SessionResponse> otherSessions = sessionService.listSessions(otherUserId);
        assertThat(otherSessions).isEmpty();
    }
}
