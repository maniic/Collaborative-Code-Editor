package com.collabeditor.execution.api;

import com.collabeditor.execution.api.dto.EnqueueExecutionResponse;
import com.collabeditor.execution.service.ExecutionCoordinatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class ExecutionController {

    private final ExecutionCoordinatorService executionCoordinatorService;

    public ExecutionController(ExecutionCoordinatorService executionCoordinatorService) {
        this.executionCoordinatorService = executionCoordinatorService;
    }

    @PostMapping("/{sessionId}/executions")
    public ResponseEntity<EnqueueExecutionResponse> enqueue(@PathVariable UUID sessionId,
                                                            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EnqueueExecutionResponse response = executionCoordinatorService.enqueue(sessionId, userId);
        return ResponseEntity.accepted().body(response);
    }
}
