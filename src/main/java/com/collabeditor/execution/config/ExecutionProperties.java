package com.collabeditor.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for Phase 4 code execution.
 *
 * <p>Bound from the {@code app.execution} prefix in application.yml.
 * Covers queue sizing, timeout, sandbox resource limits, and runtime images.
 */
@ConfigurationProperties(prefix = "app.execution")
public record ExecutionProperties(
        int cooldownSeconds,
        int timeoutSeconds,
        int workerCount,
        int queueCapacity,
        String pythonImage,
        String javaImage,
        long maxMemoryBytes,
        long nanoCpus,
        long workspaceTmpfsBytes,
        long tmpTmpfsBytes,
        String nonRootUser
) {
    public ExecutionProperties {
        if (cooldownSeconds <= 0) cooldownSeconds = 5;
        if (timeoutSeconds <= 0) timeoutSeconds = 10;
        if (workerCount <= 0) workerCount = 2;
        if (queueCapacity <= 0) queueCapacity = 8;
        if (pythonImage == null || pythonImage.isBlank()) pythonImage = "python:3.12-slim";
        if (javaImage == null || javaImage.isBlank()) javaImage = "eclipse-temurin:17-jdk-jammy";
        if (maxMemoryBytes <= 0) maxMemoryBytes = 268435456L;
        if (nanoCpus <= 0) nanoCpus = 500000000L;
        if (workspaceTmpfsBytes <= 0) workspaceTmpfsBytes = 67108864L;
        if (tmpTmpfsBytes <= 0) tmpTmpfsBytes = 33554432L;
        if (nonRootUser == null || nonRootUser.isBlank()) nonRootUser = "65534:65534";
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public String getPythonImage() {
        return pythonImage;
    }

    public String getJavaImage() {
        return javaImage;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public long getNanoCpus() {
        return nanoCpus;
    }

    public long getWorkspaceTmpfsBytes() {
        return workspaceTmpfsBytes;
    }

    public long getTmpTmpfsBytes() {
        return tmpTmpfsBytes;
    }

    public String getNonRootUser() {
        return nonRootUser;
    }
}
