package com.collabeditor.ot.service;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import org.springframework.stereotype.Service;

/**
 * Pure OT transform and apply service.
 * Contains no transport, persistence, or session state -- only deterministic transform rules.
 *
 * <p>Transform rules satisfy TP1 (Transformation Property 1):
 * {@code apply(apply(doc, op1), transform(op2, op1)) == apply(apply(doc, op2), transform(op1, op2))}
 *
 * <p>Same-position insert tie-break uses lexicographic {@code authorUserId.toString()} ordering:
 * the author with the smaller UUID string places their insert first (left).
 */
@Service
public class OperationalTransformService {

    /**
     * Transforms op1 against op2 (both were concurrent against the same document state).
     * Returns the transformed version of op1 that can be applied after op2 has been applied.
     */
    public TextOperation transform(TextOperation op1, TextOperation op2) {
        if (op1 instanceof InsertOperation ins1) {
            if (op2 instanceof InsertOperation ins2) {
                return transformInsertInsert(ins1, ins2);
            } else if (op2 instanceof DeleteOperation del2) {
                return transformInsertDelete(ins1, del2);
            }
        } else if (op1 instanceof DeleteOperation del1) {
            if (op2 instanceof InsertOperation ins2) {
                return transformDeleteInsert(del1, ins2);
            } else if (op2 instanceof DeleteOperation del2) {
                return transformDeleteDelete(del1, del2);
            }
        }
        throw new IllegalArgumentException("Unknown operation types: " + op1.getClass() + ", " + op2.getClass());
    }

    /**
     * Applies an operation to a document string and returns the resulting document.
     *
     * @throws IllegalArgumentException if the operation violates document boundaries
     */
    public String apply(String document, TextOperation operation) {
        if (operation instanceof InsertOperation ins) {
            return applyInsert(document, ins);
        } else if (operation instanceof DeleteOperation del) {
            return applyDelete(document, del);
        }
        throw new IllegalArgumentException("Unknown operation type: " + operation.getClass());
    }

    // --- Apply implementations ---

    private String applyInsert(String document, InsertOperation ins) {
        if (ins.position() < 0 || ins.position() > document.length()) {
            throw new IllegalArgumentException(
                    "Insert position %d out of bounds for document length %d"
                            .formatted(ins.position(), document.length()));
        }
        return document.substring(0, ins.position()) + ins.text() + document.substring(ins.position());
    }

    private String applyDelete(String document, DeleteOperation del) {
        if (del.position() < 0) {
            throw new IllegalArgumentException(
                    "Delete position %d must be >= 0".formatted(del.position()));
        }
        if (del.length() <= 0) {
            throw new IllegalArgumentException(
                    "Delete length %d must be > 0".formatted(del.length()));
        }
        if (del.position() + del.length() > document.length()) {
            throw new IllegalArgumentException(
                    "Delete range [%d, %d) exceeds document length %d"
                            .formatted(del.position(), del.position() + del.length(), document.length()));
        }
        return document.substring(0, del.position()) + document.substring(del.position() + del.length());
    }

    // --- Pairwise transform implementations ---

    /**
     * Transform insert against insert.
     * Same-position tie-break: lower authorUserId.toString() goes first (keeps position).
     */
    private TextOperation transformInsertInsert(InsertOperation op1, InsertOperation op2) {
        if (op1.position() < op2.position()) {
            // op1 is before op2; op2 doesn't affect op1
            return op1;
        } else if (op1.position() > op2.position()) {
            // op1 is after op2; shift right by op2's text length
            return op1.withPosition(op1.position() + op2.text().length());
        } else {
            // Same position: tie-break by authorUserId
            if (op1.authorUserId().toString().compareTo(op2.authorUserId().toString()) < 0) {
                // op1's author wins left position; op1 stays
                return op1;
            } else {
                // op2's author wins left position; op1 shifts right
                return op1.withPosition(op1.position() + op2.text().length());
            }
        }
    }

    /**
     * Transform insert against delete.
     * If insert point falls within the delete range, reposition insert to delete start.
     */
    private TextOperation transformInsertDelete(InsertOperation ins, DeleteOperation del) {
        if (ins.position() <= del.position()) {
            // Insert is before or at delete start; unaffected
            return ins;
        } else if (ins.position() >= del.position() + del.length()) {
            // Insert is after delete range; shift left by delete length
            return ins.withPosition(ins.position() - del.length());
        } else {
            // Insert is within delete range; reposition to delete start
            return ins.withPosition(del.position());
        }
    }

    /**
     * Transform delete against insert.
     * If insert falls within delete range, expand delete to include inserted text.
     */
    private TextOperation transformDeleteInsert(DeleteOperation del, InsertOperation ins) {
        int delEnd = del.position() + del.length();

        if (delEnd <= ins.position()) {
            // Delete range is entirely before insert; unaffected
            return del;
        } else if (del.position() >= ins.position()) {
            // Delete starts at or after insert point; shift right by insert length
            return del.withPositionAndLength(del.position() + ins.text().length(), del.length());
        } else {
            // Insert falls within delete range; expand delete to include inserted text
            return del.withPositionAndLength(del.position(), del.length() + ins.text().length());
        }
    }

    /**
     * Transform delete against delete.
     * Handles: non-overlapping, partial overlap, containment, and identical ranges.
     */
    private TextOperation transformDeleteDelete(DeleteOperation op1, DeleteOperation op2) {
        int op1End = op1.position() + op1.length();
        int op2End = op2.position() + op2.length();

        // No overlap: op1 entirely before op2
        if (op1End <= op2.position()) {
            return op1;
        }

        // No overlap: op1 entirely after op2
        if (op1.position() >= op2End) {
            return op1.withPositionAndLength(op1.position() - op2.length(), op1.length());
        }

        // Some overlap exists. Calculate the non-overlapping portion of op1.
        int overlapStart = Math.max(op1.position(), op2.position());
        int overlapEnd = Math.min(op1End, op2End);
        int overlapLength = overlapEnd - overlapStart;

        // Remaining length after removing overlap
        int remainingLength = op1.length() - overlapLength;

        // New position: the portion of op1 before the overlap starts at op1.position(),
        // but we need to account for op2's deletion before op1's start
        int op2DeletedBeforeOp1 = Math.max(0, Math.min(op2End, op1.position()) - op2.position());
        int newPosition = op1.position() - op2DeletedBeforeOp1;

        return op1.withPositionAndLength(newPosition, remainingLength);
    }
}
