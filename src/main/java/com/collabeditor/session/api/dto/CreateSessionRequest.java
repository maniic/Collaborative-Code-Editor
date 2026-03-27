package com.collabeditor.session.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateSessionRequest(
    @NotNull @Pattern(regexp = "JAVA|PYTHON", message = "Language must be JAVA or PYTHON")
    String language
) {}
