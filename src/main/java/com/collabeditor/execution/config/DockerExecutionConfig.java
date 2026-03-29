package com.collabeditor.execution.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring configuration for the docker-java client used by the sandbox runner.
 *
 * <p>Builds a reusable {@link DockerClient} bean from
 * {@link DefaultDockerClientConfig}, an {@link ApacheDockerHttpClient},
 * and {@link DockerClientImpl#getInstance(DefaultDockerClientConfig, DockerHttpClient)}.
 */
@Configuration
public class DockerExecutionConfig {

    @Bean
    public DefaultDockerClientConfig dockerClientConfig() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    }

    @Bean
    public DockerHttpClient dockerHttpClient(DefaultDockerClientConfig config) {
        return new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(4)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public DockerClient dockerClient(DefaultDockerClientConfig config, DockerHttpClient httpClient) {
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
