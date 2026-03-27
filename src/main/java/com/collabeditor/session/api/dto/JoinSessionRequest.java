package com.collabeditor.session.api.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinSessionRequest(
    @NotBlank String inviteCode
) {}
