package com.collabeditor.auth;

import com.collabeditor.auth.api.AuthController;
import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.security.JwtAuthenticationFilter;
import com.collabeditor.auth.security.SecurityConfig;
import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.AuthService;
import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.common.api.ApiExceptionHandler;
import com.collabeditor.session.api.SessionController;
import com.collabeditor.session.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, SessionController.class})
@Import({
        ApiExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtTokenService.class,
        AuthControllerTest.AuthControllerTestConfig.class
})
class AuthControllerTest {

    private static final String TEST_SECRET = "test-jwt-secret-must-be-at-least-32-bytes-long!!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private AuthService authService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private SessionService sessionService;

    @Test
    void shouldRegisterAndReturn201() throws Exception {
        UUID userId = UUID.randomUUID();
        com.collabeditor.auth.persistence.entity.UserEntity user =
                new com.collabeditor.auth.persistence.entity.UserEntity(userId, "test@example.com", "hashed");

        when(authService.register(eq("test@example.com"), eq("password123"))).thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "test@example.com", "password", "password123"))))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectDuplicateEmailWith409() throws Exception {
        when(authService.register(anyString(), anyString()))
                .thenThrow(new AuthService.DuplicateEmailException("Email already exists"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "dup@example.com", "password", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldLoginAndReturnAccessTokenWithRefreshCookie() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthTokenResponse tokenResponse = new AuthTokenResponse("jwt-token", 900, userId, "test@example.com");
        AuthService.AuthResult result = new AuthService.AuthResult(tokenResponse, "raw-refresh-token");

        when(authService.login(eq("test@example.com"), eq("password123"), anyString())).thenReturn(result);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "test@example.com", "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("ccd_refresh_token")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("SameSite=Strict")));
    }

    @Test
    void shouldRejectInvalidPasswordWith401() throws Exception {
        when(authService.login(anyString(), anyString(), anyString()))
                .thenThrow(new AuthService.BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "test@example.com", "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void shouldRefreshTokenFromCookie() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthTokenResponse tokenResponse = new AuthTokenResponse("new-jwt", 900, userId, "test@example.com");
        AuthService.AuthResult result = new AuthService.AuthResult(tokenResponse, "new-refresh-token");

        when(authService.refresh(eq("old-refresh-token"))).thenReturn(result);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("ccd_refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-jwt"))
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("ccd_refresh_token")));
    }

    @Test
    void shouldReturn401ForUnauthenticatedProtectedRoute() throws Exception {
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidBearerTokenOnProtectedRoute() throws Exception {
        mockMvc.perform(get("/api/sessions")
                        .header("Authorization", "Bearer invalid bearer token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAuthenticateValidBearerTokenOnProtectedRoute() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.createAccessToken(userId, "test@example.com");
        when(sessionService.listSessions(userId)).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void shouldReturn400ForMalformedInviteCodeWithValidBearerToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.createAccessToken(userId, "test@example.com");

        mockMvc.perform(post("/api/sessions/join")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inviteCode", "bad-code"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(sessionService);
    }

    @Test
    void shouldReturn400ForInvalidLanguageWithValidBearerToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.createAccessToken(userId, "test@example.com");

        mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "RUBY"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(sessionService);
    }

    @Test
    void shouldKeepProtectedPostRouteUnauthorizedWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "RUBY"))))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class AuthControllerTestConfig {

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
