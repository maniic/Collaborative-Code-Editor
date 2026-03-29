package com.collabeditor.execution;

import com.collabeditor.auth.security.JwtAuthenticationFilter;
import com.collabeditor.auth.security.SecurityConfig;
import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.common.api.ApiExceptionHandler;
import com.collabeditor.execution.api.ExecutionController;
import com.collabeditor.execution.api.dto.EnqueueExecutionResponse;
import com.collabeditor.execution.service.ExecutionCoordinatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExecutionController.class)
@Import({
        ApiExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtTokenService.class,
        ExecutionControllerTest.ExecutionControllerTestConfig.class
})
class ExecutionControllerTest {

    private static final String TEST_SECRET = "test-jwt-secret-must-be-at-least-32-bytes-long!!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExecutionCoordinatorService executionCoordinatorService;

    @Test
    void acceptedEnqueueReturns202WithQueuedStatus() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        when(executionCoordinatorService.enqueue(sessionId, userId)).thenReturn(
                new EnqueueExecutionResponse(executionId, sessionId, "PYTHON", 42L, "QUEUED"));

        mockMvc.perform(post("/api/sessions/" + sessionId + "/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(userId, "exec@example.com")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.language").value("PYTHON"))
                .andExpect(jsonPath("$.sourceRevision").value(42L))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void cooldownRejectionReturns429TooManyRequests() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(executionCoordinatorService.enqueue(sessionId, userId))
                .thenThrow(new ExecutionCoordinatorService.ExecutionRateLimitException("Too Many Requests"));

        mockMvc.perform(post("/api/sessions/" + sessionId + "/executions")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(userId, "exec@example.com")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void queueFullReturns503ServiceUnavailable() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(executionCoordinatorService.enqueue(sessionId, userId))
                .thenThrow(new ExecutionCoordinatorService.ExecutionQueueFullException("Service Unavailable"));

        mockMvc.perform(post("/api/sessions/" + sessionId + "/executions")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(userId, "exec@example.com")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void unauthorizedAccessReturns401() throws Exception {
        mockMvc.perform(post("/api/sessions/" + UUID.randomUUID() + "/executions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptedExecutionReturns202ImmediatelyWhileTerminalOutputArrivesAsynchronouslyOnTheWebsocketRelayPath() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        when(executionCoordinatorService.enqueue(sessionId, userId)).thenReturn(
                new EnqueueExecutionResponse(executionId, sessionId, "JAVA", 7L, "QUEUED"));

        mockMvc.perform(post("/api/sessions/" + sessionId + "/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(userId, "exec@example.com")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.stdout").doesNotExist())
                .andExpect(jsonPath("$.stderr").doesNotExist())
                .andExpect(jsonPath("$.exitCode").doesNotExist());
    }

    @TestConfiguration
    static class ExecutionControllerTestConfig {

        @Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties(
                    TEST_SECRET,
                    "collaborative-code-editor",
                    Duration.ofMinutes(15),
                    Duration.ofDays(30),
                    "ccd_refresh_token",
                    "/api/auth",
                    "Strict"
            );
        }
    }
}
