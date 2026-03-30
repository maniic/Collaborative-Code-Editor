package com.collabeditor.execution.service;

import com.collabeditor.execution.config.ExecutionProperties;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.model.SandboxExecutionResult;
import com.collabeditor.execution.service.ExecutionLanguageSpecResolver.LanguageSpec;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Executes user code in a Docker container with strict isolation.
 *
 * <p>Creates a sandboxed container per run with the exact Phase 4 baseline limits:
 * 256MB memory, 0.5 CPU, 10s timeout, no network, read-only root filesystem,
 * and non-root user 65534:65534. Writable runtime artifacts live only in tmpfs mounts.
 */
@Service
public class DockerSandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);
    private static final long MAX_MEMORY_BYTES = 268435456L;
    private static final long NANO_CPUS = 500_000_000L;
    private static final long WORKSPACE_TMPFS_BYTES = 67_108_864L;
    private static final long TMP_TMPFS_BYTES = 33_554_432L;
    private static final String NON_ROOT_USER = "65534:65534";
    private static final int TIMEOUT_SECONDS = 10;
    private static final String WORKSPACE_DIR = "/workspace";
    private static final List<String> IDLE_COMMAND = List.of("sh", "-lc", "while true; do sleep 3600; done");

    private final DockerClient dockerClient;
    private final ExecutionLanguageSpecResolver languageSpecResolver;
    private final ExecutionProperties properties;

    public DockerSandboxRunner(DockerClient dockerClient,
                                ExecutionLanguageSpecResolver languageSpecResolver,
                                ExecutionProperties properties) {
        this.dockerClient = dockerClient;
        this.languageSpecResolver = languageSpecResolver;
        this.properties = properties;
    }

    /**
     * Runs the given source snapshot in a sandboxed Docker container.
     *
     * @param snapshot the canonical source snapshot to execute
     * @return the execution result with status, stdout, stderr, and exit code
     */
    public SandboxExecutionResult run(ExecutionSourceSnapshot snapshot) {
        String containerId = null;

        try {
            validateConfiguredLimits();
            LanguageSpec spec = languageSpecResolver.resolve(snapshot);
            ensureImageAvailable(spec.image());

            HostConfig hostConfig = new HostConfig()
                    .withMemory(MAX_MEMORY_BYTES)
                    .withMemorySwap(MAX_MEMORY_BYTES)
                    .withNanoCPUs(NANO_CPUS)
                    .withNetworkMode("none")
                    .withReadonlyRootfs(true)
                    .withTmpFs(Map.of(
                            WORKSPACE_DIR, "rw,size=" + WORKSPACE_TMPFS_BYTES + ",uid=65534,gid=65534,mode=1777",
                            "/tmp", "rw,size=" + TMP_TMPFS_BYTES + ",uid=65534,gid=65534,mode=1777"
                    ));

            String containerName = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
            CreateContainerResponse createResponse = dockerClient.createContainerCmd(spec.image())
                    .withImage(spec.image())
                    .withCmd(IDLE_COMMAND)
                    .withHostConfig(hostConfig)
                    .withUser(NON_ROOT_USER)
                    .withWorkingDir(WORKSPACE_DIR)
                    .withName(containerName)
                    .exec();

            containerId = createResponse.getId();
            log.info("Created sandbox container {} for session {} language {}",
                    containerId, snapshot.sessionId(), snapshot.language());

            dockerClient.startContainerCmd(containerId).exec();
            stageSourceInWorkspace(containerId, spec, snapshot.sourceCode());
            return runSandboxCommand(containerId, spec);

        } catch (IllegalArgumentException e) {
            return new SandboxExecutionResult(ExecutionStatus.FAILED, "", e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainerQuietly(containerId);
            return new SandboxExecutionResult(
                    ExecutionStatus.FAILED, "", "Execution interrupted", null);
        } catch (Exception e) {
            log.error("Sandbox execution failed for session {}: {}",
                    snapshot.sessionId(), e.getMessage(), e);
            return new SandboxExecutionResult(
                    ExecutionStatus.FAILED, "", errorMessage(e), null);
        } finally {
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId)
                            .withForce(true).exec();
                    log.debug("Removed container {}", containerId);
                } catch (Exception removeEx) {
                    log.warn("Failed to remove container {}: {}",
                            containerId, removeEx.getMessage());
                }
            }
        }
    }

    private void validateConfiguredLimits() {
        requireConfiguredValue(properties.getTimeoutSeconds(), TIMEOUT_SECONDS, "timeoutSeconds");
        requireConfiguredValue(properties.getMaxMemoryBytes(), MAX_MEMORY_BYTES, "maxMemoryBytes");
        requireConfiguredValue(properties.getNanoCpus(), NANO_CPUS, "nanoCpus");
        requireConfiguredValue(properties.getWorkspaceTmpfsBytes(), WORKSPACE_TMPFS_BYTES, "workspaceTmpfsBytes");
        requireConfiguredValue(properties.getTmpTmpfsBytes(), TMP_TMPFS_BYTES, "tmpTmpfsBytes");
        if (!NON_ROOT_USER.equals(properties.getNonRootUser())) {
            throw new IllegalStateException("app.execution.non-root-user must remain " + NON_ROOT_USER);
        }
    }

    private void requireConfiguredValue(long configured, long expected, String property) {
        if (configured != expected) {
            throw new IllegalStateException("app.execution." + property + " must remain " + expected);
        }
    }

    private void requireConfiguredValue(int configured, int expected, String property) {
        if (configured != expected) {
            throw new IllegalStateException("app.execution." + property + " must remain " + expected);
        }
    }

    private void ensureImageAvailable(String image) throws InterruptedException {
        try {
            InspectImageCmd inspectImageCmd = dockerClient.inspectImageCmd(image);
            inspectImageCmd.exec();
        } catch (NotFoundException notFoundException) {
            log.info("Pulling missing sandbox image {}", image);
            dockerClient.pullImageCmd(image)
                    .start()
                    .awaitCompletion();
        }
    }

    private void killContainerQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception killEx) {
            log.warn("Failed to kill container {}: {}", containerId, killEx.getMessage());
        }
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return (message == null || message.isBlank())
                ? exception.getClass().getSimpleName()
                : message;
    }

    private void stageSourceInWorkspace(String containerId, LanguageSpec spec, String sourceCode)
            throws InterruptedException {
        String targetPath = WORKSPACE_DIR + "/" + spec.sourceFilename();
        String encodedSource = Base64.getEncoder().encodeToString(
                (sourceCode == null ? "" : sourceCode).getBytes(StandardCharsets.UTF_8)
        );
        ExecCreateCmdResponse createResponse = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(false)
                .withUser(NON_ROOT_USER)
                .withWorkingDir(WORKSPACE_DIR)
                .withEnv(List.of("SOURCE_B64=" + encodedSource))
                .withCmd("sh", "-lc", "printf '%s' \"$SOURCE_B64\" | base64 -d > " + targetPath)
                .exec();

        String execId = createResponse.getId();
        ProcessOutput output = streamExec(
                dockerClient.execStartCmd(execId)
                        .withDetach(false)
                        .withTty(false),
                5
        );

        InspectExecResponse inspect = dockerClient.inspectExecCmd(execId).exec();
        if (!output.completed() || Boolean.TRUE.equals(inspect.isRunning()) || inspect.getExitCodeLong() == null
                || inspect.getExitCodeLong() != 0L) {
            throw new IllegalStateException("Could not stage source into sandbox container: " + output.stderr());
        }
    }

    private SandboxExecutionResult runSandboxCommand(String containerId, LanguageSpec spec) throws InterruptedException {
        ExecCreateCmdResponse createResponse = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(false)
                .withUser(NON_ROOT_USER)
                .withWorkingDir(WORKSPACE_DIR)
                .withEnv(spec.env().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList())
                .withCmd(spec.command().toArray(String[]::new))
                .exec();

        String execId = createResponse.getId();
        ProcessOutput output = streamExec(
                dockerClient.execStartCmd(execId)
                        .withDetach(false)
                        .withTty(false),
                TIMEOUT_SECONDS
        );
        InspectExecResponse inspect = dockerClient.inspectExecCmd(execId).exec();

        if (!output.completed() || Boolean.TRUE.equals(inspect.isRunning())) {
            log.warn("Container {} timed out after {}s, killing", containerId, TIMEOUT_SECONDS);
            killContainerQuietly(containerId);
            return new SandboxExecutionResult(ExecutionStatus.TIMED_OUT, output.stdout(), output.stderr(), null);
        }

        Integer exitCode = inspect.getExitCode();
        if (exitCode == null) {
            throw new IllegalStateException("Execution did not produce an exit code");
        }

        ExecutionStatus status = (exitCode == 0)
                ? ExecutionStatus.COMPLETED
                : ExecutionStatus.FAILED;
        return new SandboxExecutionResult(status, output.stdout(), output.stderr(), exitCode);
    }

    private ProcessOutput streamExec(com.github.dockerjava.api.command.ExecStartCmd execStartCmd, int timeoutSeconds)
            throws InterruptedException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getStreamType() == StreamType.STDERR) {
                    stderr.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                } else {
                    stdout.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }
        };

        boolean completed = execStartCmd.exec(callback).awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        return new ProcessOutput(stdout.toString(), stderr.toString(), completed);
    }

    private record ProcessOutput(String stdout, String stderr, boolean completed) {
    }
}
