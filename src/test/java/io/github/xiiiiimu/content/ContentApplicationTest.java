package io.github.xiiiiimu.content;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ContentApplicationTest {

    @Test
    void loadsApplicationWiringWithoutMiddleware() {
        new WebApplicationContextRunner()
                .withUserConfiguration(ContentApplication.class)
                .withPropertyValues(
                        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "management.endpoint.health.probes.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ContentApplication.class));
    }
}
