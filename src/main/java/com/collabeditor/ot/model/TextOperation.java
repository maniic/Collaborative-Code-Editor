package com.collabeditor.ot.model;

import java.util.UUID;

/**
 * Sealed interface for OT operations. All operations carry author identity,
 * base revision, and a client-assigned operation ID for deduplication.
 */
public sealed interface TextOperation permits InsertOperation, DeleteOperation {

    UUID authorUserId();

    long baseRevision();

    String clientOperationId();
}
