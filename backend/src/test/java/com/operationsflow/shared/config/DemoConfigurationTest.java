package com.operationsflow.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.apache.catalina.connector.Connector;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoConfigurationTest {
    private MockEnvironment valid() {
        return new MockEnvironment().withProperty("DEMO_DB_HOST", "ep-synthetic.eu-central-1.aws.neon.tech")
            .withProperty("DEMO_DB_NAME", "demo")
            .withProperty("spring.datasource.url", DemoConfiguration.jdbcUrl("ep-synthetic.eu-central-1.aws.neon.tech", "demo"))
            .withProperty("DEMO_HTTPS_EDGE", "true")
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("server.forward-headers-strategy", "none");
    }

    @Test void acceptsVerifiedDirectNeonConfiguration() {
        assertThatCode(() -> DemoConfiguration.validate(valid())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "ep-demo-pooler.eu.aws.neon.tech", "neon.tech.evil.invalid",
        "ep-demo.neon.tech?sslmode=disable", "user:password@ep-demo.neon.tech"})
    void rejectsUnsafeOrPooledHostsWithoutEchoingValues(String host) {
        assertThatThrownBy(() -> DemoConfiguration.validate(valid().withProperty("DEMO_DB_HOST", host)))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "demo?sslmode=disable", "demo/path", "demo&password=secret"})
    void rejectsDatabaseUrlInjection(String name) {
        assertThatThrownBy(() -> DemoConfiguration.validate(valid().withProperty("DEMO_DB_NAME", name)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsTlsDowngradesAndUntrustedProxyConfiguration() {
        assertThatThrownBy(() -> DemoConfiguration.validate(valid()
            .withProperty("spring.datasource.url", "jdbc:postgresql://ep-demo.neon.tech/demo?sslmode=require")))
            .isInstanceOf(IllegalStateException.class);
        for (var key : new String[]{"DEMO_HTTPS_EDGE", "server.servlet.session.cookie.secure"}) {
            assertThatThrownBy(() -> DemoConfiguration.validate(valid().withProperty(key, "false")))
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> DemoConfiguration.validate(valid()
            .withProperty("server.forward-headers-strategy", "framework")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void profileUsesVerifiedTlsSmallIdlePoolAndDatabaseFreeProbe() throws Exception {
        var env = valid();
        var sources = new YamlPropertySourceLoader().load("demo", new ClassPathResource("application-demo.yml"));
        sources.forEach(source -> env.getPropertySources().addFirst(source));
        DemoConfiguration.validate(env);
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(3);
        assertThat(env.getProperty("spring.datasource.hikari.minimum-idle", Integer.class)).isZero();
        assertThat(env.getProperty("spring.datasource.hikari.keepalive-time", Integer.class)).isZero();
        assertThat(env.getProperty("management.endpoint.health.group.demo.include")).isEqualTo("readinessState");
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
    }

    @Test void fixedHttpsConnectorDoesNotDependOnForwardingHeaders() {
        var factory = mock(TomcatServletWebServerFactory.class);
        new DemoConfiguration().demoHttpsEdge().customize(factory);
        var customizer = ArgumentCaptor.forClass(TomcatConnectorCustomizer.class);
        verify(factory).addConnectorCustomizers(customizer.capture());
        var connector = new Connector();
        customizer.getValue().customize(connector);
        assertThat(connector.getScheme()).isEqualTo("https");
        assertThat(connector.getSecure()).isTrue();
        assertThat(connector.getProxyPort()).isEqualTo(443);
    }
}
