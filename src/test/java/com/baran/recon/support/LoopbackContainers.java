package com.baran.recon.support;

import java.util.Arrays;
import java.util.List;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The only place test containers are built. Every one publishes its ports on IPv4 loopback alone:
 * Docker's default is every interface, which on a laptop offers a throwaway database with a known
 * password to whichever network the laptop is on. ci/check-rules.sh fails the build on a container
 * constructed anywhere else, so the rule does not depend on this class being used by convention.
 *
 * <p>Containers are never reused across runs, for the reason in the ledger's README: a warm
 * container keeps a previous run's schema, and a suite once passed there with Flyway disabled.
 */
public final class LoopbackContainers {

    static final String LOOPBACK = "127.0.0.1";

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /** The image the ledger's own broker and tests run, so both services meet one Kafka version. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    private LoopbackContainers() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return onLoopback(new PostgreSQLContainer<>(POSTGRES_IMAGE)).withReuse(false);
    }

    /**
     * The broker advertises the address the tests connect to, which TESTCONTAINERS_HOST_OVERRIDE
     * sets to 127.0.0.1 in the surefire configuration, so clients reach it through the loopback
     * binding below and never through another interface.
     */
    public static KafkaContainer kafka() {
        return onLoopback(new KafkaContainer(KAFKA_IMAGE)).withReuse(false);
    }

    /**
     * Testcontainers binds each exposed port to an empty host address, which Docker reads as every
     * interface. This runs after that and replaces each binding with one on loopback, still on a
     * random host port, so mapped-port lookups keep working unchanged.
     */
    static <T extends GenericContainer<?>> T onLoopback(T container) {
        container.withCreateContainerCmdModifier(LoopbackContainers::bindExposedPortsToLoopback);
        return container;
    }

    private static void bindExposedPortsToLoopback(CreateContainerCmd command) {
        List<PortBinding> bindings = Arrays.stream(command.getExposedPorts())
                .map(port -> new PortBinding(Ports.Binding.bindIp(LOOPBACK), port))
                .toList();
        command.getHostConfig().withPortBindings(bindings);
    }
}
