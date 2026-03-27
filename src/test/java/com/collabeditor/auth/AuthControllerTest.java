package com.collabeditor.auth;

import com.collabeditor.auth.api.AuthController;
import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.common.TestSecurityConfig;
import com.collabeditor.auth.persistence.entity.UserEntity;
import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.AuthService;
import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.common.api.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@Import({ApiExceptionHandler.class, TestSecurityConfig.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtTokenService jwtTokenService;

    @MockBean
    private SecurityProperties securityProperties;

    @Test
    void shouldRegisterAndReturn201() throws Exception {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(userId, "test@example.com", "hashed");

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
        when(securityProperties.refreshCookieName()).thenReturn("ccd_refresh_token");
        when(securityProperties.refreshCookiePath()).thenReturn("/api/auth");
        when(securityProperties.refreshCookieSameSite()).thenReturn("Strict");
        when(securityProperties.refreshTokenTtl()).thenReturn(Duration.ofDays(30));

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
        when(securityProperties.refreshCookieName()).thenReturn("ccd_refresh_token");
        when(securityProperties.refreshCookiePath()).thenReturn("/api/auth");
        when(securityProperties.refreshCookieSameSite()).thenReturn("Strict");
        when(securityProperties.refreshTokenTtl()).thenReturn(Duration.ofDays(30));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("ccd_refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-jwt"))
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("ccd_refresh_token")));
    }
}
