package com.collabeditor.execution;

import com.collabeditor.execution.config.DockerExecutionConfig;
import com.collabeditor.execution.config.ExecutionProperties;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.model.SandboxExecutionResult;
import com.collabeditor.execution.service.DockerSandboxRunner;
import com.collabeditor.execution.service.ExecutionLanguageSpecResolver;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.transport.DockerHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ExecutionIntegrationTest {

    private static DockerClient dockerClient;
    private static DockerHttpClient dockerHttpClient;
    private static DockerSandboxRunner runner;

    @BeforeAll
    static void setUp() {
        ExecutionProperties properties = new ExecutionProperties(
                5,
                10,
                2,
                8,
                "python:3.12-slim",
                "eclipse-temurin:17-jdk-jammy",
                268435456L,
                500_000_000L,
                67108864L,
                33554432L,
                "65534:65534"
        );

        DockerExecutionConfig config = new DockerExecutionConfig();
        DefaultDockerClientConfig clientConfig = config.dockerClientConfig();
        dockerHttpClient = config.dockerHttpClient(clientConfig);
        dockerClient = config.dockerClient(clientConfig, dockerHttpClient);
        runner = new DockerSandboxRunner(
                dockerClient,
                new ExecutionLanguageSpecResolver(properties),
                properties
        );
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (dockerClient != null) {
            dockerClient.close();
        }
        if (dockerHttpClient != null) {
            dockerHttpClient.close();
        }
    }

    @Test
    @DisplayName("Python source prints to stdout and returns COMPLETED")
    void pythonSourcePrintsToStdoutAndReturnsCompleted() {
        SandboxExecutionResult result = runner.run(snapshot("PYTHON", "print('Python source prints')"));

        assertThat(result.status())
                .withFailMessage("python stdout=%s stderr=%s", result.stdout(), result.stderr())
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.stdout()).contains("Python source prints");
        assertThat(result.stderr()).isBlank();
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("Java Main source prints to stdout and returns COMPLETED")
    void javaMainSourcePrintsToStdoutAndReturnsCompleted() {
        SandboxExecutionResult result = runner.run(snapshot(
                "JAVA",
                "public class Main { public static void main(String[] args) { System.out.println(\"Java Main source prints\"); } }"
        ));

        assertThat(result.status())
                .withFailMessage("java stdout=%s stderr=%s", result.stdout(), result.stderr())
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.stdout()).contains("Java Main source prints");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("Java source missing the required Main contract returns FAILED with a clear message")
    void invalidJavaSourceMissingRequiredMainContractReturnsControlledFailure() {
        SandboxExecutionResult result = runner.run(snapshot(
                "JAVA",
                "public class NotMain { public static void main(String[] args) {} }"
        ));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.stderr()).contains("public class Main");
    }

    @Test
    @DisplayName("An infinite loop hits the ten-second timeout and returns TIMED_OUT")
    void infiniteLoopHitsTenSecondTimeout() {
        SandboxExecutionResult result = runner.run(snapshot(
                "PYTHON",
                "while True:\n    pass\n"
        ));

        assertThat(result.status())
                .withFailMessage("timeout stdout=%s stderr=%s", result.stdout(), result.stderr())
                .isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(result.exitCode()).isNull();
    }

    @Test
    @DisplayName("Python outbound network access fails under networkMode=none")
    void outboundNetworkAccessFailsUnderNetworkModeNone() {
        SandboxExecutionResult result = runner.run(snapshot(
                "PYTHON",
                "import socket\nsocket.create_connection(('1.1.1.1', 80), 1)\n"
        ));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.stderr()).isNotBlank();
    }

    @Test
    @DisplayName("Python write outside the tmpfs workspace fails with readonlyRootfs and non-root user 65534:65534")
    void writeOutsideTmpfsWorkspaceFailsWithReadonlyRootfsAndNonRootUser() {
        SandboxExecutionResult result = runner.run(snapshot(
                "PYTHON",
                "with open('/readonly-rootfs.txt', 'w') as handle:\n    handle.write('blocked')\n"
        ));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.stderr()).isNotBlank();
    }

    private static ExecutionSourceSnapshot snapshot(String language, String sourceCode) {
        return new ExecutionSourceSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "integration@example.com",
                language,
                9L,
                sourceCode
        );
    }
}
