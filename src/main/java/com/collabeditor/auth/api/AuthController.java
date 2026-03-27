package com.collabeditor.auth.api;

import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.api.dto.LoginRequest;
import com.collabeditor.auth.api.dto.RegisterRequest;
import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
