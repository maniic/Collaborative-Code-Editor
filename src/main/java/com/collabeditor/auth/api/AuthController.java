package com.collabeditor.auth.api;

import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.api.dto.LoginRequest;
import com.collabeditor.auth.api.dto.RegisterRequest;
import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SecurityProperties securityProperties;

    public AuthController(AuthService authService, SecurityProperties securityProperties) {
        this.authService = authService;
        this.securityProperties = securityProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        String userAgent = httpRequest.getHeader("User-Agent");
        AuthService.AuthResult result = authService.login(request.email(), request.password(),
                userAgent != null ? userAgent : "unknown");

        setRefreshCookie(httpResponse, result.rawRefreshToken());
        return ResponseEntity.ok(result.tokenResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        String rawRefreshToken = extractRefreshTokenFromCookie(httpRequest);
        if (rawRefreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        AuthService.AuthResult result = authService.refresh(rawRefreshToken);
        setRefreshCookie(httpResponse, result.rawRefreshToken());
        return ResponseEntity.ok(result.tokenResponse());
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from(
                        securityProperties.refreshCookieName(), rawToken)
                .httpOnly(true)
                .secure(true)
                .path(securityProperties.refreshCookiePath())
                .maxAge(securityProperties.refreshTokenTtl().toSeconds())
                .sameSite(securityProperties.refreshCookieSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> securityProperties.refreshCookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
