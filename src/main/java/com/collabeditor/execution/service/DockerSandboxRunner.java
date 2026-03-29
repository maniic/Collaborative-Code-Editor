package com.collabeditor.execution.service;

import com.collabeditor.execution.config.ExecutionProperties;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.model.SandboxExecutionResult;
import com.collabeditor.execution.service.ExecutionLanguageSpecResolver.LanguageSpec;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String SANDBOX_ROOT_DIR = ".collabeditor-sandbox";

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
        Path tempDir = null;
        String containerId = null;

        try {
            validateConfiguredLimits();
            LanguageSpec spec = languageSpecResolver.resolve(snapshot);
            ensureImageAvailable(spec.image());
            tempDir = createSandboxInputDirectory();
            Path sourceFile = tempDir.resolve(spec.sourceFilename());
            Files.writeString(sourceFile, snapshot.sourceCode(), StandardCharsets.UTF_8);
            makeReadableForSandbox(tempDir, sourceFile);

            List<String> envList = new ArrayList<>();
            for (Map.Entry<String, String> entry : spec.env().entrySet()) {
                envList.add(entry.getKey() + "=" + entry.getValue());
            }

            HostConfig hostConfig = new HostConfig()
                    .withMemory(MAX_MEMORY_BYTES)
                    .withMemorySwap(MAX_MEMORY_BYTES)
                    .withNanoCPUs(NANO_CPUS)
                    .withNetworkMode("none")
                    .withReadonlyRootfs(true)
                    .withBinds(
                            new Bind(tempDir.toAbsolutePath().toString(),
                                    new Volume("/input"), AccessMode.ro)
                    )
                    .withTmpFs(Map.of(
                            "/workspace", "rw,size=" + WORKSPACE_TMPFS_BYTES + ",uid=65534,gid=65534,mode=1777",
                            "/tmp", "rw,size=" + TMP_TMPFS_BYTES + ",uid=65534,gid=65534,mode=1777"
                    ));

            String containerName = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
            CreateContainerResponse createResponse = dockerClient.createContainerCmd(spec.image())
                    .withImage(spec.image())
                    .withCmd(spec.command())
                    .withHostConfig(hostConfig)
                    .withUser(NON_ROOT_USER)
                    .withWorkingDir("/workspace")
                    .withEnv(envList)
                    .withName(containerName)
                    .exec();

            containerId = createResponse.getId();
            log.info("Created sandbox container {} for session {} language {}",
                    containerId, snapshot.sessionId(), snapshot.language());

            dockerClient.startContainerCmd(containerId).exec();

            Integer exitCode;
            try {
                exitCode = dockerClient.waitContainerCmd(containerId)
                        .exec(new WaitContainerResultCallback())
                        .awaitStatusCode(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (RuntimeException timeoutException) {
                if (isWaitTimeout(timeoutException)) {
                    exitCode = null;
                } else {
                    throw timeoutException;
                }
            }

            if (exitCode == null) {
                log.warn("Container {} timed out after {}s, killing", containerId, TIMEOUT_SECONDS);
                killContainerQuietly(containerId);
                String stdout = collectLogs(containerId, true, false);
                String stderr = collectLogs(containerId, false, true);
                return new SandboxExecutionResult(ExecutionStatus.TIMED_OUT, stdout, stderr, null);
            }

            String stdout = collectLogs(containerId, true, false);
            String stderr = collectLogs(containerId, false, true);

            ExecutionStatus status = (exitCode == 0)
                    ? ExecutionStatus.COMPLETED
                    : ExecutionStatus.FAILED;
            return new SandboxExecutionResult(status, stdout, stderr, exitCode);

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

            if (tempDir != null) {
                deleteTempDir(tempDir);
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

    private String collectLogs(String containerId, boolean includeStdout, boolean includeStderr) {
        StringBuilder sb = new StringBuilder();
        try {
            LogContainerCmd logContainerCmd = dockerClient.logContainerCmd(containerId);
            logContainerCmd
                    .withStdOut(includeStdout)
                    .withStdErr(includeStderr)
                    .withFollowStream(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(
                                    frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to collect {} logs from container {}: {}",
                    includeStdout ? "stdout" : "stderr", containerId, e.getMessage());
        }
        return sb.toString();
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return (message == null || message.isBlank())
                ? exception.getClass().getSimpleName()
                : message;
    }

    private boolean isWaitTimeout(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("Awaiting status code timeout");
    }

    private void deleteTempDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort cleanup
                        }
                    });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private void makeReadableForSandbox(Path tempDir, Path sourceFile) {
        try {
            Files.setPosixFilePermissions(tempDir, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
            Files.setPosixFilePermissions(sourceFile, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ
            ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems are not expected in supported local setups.
        } catch (IOException e) {
            throw new IllegalStateException("Could not set sandbox input permissions", e);
        }
    }

    private Path createSandboxInputDirectory() throws IOException {
        Path sandboxRoot = Path.of(System.getProperty("user.home"), SANDBOX_ROOT_DIR);
        Files.createDirectories(sandboxRoot);
        return Files.createTempDirectory(sandboxRoot, "sandbox-");
    }
}
