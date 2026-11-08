package io.github.xiiiiimu.content;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.testcontainers.DockerClientFactory;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-only helper for brokers that must advertise a fixed host port on Windows.
 * Does not change production lease/send timeouts. Never touches the compose project
 * containers named content-*.
 */
final class FixedHostPortGuard {
    private FixedHostPortGuard() {}

    static void reclaimOrphans(int hostPort, String purpose) {
        DockerClient docker = DockerClientFactory.instance().client();
        for (Container container : docker.listContainersCmd().withShowAll(true).exec()) {
            String name = primaryName(container);
            if (name.startsWith("content-")) continue;
            if (!publishesHostPort(container, hostPort)) continue;
            // Only reclaim anonymous/testcontainers leftovers; refuse to steal named foreign stacks.
            boolean orphan = name.matches("[a-z]+_[a-z]+") || name.startsWith("testcontainers-");
            assertThat(orphan).as(
                    "Fixed host port %s required for %s is held by non-testcontainers container '%s'. Stop it before the IT suite.",
                    hostPort, purpose, name)
                    .isTrue();
            docker.removeContainerCmd(container.getId()).withForce(true).exec();
        }
        for (Container container : docker.listContainersCmd().withShowAll(false).exec()) {
            String name = primaryName(container);
            if (name.startsWith("content-")) continue;
            assertThat(publishesHostPort(container, hostPort))
                    .as("Fixed host port %s still allocated after orphan reclaim for %s by container '%s'", hostPort, purpose, name)
                    .isFalse();
        }
    }

    private static String primaryName(Container container) {
        if (container.getNames() == null || container.getNames().length == 0) return "";
        String name = container.getNames()[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static boolean publishesHostPort(Container container, int hostPort) {
        ContainerPort[] ports = container.getPorts();
        if (ports == null) return false;
        for (ContainerPort port : ports) {
            if (port.getPublicPort() != null && port.getPublicPort() == hostPort) return true;
        }
        return false;
    }
}
