package com.collabeditor.ot;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.CollaborationSessionRuntime.ApplyResult;
import com.collabeditor.ot.service.OperationalTransformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
}
