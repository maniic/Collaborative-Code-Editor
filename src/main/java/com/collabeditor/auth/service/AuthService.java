package com.collabeditor.auth.service;

import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.UserEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

    public UserEntity register(String email, String password) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public AuthResult login(String email, String password, String userAgent) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public AuthResult refresh(String rawRefreshToken) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
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
