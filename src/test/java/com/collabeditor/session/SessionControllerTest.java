package com.collabeditor.session;

import com.collabeditor.auth.security.JwtAuthenticationFilter;
import com.collabeditor.auth.security.SecurityConfig;
import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.common.api.ApiExceptionHandler;
import com.collabeditor.session.api.SessionController;
import com.collabeditor.session.api.dto.SessionResponse;
import com.collabeditor.session.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SessionController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class})
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SessionService sessionService;

    @MockBean
    private JwtTokenService jwtTokenService;

    private UsernamePasswordAuthenticationToken authToken(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }

    @Test
    void shouldCreateSessionAndReturn201() throws Exception {
        UUID userId = UUID.randomUUID();
        SessionResponse response = new SessionResponse(
                UUID.randomUUID(), "ABCD2345", "JAVA", userId, 12, 1, Instant.now());

        when(sessionService.createSession(eq(userId), eq("JAVA"))).thenReturn(response);

        mockMvc.perform(post("/api/sessions")
                        .with(authentication(authToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "JAVA"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(response.sessionId().toString()))
                .andExpect(jsonPath("$.inviteCode").value("ABCD2345"))
                .andExpect(jsonPath("$.language").value("JAVA"))
                .andExpect(jsonPath("$.ownerUserId").value(userId.toString()))
                .andExpect(jsonPath("$.participantCap").value(12));
    }

    @Test
    void shouldListSessionsForAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        SessionResponse session1 = new SessionResponse(
                UUID.randomUUID(), "SESS0001", "JAVA", userId, 12, 2, Instant.now());
        SessionResponse session2 = new SessionResponse(
                UUID.randomUUID(), "SESS0002", "PYTHON", userId, 12, 1, Instant.now());

        when(sessionService.listSessions(userId)).thenReturn(List.of(session1, session2));

        mockMvc.perform(get("/api/sessions")
                        .with(authentication(authToken(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].inviteCode").value("SESS0001"))
                .andExpect(jsonPath("$[1].inviteCode").value("SESS0002"));
    }

    @Test
    void shouldJoinSessionByInviteCode() throws Exception {
        UUID userId = UUID.randomUUID();
        SessionResponse response = new SessionResponse(
                UUID.randomUUID(), "JOIN2345", "JAVA", UUID.randomUUID(), 12, 3, Instant.now());

        when(sessionService.joinSession(eq(userId), eq("JOIN2345"))).thenReturn(response);

        mockMvc.perform(post("/api/sessions/join")
                        .with(authentication(authToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inviteCode", "JOIN2345"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("JOIN2345"));
    }

    @Test
    void shouldRejectJoinWhenRoomIsFull() throws Exception {
        UUID userId = UUID.randomUUID();

        when(sessionService.joinSession(eq(userId), eq("FULL2345")))
                .thenThrow(new SessionService.SessionFullException("Session is full (cap: 12)"));

        mockMvc.perform(post("/api/sessions/join")
                        .with(authentication(authToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inviteCode", "FULL2345"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldLeaveSession() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        doNothing().when(sessionService).leaveSession(eq(userId), eq(sessionId));

        mockMvc.perform(post("/api/sessions/" + sessionId + "/leave")
                        .with(authentication(authToken(userId))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnNotFoundForNonexistentSession() throws Exception {
        UUID userId = UUID.randomUUID();

        when(sessionService.joinSession(eq(userId), eq("NOPE2345")))
                .thenThrow(new SessionService.SessionNotFoundException("Session not found"));

        mockMvc.perform(post("/api/sessions/join")
                        .with(authentication(authToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inviteCode", "NOPE2345"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldRejectMalformedInviteCodeWith400() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/sessions/join")
                        .with(authentication(authToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inviteCode", "bad-code"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(sessionService);
    }
}
