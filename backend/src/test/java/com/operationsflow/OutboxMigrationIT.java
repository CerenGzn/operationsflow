package com.operationsflow;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxMigrationIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Test void additiveUpgradePreservesExistingUsersAndDoesNotBackfillEvents() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).target("8").load().migrate(); var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role) values (?,'migration@example.invalid','Synthetic migration user',?,'DEMO_VIEWER')",UUID.randomUUID(),new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("Synthetic-migration-only-13!"));
        var before = jdbc.queryForList("select * from operationsflow.app_user");
        var flyway = Flyway.configure().dataSource(ds).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select * from operationsflow.app_user")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.event_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.event_inbox",Long.class)).isZero();
    }
}
