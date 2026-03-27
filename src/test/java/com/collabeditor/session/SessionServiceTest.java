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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void shouldJoinAndLeaveWithOwnerTransferAndOneHourRetention() {
        UUID ownerId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String inviteCode = "ABCD2345";

        CodingSessionEntity session = new CodingSessionEntity(sessionId, inviteCode, "JAVA", ownerId, 12);

        // --- Join by invite code ---
        when(codingSessionRepository.findByInviteCode(inviteCode)).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndUserId(sessionId, joinerId)).thenReturn(Optional.empty());
        when(participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE")).thenReturn(1L, 2L);
        when(participantRepository.save(any(SessionParticipantEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionResponse joinResponse = sessionService.joinSession(joinerId, inviteCode);
        assertThat(joinResponse.sessionId()).isEqualTo(sessionId);
        assertThat(joinResponse.inviteCode()).isEqualTo(inviteCode);

        // Verify participant was saved
        ArgumentCaptor<SessionParticipantEntity> captor = ArgumentCaptor.forClass(SessionParticipantEntity.class);
        verify(participantRepository).save(captor.capture());
        SessionParticipantEntity joinedParticipant = captor.getValue();
        assertThat(joinedParticipant.getRole()).isEqualTo("PARTICIPANT");
        assertThat(joinedParticipant.getStatus()).isEqualTo("ACTIVE");

        // --- Owner leaves: transfer ownership to joiner ---
        reset(codingSessionRepository, participantRepository);

        when(codingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        SessionParticipantEntity ownerParticipant = new SessionParticipantEntity(sessionId, ownerId, "OWNER", "ACTIVE");
        ownerParticipant.setJoinedAt(Instant.now().minusSeconds(100));
        when(participantRepository.findBySessionIdAndUserId(sessionId, ownerId)).thenReturn(Optional.of(ownerParticipant));

        SessionParticipantEntity joinerParticipant = new SessionParticipantEntity(sessionId, joinerId, "PARTICIPANT", "ACTIVE");
        joinerParticipant.setJoinedAt(Instant.now().minusSeconds(50));
        when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of(joinerParticipant));
        when(participantRepository.save(any(SessionParticipantEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codingSessionRepository.save(any(CodingSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        sessionService.leaveSession(ownerId, sessionId);

        // Verify ownership transferred
        assertThat(joinerParticipant.getRole()).isEqualTo("OWNER");
        assertThat(session.getOwnerUserId()).isEqualTo(joinerId);

        // --- Last participant leaves: 1-hour retention ---
        reset(codingSessionRepository, participantRepository);

        when(codingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndUserId(sessionId, joinerId)).thenReturn(Optional.of(joinerParticipant));
        when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of());
        when(participantRepository.save(any(SessionParticipantEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codingSessionRepository.save(any(CodingSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        sessionService.leaveSession(joinerId, sessionId);

        // Verify session has emptySince and cleanupAfter set
        assertThat(session.getEmptySince()).isNotNull();
        assertThat(session.getCleanupAfter()).isNotNull();
        // cleanupAfter should be approximately 1 hour after emptySince
        assertThat(session.getCleanupAfter()).isAfter(session.getEmptySince());
        long diffSeconds = session.getCleanupAfter().getEpochSecond() - session.getEmptySince().getEpochSecond();
        assertThat(diffSeconds).isBetween(3590L, 3610L); // approximately 1 hour
    }

    @Test
    void shouldRejectJoinWhenRoomIsFull() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String inviteCode = "FULL2345";

        CodingSessionEntity session = new CodingSessionEntity(sessionId, inviteCode, "PYTHON", UUID.randomUUID(), 12);
        when(codingSessionRepository.findByInviteCode(inviteCode)).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());
        when(participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE")).thenReturn(12L);

        assertThatThrownBy(() -> sessionService.joinSession(userId, inviteCode))
                .isInstanceOf(SessionService.SessionFullException.class);
    }

    @Test
    void shouldClearCleanupWindowWhenParticipantRejoins() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String inviteCode = "REJN5678";

        CodingSessionEntity session = new CodingSessionEntity(sessionId, inviteCode, "JAVA", UUID.randomUUID(), 12);
        session.setEmptySince(Instant.now().minusSeconds(600));
        session.setCleanupAfter(Instant.now().plusSeconds(3000));

        SessionParticipantEntity leftParticipant = new SessionParticipantEntity(sessionId, userId, "PARTICIPANT", "LEFT");
        leftParticipant.setLeftAt(Instant.now().minusSeconds(600));

        when(codingSessionRepository.findByInviteCode(inviteCode)).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndUserId(sessionId, userId)).thenReturn(Optional.of(leftParticipant));
        when(participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE")).thenReturn(0L, 1L);
        when(participantRepository.save(any(SessionParticipantEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codingSessionRepository.save(any(CodingSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionResponse response = sessionService.joinSession(userId, inviteCode);

        assertThat(session.getEmptySince()).isNull();
        assertThat(session.getCleanupAfter()).isNull();
        assertThat(leftParticipant.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldRejectMalformedInviteCodeBeforeLookup() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> sessionService.joinSession(userId, "bad-code"))
                .isInstanceOf(SessionService.InvalidInviteCodeException.class)
                .hasMessageContaining("[A-Z2-9]{8}");

        verify(codingSessionRepository, never()).findByInviteCode(anyString());
    }

    @Test
    void shouldNormalizeUppercaseInviteCodeBeforeLookup() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CodingSessionEntity session = new CodingSessionEntity(sessionId, "JOIN2345", "JAVA", UUID.randomUUID(), 12);

        when(codingSessionRepository.findByInviteCode("JOIN2345")).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());
        when(participantRepository.countBySessionIdAndStatus(sessionId, "ACTIVE")).thenReturn(0L, 1L);
        when(participantRepository.save(any(SessionParticipantEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionResponse response = sessionService.joinSession(userId, "join2345");

        assertThat(response.inviteCode()).isEqualTo("JOIN2345");
        verify(codingSessionRepository).findByInviteCode("JOIN2345");
    }

    @Test
    void shouldRetryInviteCodeCollisionUntilUniqueCodeIsAvailable() {
        UUID userId = UUID.randomUUID();
        SessionService collisionAwareService = new SessionService(codingSessionRepository, participantRepository, 12) {
            private final java.util.Iterator<String> inviteCodes =
                    java.util.List.of("COLL2222", "UNIQ2222").iterator();

            @Override
            protected String generateInviteCode() {
                return inviteCodes.next();
            }
        };

        when(codingSessionRepository.existsByInviteCode("COLL2222")).thenReturn(true);
        when(codingSessionRepository.existsByInviteCode("UNIQ2222")).thenReturn(false);
        when(codingSessionRepository.save(any(CodingSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.save(any(SessionParticipantEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.countBySessionIdAndStatus(any(), eq("ACTIVE")))
                .thenReturn(1L);

        SessionResponse created = collisionAwareService.createSession(userId, "JAVA");

        assertThat(created.inviteCode()).isEqualTo("UNIQ2222");
        verify(codingSessionRepository).existsByInviteCode("COLL2222");
        verify(codingSessionRepository).existsByInviteCode("UNIQ2222");
    }
}
