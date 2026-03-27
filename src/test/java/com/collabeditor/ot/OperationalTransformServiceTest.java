package com.collabeditor.ot;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.service.OperationalTransformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalTransformServiceTest {

    private OperationalTransformService otService;

    // Deterministic user IDs for tie-break testing
    // userA < userB lexicographically
    private final UUID userA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID userB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        otService = new OperationalTransformService();
    }

    // --- Apply tests ---

    @Nested
    @DisplayName("apply() - document mutation")
    class ApplyTests {

        @Test
        @DisplayName("apply insert at beginning")
        void applyInsertAtBeginning() {
            String result = otService.apply("hello", insert(userA, 0, "xyz"));
            assertThat(result).isEqualTo("xyzhello");
        }

        @Test
        @DisplayName("apply insert at end")
        void applyInsertAtEnd() {
            String result = otService.apply("hello", insert(userA, 5, " world"));
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        @DisplayName("apply insert in middle")
        void applyInsertInMiddle() {
            String result = otService.apply("helo", insert(userA, 2, "ll"));
            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("apply delete from beginning")
        void applyDeleteFromBeginning() {
            String result = otService.apply("hello", delete(userA, 0, 2));
            assertThat(result).isEqualTo("llo");
        }

        @Test
        @DisplayName("apply delete from end")
        void applyDeleteFromEnd() {
            String result = otService.apply("hello", delete(userA, 3, 2));
            assertThat(result).isEqualTo("hel");
        }

        @Test
        @DisplayName("apply delete in middle")
        void applyDeleteInMiddle() {
            String result = otService.apply("hello", delete(userA, 1, 3));
            assertThat(result).isEqualTo("ho");
        }

        @Test
        @DisplayName("apply insert to empty document")
        void applyInsertToEmpty() {
            String result = otService.apply("", insert(userA, 0, "hello"));
            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("apply delete entire document")
        void applyDeleteEntireDocument() {
            String result = otService.apply("hello", delete(userA, 0, 5));
            assertThat(result).isEqualTo("");
        }

        @Test
        @DisplayName("reject insert at negative position")
        void rejectInsertNegativePosition() {
            assertThatThrownBy(() -> otService.apply("hello", insert(userA, -1, "x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reject insert beyond document length")
        void rejectInsertBeyondLength() {
            assertThatThrownBy(() -> otService.apply("hello", insert(userA, 6, "x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reject delete with negative position")
        void rejectDeleteNegativePosition() {
            assertThatThrownBy(() -> otService.apply("hello", delete(userA, -1, 2)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reject delete beyond document boundary")
        void rejectDeleteBeyondBoundary() {
            assertThatThrownBy(() -> otService.apply("hello", delete(userA, 3, 5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reject delete with zero length")
        void rejectDeleteZeroLength() {
            assertThatThrownBy(() -> otService.apply("hello", delete(userA, 0, 0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- Transform Insert/Insert tests ---

    @Nested
    @DisplayName("transformInsertInsert")
    class TransformInsertInsert {

        @Test
        @DisplayName("non-overlapping: op1 before op2")
        void op1BeforeOp2() {
            InsertOperation op1 = insert(userA, 2, "ab");
            InsertOperation op2 = insert(userB, 5, "cd");

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(InsertOperation.class);
            InsertOperation r = (InsertOperation) result;
            assertThat(r.position()).isEqualTo(2);
            assertThat(r.text()).isEqualTo("ab");
        }

        @Test
        @DisplayName("non-overlapping: op1 after op2")
        void op1AfterOp2() {
            InsertOperation op1 = insert(userA, 5, "ab");
            InsertOperation op2 = insert(userB, 2, "cd");

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(InsertOperation.class);
            InsertOperation r = (InsertOperation) result;
            // op2 inserts 2 chars at position 2, so op1 shifts right by 2
            assertThat(r.position()).isEqualTo(7);
        }

        @Test
        @DisplayName("same position: tie-break by authorUserId - lower UUID wins left position")
        void samePositionTieBreak() {
            // userA < userB lexicographically, so userA's insert stays at position
            InsertOperation op1 = insert(userA, 3, "ab");
            InsertOperation op2 = insert(userB, 3, "cd");

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(InsertOperation.class);
            InsertOperation r = (InsertOperation) result;
            // userA < userB, so op1 (userA) keeps position 3
            assertThat(r.position()).isEqualTo(3);

            // Now transform op2 against op1 - userB should shift right
            TextOperation result2 = otService.transform(op2, op1);
            assertThat(result2).isInstanceOf(InsertOperation.class);
            InsertOperation r2 = (InsertOperation) result2;
            assertThat(r2.position()).isEqualTo(5); // shifted by op1's "ab" length
        }
    }

    // --- Transform Insert/Delete tests ---

    @Nested
    @DisplayName("transformInsertDelete")
    class TransformInsertDelete {

        @Test
        @DisplayName("insert before delete range")
        void insertBeforeDelete() {
            InsertOperation ins = insert(userA, 1, "xy");
            DeleteOperation del = delete(userB, 5, 3);

            TextOperation result = otService.transform(ins, del);
            assertThat(result).isInstanceOf(InsertOperation.class);
            // Insert at 1, delete starts at 5 -> no shift needed
            assertThat(((InsertOperation) result).position()).isEqualTo(1);
        }

        @Test
        @DisplayName("insert after delete range")
        void insertAfterDelete() {
            InsertOperation ins = insert(userA, 8, "xy");
            DeleteOperation del = delete(userB, 2, 3);

            TextOperation result = otService.transform(ins, del);
            assertThat(result).isInstanceOf(InsertOperation.class);
            // Delete removes 3 chars starting at 2, so insert shifts left by 3
            assertThat(((InsertOperation) result).position()).isEqualTo(5);
        }

        @Test
        @DisplayName("insert within delete range - preserves insert at delete start")
        void insertWithinDeleteRange() {
            InsertOperation ins = insert(userA, 4, "xy");
            DeleteOperation del = delete(userB, 2, 5);

            TextOperation result = otService.transform(ins, del);
            assertThat(result).isInstanceOf(InsertOperation.class);
            // Insert point is inside delete range -> insert repositioned to delete start
            assertThat(((InsertOperation) result).position()).isEqualTo(2);
        }

        @Test
        @DisplayName("insert at exact delete start position")
        void insertAtDeleteStart() {
            InsertOperation ins = insert(userA, 2, "xy");
            DeleteOperation del = delete(userB, 2, 3);

            TextOperation result = otService.transform(ins, del);
            assertThat(result).isInstanceOf(InsertOperation.class);
            // Insert at exact start of delete - insert stays at position 2
            assertThat(((InsertOperation) result).position()).isEqualTo(2);
        }
    }

    // --- Transform Delete/Insert tests ---

    @Nested
    @DisplayName("transformDeleteInsert")
    class TransformDeleteInsert {

        @Test
        @DisplayName("delete before insert position")
        void deleteBeforeInsert() {
            DeleteOperation del = delete(userA, 0, 2);
            InsertOperation ins = insert(userB, 5, "xy");

            TextOperation result = otService.transform(del, ins);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            // Delete at 0 len 2, insert at 5 -> no shift
            assertThat(((DeleteOperation) result).position()).isEqualTo(0);
            assertThat(((DeleteOperation) result).length()).isEqualTo(2);
        }

        @Test
        @DisplayName("delete after insert position")
        void deleteAfterInsert() {
            DeleteOperation del = delete(userA, 5, 3);
            InsertOperation ins = insert(userB, 2, "xy");

            TextOperation result = otService.transform(del, ins);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            // Insert of 2 chars at position 2, so delete shifts right by 2
            assertThat(((DeleteOperation) result).position()).isEqualTo(7);
            assertThat(((DeleteOperation) result).length()).isEqualTo(3);
        }

        @Test
        @DisplayName("delete range contains insert point - delete splits around insert")
        void deleteContainsInsertPoint() {
            // Delete range [2, 7) contains insert at position 4
            DeleteOperation del = delete(userA, 2, 5);
            InsertOperation ins = insert(userB, 4, "xy");

            TextOperation result = otService.transform(del, ins);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            // Insert adds 2 chars inside delete range, so delete length grows by 2
            assertThat(r.position()).isEqualTo(2);
            assertThat(r.length()).isEqualTo(7);
        }
    }

    // --- Transform Delete/Delete tests ---

    @Nested
    @DisplayName("transformDeleteDelete")
    class TransformDeleteDelete {

        @Test
        @DisplayName("non-overlapping: op1 before op2")
        void op1BeforeOp2() {
            DeleteOperation op1 = delete(userA, 0, 2);
            DeleteOperation op2 = delete(userB, 5, 3);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            assertThat(r.position()).isEqualTo(0);
            assertThat(r.length()).isEqualTo(2);
        }

        @Test
        @DisplayName("non-overlapping: op1 after op2")
        void op1AfterOp2() {
            DeleteOperation op1 = delete(userA, 5, 3);
            DeleteOperation op2 = delete(userB, 0, 2);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            // op2 removes 2 chars at 0, so op1 shifts left by 2
            assertThat(r.position()).isEqualTo(3);
            assertThat(r.length()).isEqualTo(3);
        }

        @Test
        @DisplayName("partially overlapping deletes - left overlap")
        void partialOverlapLeft() {
            // op1: delete [2, 6), op2: delete [4, 8)
            DeleteOperation op1 = delete(userA, 2, 4);
            DeleteOperation op2 = delete(userB, 4, 4);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            // Overlap: [4,6). op2 already deletes that range.
            // op1 only needs to delete [2,4) which is 2 chars, positioned at 2
            assertThat(r.position()).isEqualTo(2);
            assertThat(r.length()).isEqualTo(2);
        }

        @Test
        @DisplayName("partially overlapping deletes - right overlap")
        void partialOverlapRight() {
            // op1: delete [4, 8), op2: delete [2, 6)
            DeleteOperation op1 = delete(userA, 4, 4);
            DeleteOperation op2 = delete(userB, 2, 4);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            // Overlap: [4,6). op2 already deleted that.
            // op1 needs to delete [6,8) which is 2 chars, shifted left by op2 prefix
            // After op2 deletes [2,6), position 6 becomes position 2, so op1 starts at 2 with length 2
            assertThat(r.position()).isEqualTo(2);
            assertThat(r.length()).isEqualTo(2);
        }

        @Test
        @DisplayName("op1 entirely within op2 - becomes no-op")
        void op1EntirelyWithinOp2() {
            // op1: delete [3, 5), op2: delete [2, 7)
            DeleteOperation op1 = delete(userA, 3, 2);
            DeleteOperation op2 = delete(userB, 2, 5);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            // op2 already deleted the entire range op1 wanted to delete
            assertThat(r.length()).isEqualTo(0);
        }

        @Test
        @DisplayName("op1 entirely contains op2")
        void op1ContainsOp2() {
            // op1: delete [2, 7), op2: delete [3, 5)
            DeleteOperation op1 = delete(userA, 2, 5);
            DeleteOperation op2 = delete(userB, 3, 2);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            // op2 deleted [3,5) which is 2 chars within op1's range
            // op1 still needs to delete the rest: [2,3) and [5,7) = 3 chars at position 2
            assertThat(r.position()).isEqualTo(2);
            assertThat(r.length()).isEqualTo(3);
        }

        @Test
        @DisplayName("identical deletes become no-op")
        void identicalDeletes() {
            DeleteOperation op1 = delete(userA, 2, 3);
            DeleteOperation op2 = delete(userB, 2, 3);

            TextOperation result = otService.transform(op1, op2);
            assertThat(result).isInstanceOf(DeleteOperation.class);
            DeleteOperation r = (DeleteOperation) result;
            assertThat(r.length()).isEqualTo(0);
        }
    }

    // --- TP1 convergence property ---

    @Nested
    @DisplayName("TP1 convergence property")
    class TP1Convergence {

        @Test
        @DisplayName("insert-insert convergence: apply(apply(doc, op1), transform(op2, op1)) == apply(apply(doc, op2), transform(op1, op2))")
        void insertInsertConvergence() {
            String doc = "hello world";
            InsertOperation op1 = insert(userA, 5, " beautiful");
            InsertOperation op2 = insert(userB, 5, " cruel");

            // Path 1: apply op1, then transform(op2, op1)
            String after1 = otService.apply(doc, op1);
            TextOperation op2Prime = otService.transform(op2, op1);
            String result1 = otService.apply(after1, op2Prime);

            // Path 2: apply op2, then transform(op1, op2)
            String after2 = otService.apply(doc, op2);
            TextOperation op1Prime = otService.transform(op1, op2);
            String result2 = otService.apply(after2, op1Prime);

            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("insert-delete convergence")
        void insertDeleteConvergence() {
            String doc = "hello world";
            InsertOperation ins = insert(userA, 3, "XY");
            DeleteOperation del = delete(userB, 1, 4);

            // Path 1
            String after1 = otService.apply(doc, ins);
            TextOperation delPrime = otService.transform(del, ins);
            String result1 = otService.apply(after1, delPrime);

            // Path 2
            String after2 = otService.apply(doc, del);
            TextOperation insPrime = otService.transform(ins, del);
            String result2 = otService.apply(after2, insPrime);

            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("delete-delete convergence with overlap")
        void deleteDeleteConvergence() {
            String doc = "abcdefghij";
            DeleteOperation op1 = delete(userA, 2, 4); // delete "cdef"
            DeleteOperation op2 = delete(userB, 4, 4); // delete "efgh"

            // Path 1
            String after1 = otService.apply(doc, op1);
            TextOperation op2Prime = otService.transform(op2, op1);
            String result1 = otService.apply(after1, op2Prime);

            // Path 2
            String after2 = otService.apply(doc, op2);
            TextOperation op1Prime = otService.transform(op1, op2);
            String result2 = otService.apply(after2, op1Prime);

            assertThat(result1).isEqualTo(result2);
        }
    }

    // --- Helper methods ---

    private InsertOperation insert(UUID author, int position, String text) {
        return new InsertOperation(author, 0L, UUID.randomUUID().toString(), position, text);
    }

    private DeleteOperation delete(UUID author, int position, int length) {
        return new DeleteOperation(author, 0L, UUID.randomUUID().toString(), position, length);
    }
}
