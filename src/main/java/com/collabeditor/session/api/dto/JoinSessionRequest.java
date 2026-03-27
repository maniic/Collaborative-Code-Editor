package com.collabeditor.session.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record JoinSessionRequest(
    @NotBlank
    @Pattern(regexp = "(?i)[A-Z2-9]{8}", message = "Invite code must match [A-Z2-9]{8}")
    String inviteCode
) {}
