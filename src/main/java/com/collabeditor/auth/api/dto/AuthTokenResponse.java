package com.collabeditor.auth.api.dto;

import java.util.UUID;

public record AuthTokenResponse(
        String accessToken,
        long expiresInSeconds,
        UUID userId,
        String email
) {}
