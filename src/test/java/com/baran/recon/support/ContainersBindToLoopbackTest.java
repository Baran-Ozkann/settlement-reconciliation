package com.baran.recon.support;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks the Docker daemon, not Testcontainers, where each container of this test run publishes its
 * ports. Reading the daemon is what makes this a check of the outcome rather than of the
 * configuration: a binding the factory asked for but Docker did not honour would show up here.
 *
 * <p>Every container carrying this JVM's Testcontainers session label is inspected, not only the
 * one started below, so a helper container the library starts on its own - Ryuk, when it is
 * enabled - fails the test as well.
 */
@DisplayName("Test containers publish on 127.0.0.1 only and are never reused")
class ContainersBindToLoopbackTest {

    private static final String SESSION_LABEL = "org.testcontainers.sessionId";

    /**
     * Loopback, so the proof container is reachable from this machine alone, but not 127.0.0.1, so
     * the check must report it. Docker's default of every interface would make the same point, but
     * publishing there is what CLAUDE.md 3.2 forbids, for a few seconds as much as for good.
     */
    private static final String OTHER_LOOPBACK_ADDRESS = "127.0.0.2";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainer() {
        postgres = LoopbackContainers.postgres();
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    @Test
    @DisplayName("every published port of every container in this session is on 127.0.0.1")
    void everyPublishedPortIsOnLoopback() {
        List<ContainerPort> published = publishedPorts(sessionContainers());

        assertThat(published).as("published ports of this session's containers").isNotEmpty();
        assertThat(offLoopback(published)).as("ports published off 127.0.0.1").isEmpty();
    }

    /**
     * The break proof for the test above, kept as a test so it runs on every build: a container
     * whose binding the factory did not choose is reported by the same check, read from the daemon.
     */
    @Test
    @DisplayName("break proof: a port published on any other address is reported")
    void aPortOffLoopbackIsReported() {
        // The address is spelled out, not OTHER_LOOPBACK_ADDRESS: ci/check-rules.sh accepts only a
        // literal loopback address in a binding, since a constant could hold anything.
        try (PostgreSQLContainer<?> elsewhere = LoopbackContainers.postgres()
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        Arrays.stream(command.getExposedPorts())
                                .map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.2"), port))
                                .toList()))) {
            elsewhere.start();

            List<ContainerPort> reported = offLoopback(publishedPorts(sessionContainers().stream()
                    .filter(container -> container.getId().equals(elsewhere.getContainerId()))
                    .toList()));

            assertThat(reported).extracting(ContainerPort::getIp).containsOnly(OTHER_LOOPBACK_ADDRESS);
        }
    }

    @Test
    @DisplayName("the mapped port is reachable through the loopback address")
    void mappedPortAnswersOnLoopback() {
        assertThat(postgres.getHost()).isEqualTo(LoopbackContainers.LOOPBACK);
        assertThat(postgres.getJdbcUrl()).startsWith("jdbc:postgresql://127.0.0.1:");
    }

    @Test
    @DisplayName("TDD 9.1: the container is not marked for reuse")
    void containerIsNotReused() {
        assertThat(postgres.isShouldBeReused()).isFalse();
    }

    private static List<ContainerPort> publishedPorts(List<Container> containers) {
        return containers.stream()
                .flatMap(container -> Arrays.stream(container.getPorts()))
                .filter(port -> port.getPublicPort() != null)
                .toList();
    }

    private static List<ContainerPort> offLoopback(List<ContainerPort> ports) {
        return ports.stream()
                .filter(port -> !LoopbackContainers.LOOPBACK.equals(port.getIp()))
                .toList();
    }

    private static List<Container> sessionContainers() {
        DockerClient docker = DockerClientFactory.instance().client();
        return docker.listContainersCmd()
                .withLabelFilter(Map.of(SESSION_LABEL, DockerClientFactory.SESSION_ID))
                .exec()
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
