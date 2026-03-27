package com.collabeditor.session.api;

import com.collabeditor.session.api.dto.CreateSessionRequest;
import com.collabeditor.session.api.dto.JoinSessionRequest;
import com.collabeditor.session.api.dto.SessionResponse;
import com.collabeditor.session.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request,
                                                          Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        SessionResponse response = sessionService.createSession(userId, request.language());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> listSessions(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        List<SessionResponse> sessions = sessionService.listSessions(userId);
        return ResponseEntity.ok(sessions);
    }

    @PostMapping("/join")
    public ResponseEntity<SessionResponse> joinSession(@Valid @RequestBody JoinSessionRequest request,
                                                        Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        SessionResponse response = sessionService.joinSession(userId, request.inviteCode());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/leave")
    public ResponseEntity<Void> leaveSession(@PathVariable UUID sessionId,
                                              Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        sessionService.leaveSession(userId, sessionId);
        return ResponseEntity.ok().build();
    }
}
