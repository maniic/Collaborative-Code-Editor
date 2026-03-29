package com.collabeditor.common.api;

import com.collabeditor.auth.service.AuthService;
import com.collabeditor.auth.service.RefreshSessionService;
import com.collabeditor.execution.service.ExecutionCoordinatorService;
import com.collabeditor.execution.service.ExecutionSourceService;
import com.collabeditor.session.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(int status, String error, String message, Instant timestamp) {}

    @ExceptionHandler(AuthService.DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(AuthService.DuplicateEmailException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponse(409, "Conflict", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AuthService.BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(AuthService.BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ErrorResponse(401, "Unauthorized", "Bad credentials", Instant.now()));
    }

    @ExceptionHandler(RefreshSessionService.InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(RefreshSessionService.InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ErrorResponse(401, "Unauthorized", "Invalid or expired refresh token", Instant.now()));
    }

    @ExceptionHandler(RefreshSessionService.RefreshTokenReusedException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenReuse(RefreshSessionService.RefreshTokenReusedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ErrorResponse(401, "Unauthorized", "Refresh token reuse detected", Instant.now()));
    }

    @ExceptionHandler(SessionService.SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSessionNotFound(SessionService.SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ErrorResponse(404, "Not Found", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(SessionService.SessionFullException.class)
    public ResponseEntity<ErrorResponse> handleSessionFull(SessionService.SessionFullException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponse(409, "Conflict", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(SessionService.InvalidLanguageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLanguage(SessionService.InvalidLanguageException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ErrorResponse(400, "Bad Request", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ExecutionSourceService.ExecutionSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleExecutionSessionNotFound(ExecutionSourceService.ExecutionSessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ErrorResponse(404, "Not Found", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ExecutionSourceService.ExecutionAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleExecutionAccessDenied(ExecutionSourceService.ExecutionAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ErrorResponse(403, "Forbidden", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ExecutionCoordinatorService.ExecutionRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleExecutionRateLimit(ExecutionCoordinatorService.ExecutionRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                new ErrorResponse(429, "Too Many Requests", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ExecutionCoordinatorService.ExecutionQueueFullException.class)
    public ResponseEntity<ErrorResponse> handleExecutionQueueFull(ExecutionCoordinatorService.ExecutionQueueFullException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ErrorResponse(503, "Service Unavailable", ex.getMessage(), Instant.now()));
    }
}
