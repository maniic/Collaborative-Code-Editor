package com.collabeditor.execution;

import com.collabeditor.execution.config.ExecutionProperties;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.model.SandboxExecutionResult;
import com.collabeditor.execution.service.DockerSandboxRunner;
import com.collabeditor.execution.service.ExecutionLanguageSpecResolver;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DockerSandboxRunnerTest {

    @Mock
    private DockerClient dockerClient;

    private DockerSandboxRunner runner;

    @BeforeEach
    void setUp() {
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
        runner = new DockerSandboxRunner(
                dockerClient,
                new ExecutionLanguageSpecResolver(properties),
                properties
        );
    }

    @Test
    @DisplayName("Python run uses python:3.12-slim image and python /input/main.py command")
    void pythonRunUsesCorrectImageAndCommand() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com",
                "PYTHON", 1L, "print('hello')");

        CreateContainerCmd createCmd = mockExecutionLifecycle("container-py-1", 0);

        runner.run(snapshot);

        ArgumentCaptor<String> imageCaptor = ArgumentCaptor.forClass(String.class);
        verify(createCmd).withImage(imageCaptor.capture());
        assertThat(imageCaptor.getValue()).isEqualTo("python:3.12-slim");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(createCmd).withCmd(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue()).containsExactly("python", "/input/main.py");
    }

    @Test
    @DisplayName("Java run uses eclipse-temurin:17-jdk-jammy and cp /input/Main.java /workspace/Main.java && javac -d /workspace/out /workspace/Main.java && java -cp /workspace/out Main")
    void javaRunUsesCorrectImageAndCommand() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user@test.com",
                "JAVA",
                1L,
                "public class Main { public static void main(String[] args) { System.out.println(\"hello\"); } }"
        );

        CreateContainerCmd createCmd = mockExecutionLifecycle("container-java-1", 0);

        runner.run(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(createCmd).withCmd(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue()).containsExactly(
                "sh",
                "-lc",
                "cp /input/Main.java /workspace/Main.java && javac -d /workspace/out /workspace/Main.java && java -cp /workspace/out Main"
        );
    }

    @Test
    @DisplayName("Container HostConfig applies exact sandbox limits: memory, CPU, network, readonly rootfs, user, and timeout cleanup")
    void hostConfigAppliesExactSandboxLimits() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com",
                "PYTHON", 1L, "print('hello')");

        CreateContainerCmd createCmd = mockExecutionLifecycle("container-limits-1", 0);

        runner.run(snapshot);

        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfigCaptor.capture());
        HostConfig hostConfig = hostConfigCaptor.getValue();

        assertThat(hostConfig.getMemory()).isEqualTo(268435456L);
        assertThat(hostConfig.getMemorySwap()).isEqualTo(268435456L);
        assertThat(hostConfig.getNanoCPUs()).isEqualTo(500_000_000L);
        assertThat(hostConfig.getNetworkMode()).isEqualTo("none");
        assertThat(hostConfig.getReadonlyRootfs()).isTrue();

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(createCmd).withUser(userCaptor.capture());
        assertThat(userCaptor.getValue()).isEqualTo("65534:65534");
    }

    @Test
    @DisplayName("Timed-out container is killed and removed with TIMED_OUT status")
    void timedOutContainerIsKilledAndRemoved() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com",
                "PYTHON", 1L, "import time; time.sleep(60)");

        String containerId = "container-timeout-1";
        mockExecutionLifecycle(containerId, null);
        KillContainerCmd killContainerCmd = mock(KillContainerCmd.class);
        when(dockerClient.killContainerCmd(containerId)).thenReturn(killContainerCmd);

        SandboxExecutionResult result = runner.run(snapshot);

        assertThat(result.status()).isEqualTo(ExecutionStatus.TIMED_OUT);
        verify(dockerClient).killContainerCmd(containerId);
        verify(dockerClient).removeContainerCmd(containerId);
    }

    @Test
    @DisplayName("Java source missing the required Main contract returns FAILED with a clear validation message")
    void javaSourceMissingMainClassReturnsFailedResult() {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user@test.com",
                "JAVA",
                1L,
                "public class Foo { public static void main(String[] args) {} }"
        );

        SandboxExecutionResult result = runner.run(snapshot);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.stderr()).contains("public class Main");
    }

    @Test
    @DisplayName("Successful Python run returns COMPLETED with exit code 0")
    void successfulPythonRunReturnsCompleted() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com",
                "PYTHON", 1L, "print('hello')");

        mockExecutionLifecycle("container-success-1", 0);

        SandboxExecutionResult result = runner.run(snapshot);

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("Non-zero exit code returns FAILED status")
    void nonZeroExitCodeReturnsFailed() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com",
                "PYTHON", 1L, "import sys; sys.exit(1)");

        mockExecutionLifecycle("container-fail-1", 1);

        SandboxExecutionResult result = runner.run(snapshot);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("Container is always removed in finally block even on success")
    void containerAlwaysRemovedOnSuccess() throws Exception {
        ExecutionSourceSnapshot snapshot = new ExecutionSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com",
                "PYTHON", 1L, "print('ok')");

        String containerId = "container-cleanup-1";
        mockExecutionLifecycle(containerId, 0);

        runner.run(snapshot);

        verify(dockerClient).removeContainerCmd(containerId);
    }

    private CreateContainerCmd mockExecutionLifecycle(String containerId, Integer exitCode) throws Exception {
        mockImageInspection();

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn(containerId);
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);

        StartContainerCmd startContainerCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(containerId)).thenReturn(startContainerCmd);

        WaitContainerCmd waitContainerCmd = mock(WaitContainerCmd.class);
        WaitContainerResultCallback waitCallback = mock(WaitContainerResultCallback.class);
        when(dockerClient.waitContainerCmd(containerId)).thenReturn(waitContainerCmd);
        when(waitContainerCmd.exec(any(WaitContainerResultCallback.class))).thenReturn(waitCallback);
        when(waitCallback.awaitStatusCode(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(exitCode);

        LogContainerCmd logContainerCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd(containerId)).thenReturn(logContainerCmd);
        ResultCallback.Adapter<Frame> logCallback = mock(ResultCallback.Adapter.class);
        when(logContainerCmd.exec(any(ResultCallback.Adapter.class))).thenReturn(logCallback);
        when(logCallback.awaitCompletion(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);

        RemoveContainerCmd removeContainerCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd(containerId)).thenReturn(removeContainerCmd);

        return createCmd;
    }

    private void mockImageInspection() {
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));
    }
}
