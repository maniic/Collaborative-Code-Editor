package com.collabeditor.ot.service;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import org.springframework.stereotype.Service;

/**
 * Pure OT transform and apply service.
 * Contains no transport, persistence, or session state -- only deterministic transform rules.
 */
@Service
public class OperationalTransformService {

    /**
     * Transforms op1 against op2 (both were concurrent).
     * Returns the transformed version of op1 that can be applied after op2.
     */
    public TextOperation transform(TextOperation op1, TextOperation op2) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Applies an operation to a document string and returns the resulting document.
     */
    public String apply(String document, TextOperation operation) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
