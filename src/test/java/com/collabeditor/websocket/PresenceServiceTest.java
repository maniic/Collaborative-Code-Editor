package com.collabeditor.websocket;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.InsertOperation;
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

    @Nested
    @DisplayName("Selection range transformation through canonical operations")
    class SelectionRangeTransformation {

        @Test
        @DisplayName("insert before range shifts both edges right by inserted text length")
        void insertBeforeRangeShiftsRight() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(5, 10));

            InsertOperation insert = new InsertOperation(peerId, 0, "op-1", 2, "abc");
            presenceService.transformSelectionsForSession(sessionId, insert);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(8);  // 5 + 3
            assertThat(result.end()).isEqualTo(13);    // 10 + 3
        }

        @Test
        @DisplayName("insert after range leaves range unchanged")
        void insertAfterRangeUnchanged() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(2, 5));

            InsertOperation insert = new InsertOperation(peerId, 0, "op-1", 10, "xyz");
            presenceService.transformSelectionsForSession(sessionId, insert);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(2);
            assertThat(result.end()).isEqualTo(5);
        }

        @Test
        @DisplayName("insert inside range shifts end by inserted text length")
        void insertInsideRangeShiftsEnd() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(3, 8));

            InsertOperation insert = new InsertOperation(peerId, 0, "op-1", 5, "ab");
            presenceService.transformSelectionsForSession(sessionId, insert);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(3);
            assertThat(result.end()).isEqualTo(10);  // 8 + 2
        }

        @Test
        @DisplayName("delete before range shifts both edges left by delete length")
        void deleteBeforeRangeShiftsLeft() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(10, 15));

            DeleteOperation delete = new DeleteOperation(peerId, 0, "op-1", 2, 3);
            presenceService.transformSelectionsForSession(sessionId, delete);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(7);   // 10 - 3
            assertThat(result.end()).isEqualTo(12);    // 15 - 3
        }

        @Test
        @DisplayName("delete after range leaves range unchanged")
        void deleteAfterRangeUnchanged() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(2, 5));

            DeleteOperation delete = new DeleteOperation(peerId, 0, "op-1", 10, 3);
            presenceService.transformSelectionsForSession(sessionId, delete);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(2);
            assertThat(result.end()).isEqualTo(5);
        }

        @Test
        @DisplayName("delete overlapping range start clamps start to delete start")
        void deleteOverlappingRangeStartClampsStart() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(5, 10));

            // Delete [3, 7) -- overlaps start of range
            DeleteOperation delete = new DeleteOperation(peerId, 0, "op-1", 3, 4);
            presenceService.transformSelectionsForSession(sessionId, delete);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(3);  // clamped to delete start
            assertThat(result.end()).isEqualTo(6);    // 10 - 4
        }

        @Test
        @DisplayName("delete overlapping range end clamps end to delete start")
        void deleteOverlappingRangeEndClampsEnd() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(3, 8));

            // Delete [6, 12) -- overlaps end of range
            DeleteOperation delete = new DeleteOperation(peerId, 0, "op-1", 6, 6);
            presenceService.transformSelectionsForSession(sessionId, delete);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(3);
            assertThat(result.end()).isEqualTo(6);    // clamped to delete start
        }

        @Test
        @DisplayName("delete containing entire range collapses to zero-length at delete start")
        void deleteContainingRangeCollapsesToCaret() {
            presenceService.join(sessionId, userId, email);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(5, 8));

            // Delete [3, 12) -- contains entire range
            DeleteOperation delete = new DeleteOperation(peerId, 0, "op-1", 3, 9);
            presenceService.transformSelectionsForSession(sessionId, delete);

            SelectionRange result = presenceService.getSelection(sessionId, userId);
            assertThat(result.start()).isEqualTo(3);
            assertThat(result.end()).isEqualTo(3);  // collapsed to caret at delete start
        }

        @Test
        @DisplayName("participants without selection ranges are unaffected by transforms")
        void noSelectionUnaffectedByTransform() {
            presenceService.join(sessionId, userId, email);
            // No selection set

            InsertOperation insert = new InsertOperation(peerId, 0, "op-1", 0, "hello");
            presenceService.transformSelectionsForSession(sessionId, insert);

            assertThat(presenceService.getSelection(sessionId, userId)).isNull();
        }

        @Test
        @DisplayName("transform updates multiple participants in the same session")
        void transformUpdatesMultipleParticipants() {
            presenceService.join(sessionId, userId, email);
            presenceService.join(sessionId, peerId, peerEmail);
            presenceService.updateSelection(sessionId, userId, new SelectionRange(5, 10));
            presenceService.updateSelection(sessionId, peerId, new SelectionRange(2, 4));

            InsertOperation insert = new InsertOperation(
                    UUID.fromString("44444444-4444-4444-4444-444444444444"), 0, "op-1", 3, "xx");
            presenceService.transformSelectionsForSession(sessionId, insert);

            SelectionRange userRange = presenceService.getSelection(sessionId, userId);
            assertThat(userRange.start()).isEqualTo(7);  // 5 + 2
            assertThat(userRange.end()).isEqualTo(12);   // 10 + 2

            SelectionRange peerRange = presenceService.getSelection(sessionId, peerId);
            assertThat(peerRange.start()).isEqualTo(2);
            assertThat(peerRange.end()).isEqualTo(6);    // 4 + 2 (insert inside range)
        }
    }
}
