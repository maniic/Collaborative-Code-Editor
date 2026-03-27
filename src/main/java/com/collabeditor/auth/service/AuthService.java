package com.collabeditor.auth.service;

import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.RefreshSessionEntity;
import com.collabeditor.auth.persistence.entity.UserEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshSessionService refreshSessionService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       RefreshSessionService refreshSessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshSessionService = refreshSessionService;
    }

    /**
     * Result of a login or refresh operation.
     */
    public record AuthResult(AuthTokenResponse tokenResponse, String rawRefreshToken) {}

    @Transactional
    public UserEntity register(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateEmailException("A user with this email already exists");
        }

        String passwordHash = passwordEncoder.encode(password);
        UserEntity user = new UserEntity(UUID.randomUUID(), normalizedEmail, passwordHash);
        return userRepository.save(user);
    }

    @Transactional
    public AuthResult login(String email, String password, String userAgent) {
        String normalizedEmail = email.trim().toLowerCase();

        UserEntity user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        UUID deviceId = UUID.randomUUID();
        RefreshSessionService.RefreshResult refreshResult =
                refreshSessionService.createSession(user.getId(), deviceId, userAgent);

        String accessToken = jwtTokenService.createAccessToken(user.getId(), user.getEmail());
        long expiresIn = jwtTokenService.getAccessTokenTtlSeconds();

        AuthTokenResponse tokenResponse = new AuthTokenResponse(
                accessToken, expiresIn, user.getId(), user.getEmail());

        return new AuthResult(tokenResponse, refreshResult.rawToken());
    }

    @Transactional
    public AuthResult refresh(String rawRefreshToken) {
        RefreshSessionService.RefreshResult refreshResult = refreshSessionService.rotate(rawRefreshToken);

        RefreshSessionEntity session = refreshResult.session();
        UserEntity user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        String accessToken = jwtTokenService.createAccessToken(user.getId(), user.getEmail());
        long expiresIn = jwtTokenService.getAccessTokenTtlSeconds();

        AuthTokenResponse tokenResponse = new AuthTokenResponse(
                accessToken, expiresIn, user.getId(), user.getEmail());

        return new AuthResult(tokenResponse, refreshResult.rawToken());
    }

    /**
     * Thrown when a user with the given email already exists.
     */
    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when credentials are invalid.
     */
    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException(String message) {
            super(message);
        }
    }
}
