package com.collabeditor.websocket;

import com.collabeditor.websocket.model.SelectionRange;
import com.collabeditor.websocket.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceServiceTest {

    private PresenceService presenceService;

    private final UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID peerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String email = "user@example.com";
    private final String peerEmail = "peer@example.com";

    @BeforeEach
    void setUp() {
        presenceService = new PresenceService(75);
    }

    @Nested
    @DisplayName("Participant join/leave tracking")
    class JoinLeave {

        @Test
        @DisplayName("joining a session stores participant identity")
        void joinStoresParticipant() {
            presenceService.join(sessionId, userId, email);

            var participants = presenceService.getParticipants(sessionId);
            assertThat(participants).hasSize(1);
            assertThat(participants.get(0).userId()).isEqualTo(userId);
            assertThat(participants.get(0).email()).isEqualTo(email);
        }

        @Test
        @DisplayName("leaving a session removes participant")
        void leaveRemovesParticipant() {
            presenceService.join(sessionId, userId, email);
            presenceService.leave(sessionId, userId);

            var participants = presenceService.getParticipants(sessionId);
            assertThat(participants).isEmpty();
        }

        @Test
        @DisplayName("multiple participants tracked per session")
        void multipleParticipants() {
            presenceService.join(sessionId, userId, email);
            presenceService.join(sessionId, peerId, peerEmail);

            var participants = presenceService.getParticipants(sessionId);
            assertThat(participants).hasSize(2);
        }

        @Test
        @DisplayName("leaving a non-existent participant is a no-op")
        void leaveNonExistentNoOp() {
            presenceService.leave(sessionId, userId);
            // Should not throw
        }
    }

    @Nested
    @DisplayName("Selection range storage")
    class SelectionRangeStorage {

        @Test
        @DisplayName("update stores selection range for participant")
        void updateStoresRange() {
            presenceService.join(sessionId, userId, email);
            SelectionRange range = new SelectionRange(5, 10);
            presenceService.updateSelection(sessionId, userId, range);

            var stored = presenceService.getSelection(sessionId, userId);
            assertThat(stored).isNotNull();
            assertThat(stored.start()).isEqualTo(5);
            assertThat(stored.end()).isEqualTo(10);
        }

        @Test
        @DisplayName("caret represented as zero-length range (start == end)")
        void caretIsZeroLengthRange() {
            presenceService.join(sessionId, userId, email);
            SelectionRange caret = new SelectionRange(7, 7);
            presenceService.updateSelection(sessionId, userId, caret);

            var stored = presenceService.getSelection(sessionId, userId);
            assertThat(stored.start()).isEqualTo(stored.end());
        }

        @Test
        @DisplayName("selection range is null before any update")
        void selectionNullBeforeUpdate() {
            presenceService.join(sessionId, userId, email);
            var stored = presenceService.getSelection(sessionId, userId);
            assertThat(stored).isNull();
        }
    }

    @Nested
    @DisplayName("Cursor throttling")
    class CursorThrottling {

        @Test
        @DisplayName("first update is always broadcastable")
        void firstUpdateBroadcastable() {
            presenceService.join(sessionId, userId, email);
            SelectionRange range = new SelectionRange(0, 0);
            boolean should = presenceService.shouldBroadcast(sessionId, userId);
            assertThat(should).isTrue();
        }

        @Test
        @DisplayName("immediate second update is throttled")
        void immediateSecondThrottled() {
            presenceService.join(sessionId, userId, email);
            presenceService.markBroadcast(sessionId, userId);

            boolean should = presenceService.shouldBroadcast(sessionId, userId);
            assertThat(should).isFalse();
        }

        @Test
        @DisplayName("update after throttle window passes is broadcastable")
        void afterThrottleWindowBroadcastable() throws InterruptedException {
            // Use a very short throttle for testing
            PresenceService shortThrottle = new PresenceService(10);
            shortThrottle.join(sessionId, userId, email);
            shortThrottle.markBroadcast(sessionId, userId);

            Thread.sleep(15);

            boolean should = shortThrottle.shouldBroadcast(sessionId, userId);
            assertThat(should).isTrue();
        }
    }
}
