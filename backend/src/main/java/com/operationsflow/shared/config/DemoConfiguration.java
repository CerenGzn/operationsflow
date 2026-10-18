package com.operationsflow.shared.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateDemoSettings(Environment environment) {
        // Validate before datasource/Flyway creation; never include supplied values in errors.
        return beanFactory -> validate(environment);
    }

    static void validate(Environment environment) {
        String host = environment.getProperty("DEMO_DB_HOST", "");
        String database = environment.getProperty("DEMO_DB_NAME", "");
        if (!host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.neon\\.tech") || host.contains("-pooler")) {
            throw new IllegalStateException("Demo requires a direct Neon hostname without URL parameters or credentials.");
        }
        if (!database.matches("[a-zA-Z0-9_]{1,63}")) {
            throw new IllegalStateException("Demo database name must contain only letters, digits or underscores (1–63).");
        }
        String expected = jdbcUrl(host, database);
        if (!expected.equals(environment.getProperty("spring.datasource.url"))) {
            throw new IllegalStateException("Demo JDBC configuration must retain verified TLS and bounded connection timeouts.");
        }
        if (!"true".equals(environment.getProperty("DEMO_HTTPS_EDGE"))
            || !environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)
            || !"none".equals(environment.getProperty("server.forward-headers-strategy"))) {
            throw new IllegalStateException("Demo requires the HTTPS-only edge contract, Secure cookies and ignored forwarding headers.");
        }
    }

    static String jdbcUrl(String host, String database) {
        return "jdbc:postgresql://" + host + ":5432/" + database
            + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
            + "&connectTimeout=15&socketTimeout=60&tcpKeepAlive=true";
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> demoHttpsEdge() {
        // Render enforces HTTPS at its edge; its internal HTTP connector is not publicly reachable.
        // Do not trust client-controlled Forwarded/X-Forwarded-* headers, including source IPs.
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setProxyPort(443);
        });
    }
}
