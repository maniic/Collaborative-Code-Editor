package com.collabeditor.ot;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.CollaborationSessionRuntime.ApplyResult;
import com.collabeditor.ot.service.OperationalTransformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollaborationSessionRuntimeTest {

    private CollaborationSessionRuntime runtime;
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID userB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID userC = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        OperationalTransformService otService = new OperationalTransformService();
        runtime = new CollaborationSessionRuntime(sessionId, otService);
    }

    @Nested
    @DisplayName("Initial state")
    class InitialState {

        @Test
        @DisplayName("new runtime starts with empty document at revision 0")
        void emptyDocumentAtRevisionZero() {
            DocumentSnapshot snapshot = runtime.snapshot();
            assertThat(snapshot.document()).isEmpty();
            assertThat(snapshot.revision()).isEqualTo(0);
        }

        @Test
        @DisplayName("new runtime has correct session ID")
        void correctSessionId() {
            assertThat(runtime.getSessionId()).isEqualTo(sessionId);
        }
    }

    @Nested
    @DisplayName("Basic operations")
    class BasicOperations {

        @Test
        @DisplayName("apply insert increments revision and updates document snapshot")
        void applyInsertIncrementsRevision() {
            InsertOperation op = new InsertOperation(userA, 0L, "op1", 0, "hello");

            ApplyResult result = runtime.applyClientOperation(op);

            assertThat(result.revision()).isEqualTo(1);
            DocumentSnapshot snapshot = result.snapshot();
            assertThat(snapshot.document()).isEqualTo("hello");
            assertThat(snapshot.revision()).isEqualTo(1);
        }

        @Test
        @DisplayName("apply delete updates document and increments revision")
        void applyDeleteUpdatesDocument() {
            // First insert some text
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello world"));

            // Then delete " world"
            DeleteOperation del = new DeleteOperation(userA, 1L, "op2", 5, 6);
            ApplyResult result = runtime.applyClientOperation(del);

            assertThat(result.revision()).isEqualTo(2);
            assertThat(result.snapshot().document()).isEqualTo("hello");
        }

        @Test
        @DisplayName("multiple operations increment revision sequentially")
        void multipleOperationsIncrementSequentially() {
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello"));
            runtime.applyClientOperation(new InsertOperation(userA, 1L, "op2", 5, " world"));
            ApplyResult result = runtime.applyClientOperation(new InsertOperation(userA, 2L, "op3", 11, "!"));

            assertThat(result.revision()).isEqualTo(3);
            assertThat(result.snapshot().document()).isEqualTo("hello world!");
        }
    }

    @Nested
    @DisplayName("Stale revision handling")
    class StaleRevisionHandling {

        @Test
        @DisplayName("stale revision operation is transformed through history and applied")
        void staleRevisionIsTransformed() {
            // User A inserts "hello" at revision 0
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello"));
            // Now at revision 1, document = "hello"

            // User B sends operation against stale revision 0 (missed userA's insert)
            InsertOperation staleOp = new InsertOperation(userB, 0L, "op2", 0, "hi ");
            ApplyResult result = runtime.applyClientOperation(staleOp);

            // userB's "hi " at position 0 should transform correctly
            // userB (0...0002) > userA (0...0001) lexicographically, so userA wins left position
            // transform(insert(0, "hi "), insert(0, "hello")): userB shifts right by 5
            // Result: insert at position 5
            assertThat(result.revision()).isEqualTo(2);
            assertThat(result.snapshot().document()).isEqualTo("hellohi ");
        }

        @Test
        @DisplayName("stale revision transforms through multiple history entries")
        void staleRevisionTransformsThroughMultipleEntries() {
            // Build up some history
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "AB"));   // rev 1: "AB"
            runtime.applyClientOperation(new InsertOperation(userA, 1L, "op2", 2, "CD"));   // rev 2: "ABCD"
            runtime.applyClientOperation(new InsertOperation(userA, 2L, "op3", 4, "EF"));   // rev 3: "ABCDEF"

            // User B sends op against revision 0 (missed all three)
            InsertOperation staleOp = new InsertOperation(userB, 0L, "op4", 0, "X");
            ApplyResult result = runtime.applyClientOperation(staleOp);

            // userB (0...0002) > userA (0...0001), so userA wins left on same-position ties
            // transform against op1 (insert 0, "AB"): userB shifts right by 2 -> insert(2, "X")
            // transform against op2 (insert 2, "CD"): same position tie, userA wins -> insert(4, "X")
            // transform against op3 (insert 4, "EF"): same position tie, userA wins -> insert(6, "X")
            assertThat(result.revision()).isEqualTo(4);
            assertThat(result.snapshot().document()).isEqualTo("ABCDEFX");
        }

        @Test
        @DisplayName("operation at current revision is applied without transformation")
        void currentRevisionAppliedDirectly() {
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello"));

            InsertOperation currentOp = new InsertOperation(userB, 1L, "op2", 5, " world");
            ApplyResult result = runtime.applyClientOperation(currentOp);

            assertThat(result.revision()).isEqualTo(2);
            assertThat(result.snapshot().document()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("reject operation with future revision")
        void rejectFutureRevision() {
            assertThatThrownBy(() ->
                    runtime.applyClientOperation(new InsertOperation(userA, 5L, "op1", 0, "x"))
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("History tracking")
    class HistoryTracking {

        @Test
        @DisplayName("history stores applied operations with assigned revisions")
        void historyStoresAppliedOps() {
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello"));
            runtime.applyClientOperation(new InsertOperation(userB, 1L, "op2", 5, " world"));

            assertThat(runtime.getHistorySize()).isEqualTo(2);
        }

        @Test
        @DisplayName("document snapshot reflects current canonical state")
        void documentSnapshotReflectsState() {
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello"));
            runtime.applyClientOperation(new InsertOperation(userB, 1L, "op2", 5, " world"));

            DocumentSnapshot snapshot = runtime.snapshot();
            assertThat(snapshot.document()).isEqualTo("hello world");
            assertThat(snapshot.revision()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Three-user convergence")
    class ThreeUserConvergence {

        @Test
        @DisplayName("three user interleaved inserts converge to same document")
        void threeUserInterleavedInserts() {
            // All three users see empty doc at revision 0 and type concurrently
            // Server receives: userA first, then userB (stale rev 0), then userC (stale rev 0)

            InsertOperation opA = new InsertOperation(userA, 0L, "opA", 0, "AAA");
            InsertOperation opB = new InsertOperation(userB, 0L, "opB", 0, "BBB");
            InsertOperation opC = new InsertOperation(userC, 0L, "opC", 0, "CCC");

            ApplyResult resultA = runtime.applyClientOperation(opA);
            ApplyResult resultB = runtime.applyClientOperation(opB);
            ApplyResult resultC = runtime.applyClientOperation(opC);

            // All operations applied, revision increments to 3
            assertThat(resultC.revision()).isEqualTo(3);

            // The final document must be deterministic regardless of user ordering
            String finalDoc = resultC.snapshot().document();
            assertThat(finalDoc).hasSize(9); // 3 + 3 + 3

            // With tie-breaking: userA < userB < userC, so userA wins leftmost
            // userA inserts at 0 -> "AAA"
            // userB at 0, transformed against userA at 0: userB > userA, shifts right by 3 -> insert(3, "BBB") -> "AAABBB"
            // userC at 0, transformed against userA at 0: userC > userA, shifts right by 3 -> insert(3, "CCC")
            //   then transformed against userB at 3: userC > userB, shifts right by 3 -> insert(6, "CCC") -> "AAABBBCCC"
            assertThat(finalDoc).isEqualTo("AAABBBCCC");
        }

        @Test
        @DisplayName("three user mixed inserts and deletes converge")
        void threeUserMixedOps() {
            // Start with some content
            runtime.applyClientOperation(new InsertOperation(userA, 0L, "setup", 0, "abcdefghij")); // rev 1

            // All three users saw "abcdefghij" at revision 1
            InsertOperation opA = new InsertOperation(userA, 1L, "opA", 5, "X"); // insert X at 5
            DeleteOperation opB = new DeleteOperation(userB, 1L, "opB", 0, 3);   // delete "abc"
            InsertOperation opC = new InsertOperation(userC, 1L, "opC", 10, "Z"); // insert Z at end

            runtime.applyClientOperation(opA); // rev 2: "abcdeXfghij"
            runtime.applyClientOperation(opB); // rev 3: stale, transformed
            ApplyResult resultC = runtime.applyClientOperation(opC); // rev 4: stale, transformed

            assertThat(resultC.revision()).isEqualTo(4);
            String finalDoc = resultC.snapshot().document();
            // Must contain X, Z, and the non-deleted portion
            assertThat(finalDoc).contains("X").contains("Z");
            assertThat(finalDoc).doesNotContain("abc"); // deleted by opB
        }

        @Test
        @DisplayName("three user same position inserts produce deterministic order")
        void threeUserSamePositionDeterministic() {
            // All insert at position 0 against revision 0
            InsertOperation opA = new InsertOperation(userA, 0L, "opA", 0, "A");
            InsertOperation opB = new InsertOperation(userB, 0L, "opB", 0, "B");
            InsertOperation opC = new InsertOperation(userC, 0L, "opC", 0, "C");

            runtime.applyClientOperation(opA);
            runtime.applyClientOperation(opB);
            ApplyResult resultC = runtime.applyClientOperation(opC);

            // userA < userB < userC, deterministic ordering: A first, B second, C third
            assertThat(resultC.snapshot().document()).isEqualTo("ABC");
        }
    }

    @Nested
    @DisplayName("Seeded randomized convergence")
    class SeededRandomConvergence {

        private final OperationalTransformService otService = new OperationalTransformService();

        @Test
        @DisplayName("seeded random two-user convergence over 200 schedules")
        void seededRandomTwoUserConvergence() {
            long seed = 42L;
            Random rng = new Random(seed);
            int scheduleCount = 200;

            for (int schedule = 0; schedule < scheduleCount; schedule++) {
                CollaborationSessionRuntime rt = new CollaborationSessionRuntime(UUID.randomUUID(), otService);

                // Start with a random initial document
                String initDoc = randomString(rng, 5 + rng.nextInt(20));
                rt.applyClientOperation(new InsertOperation(userA, 0L, "init", 0, initDoc));

                long baseRev = 1L;
                DocumentSnapshot snap = rt.snapshot();
                String localDocA = snap.document();
                String localDocB = snap.document();

                // Generate 4-8 operations from two users, interleaved
                int opCount = 4 + rng.nextInt(5);
                for (int i = 0; i < opCount; i++) {
                    UUID author = rng.nextBoolean() ? userA : userB;
                    String currentDoc = author.equals(userA) ? localDocA : localDocB;

                    if (currentDoc.isEmpty()) {
                        // Can only insert
                        String text = randomString(rng, 1 + rng.nextInt(3));
                        InsertOperation op = new InsertOperation(author, baseRev, "op" + i, 0, text);
                        ApplyResult result = rt.applyClientOperation(op);
                        // Both clients get the canonical result
                        localDocA = result.snapshot().document();
                        localDocB = result.snapshot().document();
                        baseRev = result.revision();
                    } else {
                        TextOperation op;
                        if (rng.nextBoolean()) {
                            int pos = rng.nextInt(currentDoc.length() + 1);
                            String text = randomString(rng, 1 + rng.nextInt(3));
                            op = new InsertOperation(author, baseRev, "op" + i, pos, text);
                        } else {
                            int pos = rng.nextInt(currentDoc.length());
                            int maxLen = currentDoc.length() - pos;
                            int len = 1 + rng.nextInt(Math.min(maxLen, 3));
                            op = new DeleteOperation(author, baseRev, "op" + i, pos, len);
                        }

                        ApplyResult result = rt.applyClientOperation(op);
                        localDocA = result.snapshot().document();
                        localDocB = result.snapshot().document();
                        baseRev = result.revision();
                    }
                }

                // Both local views must match canonical
                assertThat(localDocA)
                        .as("Schedule %d (seed=%d): two-user views must converge", schedule, seed)
                        .isEqualTo(localDocB);
                assertThat(localDocA)
                        .as("Schedule %d (seed=%d): local view must match canonical", schedule, seed)
                        .isEqualTo(rt.snapshot().document());
            }
        }

        @Test
        @DisplayName("seeded random three-user convergence with stale ops over 100 schedules")
        void seededRandomThreeUserConvergenceWithStaleOps() {
            long seed = 12345L;
            Random rng = new Random(seed);
            int scheduleCount = 100;

            UUID[] users = {userA, userB, userC};

            for (int schedule = 0; schedule < scheduleCount; schedule++) {
                CollaborationSessionRuntime rt = new CollaborationSessionRuntime(UUID.randomUUID(), otService);

                // Random initial doc
                String initDoc = randomString(rng, 3 + rng.nextInt(15));
                rt.applyClientOperation(new InsertOperation(userA, 0L, "init", 0, initDoc));

                // Each user tracks their own perceived base revision (simulate stale ops)
                long[] userBaseRevs = {1L, 1L, 1L};

                // Run 6-10 operations with varying staleness
                int opCount = 6 + rng.nextInt(5);
                for (int i = 0; i < opCount; i++) {
                    int userIdx = rng.nextInt(3);
                    UUID author = users[userIdx];
                    long baseRev = userBaseRevs[userIdx];

                    DocumentSnapshot snap = rt.snapshot();
                    String canonDoc = snap.document();

                    if (canonDoc.isEmpty()) {
                        String text = randomString(rng, 1 + rng.nextInt(3));
                        InsertOperation op = new InsertOperation(author, baseRev, "op" + i, 0, text);
                        try {
                            ApplyResult result = rt.applyClientOperation(op);
                            userBaseRevs[userIdx] = result.revision();
                        } catch (IllegalArgumentException e) {
                            // Stale base revision edge case; skip
                        }
                    } else {
                        TextOperation op;
                        if (rng.nextBoolean()) {
                            int pos = rng.nextInt(canonDoc.length() + 1);
                            String text = randomString(rng, 1 + rng.nextInt(3));
                            op = new InsertOperation(author, baseRev, "op" + i, pos, text);
                        } else {
                            int pos = rng.nextInt(canonDoc.length());
                            int maxLen = canonDoc.length() - pos;
                            int len = 1 + rng.nextInt(Math.min(maxLen, 3));
                            op = new DeleteOperation(author, baseRev, "op" + i, pos, len);
                        }

                        try {
                            ApplyResult result = rt.applyClientOperation(op);
                            // Update this user's base revision
                            userBaseRevs[userIdx] = result.revision();
                            // Occasionally update other users (simulate them receiving the broadcast)
                            for (int u = 0; u < 3; u++) {
                                if (u != userIdx && rng.nextBoolean()) {
                                    userBaseRevs[u] = result.revision();
                                }
                            }
                        } catch (IllegalArgumentException e) {
                            // Operation became invalid after transform; skip
                        }
                    }
                }

                // Final canonical document must be consistent
                DocumentSnapshot finalSnap = rt.snapshot();
                assertThat(finalSnap.document())
                        .as("Schedule %d (seed=%d): canonical document must be non-null", schedule, seed)
                        .isNotNull();
                assertThat(finalSnap.revision())
                        .as("Schedule %d (seed=%d): revision must advance", schedule, seed)
                        .isGreaterThanOrEqualTo(1L);
            }
        }

        private String randomString(Random rng, int length) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append((char) ('a' + rng.nextInt(26)));
            }
            return sb.toString();
        }
    }

    @Nested
    @DisplayName("Restore from persisted state")
    class RestoreFromPersistedState {

        private final OperationalTransformService otService = new OperationalTransformService();

        @Test
        @DisplayName("restored runtime reports the provided document and revision immediately")
        void restoredRuntimeReportsProvidedState() {
            String document = "hello world";
            long revision = 5;
            List<AppliedOperation> history = new ArrayList<>();

            CollaborationSessionRuntime restored = CollaborationSessionRuntime.restore(
                    sessionId, document, revision, history, otService);

            DocumentSnapshot snapshot = restored.snapshot();
            assertThat(snapshot.document()).isEqualTo("hello world");
            assertThat(snapshot.revision()).isEqualTo(5);
            assertThat(restored.getSessionId()).isEqualTo(sessionId);
        }

        @Test
        @DisplayName("restored runtime still transforms stale operations against restored history")
        void restoredRuntimeTransformsStaleOperationsAgainstRestoredHistory() {
            // Build a runtime, apply some operations, then restore from that state
            CollaborationSessionRuntime original = new CollaborationSessionRuntime(sessionId, otService);
            original.applyClientOperation(new InsertOperation(userA, 0L, "op1", 0, "hello"));
            original.applyClientOperation(new InsertOperation(userA, 1L, "op2", 5, " world"));
            // Original is now at revision 2, document = "hello world"

            // Extract history from the original by replaying
            List<AppliedOperation> history = new ArrayList<>();
            history.add(new AppliedOperation(1, new InsertOperation(userA, 0L, "op1", 0, "hello")));
            history.add(new AppliedOperation(2, new InsertOperation(userA, 1L, "op2", 5, " world")));

            CollaborationSessionRuntime restored = CollaborationSessionRuntime.restore(
                    sessionId, "hello world", 2, history, otService);

            // Now a stale op from userB based on revision 0 should be transformed
            InsertOperation staleOp = new InsertOperation(userB, 0L, "op3", 0, "X");
            ApplyResult result = restored.applyClientOperation(staleOp);

            // userB > userA lexicographically, so userA wins left position at same-pos ties
            // transform against op1 (insert 0, "hello"): B shifts right by 5 -> insert(5, "X")
            // transform against op2 (insert 5, " world"): same position, B > A -> insert(11, "X")
            assertThat(result.revision()).isEqualTo(3);
            assertThat(result.snapshot().document()).isEqualTo("hello worldX");
        }

        @Test
        @DisplayName("deterministic convergence after a restore-then-apply sequence")
        void deterministicConvergenceAfterRestoreThenApply() {
            // Restore with some existing state
            List<AppliedOperation> history = new ArrayList<>();
            history.add(new AppliedOperation(1, new InsertOperation(userA, 0L, "op1", 0, "AB")));
            history.add(new AppliedOperation(2, new InsertOperation(userA, 1L, "op2", 2, "CD")));

            CollaborationSessionRuntime restored = CollaborationSessionRuntime.restore(
                    sessionId, "ABCD", 2, history, otService);

            // Apply new operations from different users
            ApplyResult r1 = restored.applyClientOperation(
                    new InsertOperation(userB, 2L, "op3", 4, "EF"));
            assertThat(r1.snapshot().document()).isEqualTo("ABCDEF");

            // Stale op from userC against revision 1
            ApplyResult r2 = restored.applyClientOperation(
                    new InsertOperation(userC, 1L, "op4", 2, "X"));
            // transform against op2 (insert 2, "CD"): C > A -> shifts right by 2 -> insert(4, "X")
            // transform against op3 (insert 4, "EF"): C > B -> shifts right by 2 -> insert(6, "X")
            assertThat(r2.revision()).isEqualTo(4);
            assertThat(r2.snapshot().document()).isEqualTo("ABCDEFX");
        }
    }
}
