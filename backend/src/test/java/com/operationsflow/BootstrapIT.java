package com.operationsflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"BOOTSTRAP_ADMIN_EMAIL=bootstrap@example.invalid","BOOTSTRAP_ADMIN_PASSWORD=Synthetic-bootstrap-test-5!"})
@Testcontainers
class BootstrapIT {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Test void firstStartupCreatesExactlyOneUsableAdministratorAndSystemAudit(){
        var account=jdbc.queryForMap("select * from operationsflow.app_user");
        assertThat(account.get("email")).isEqualTo("bootstrap@example.invalid");
        assertThat(account.get("role")).isEqualTo("ADMIN");
        assertThat(account.get("active")).isEqualTo(true);
        assertThat(account.get("must_change_password")).isEqualTo(false);
        assertThat(passwords.matches("Synthetic-bootstrap-test-5!",account.get("password_hash").toString())).isTrue();
        var audit=jdbc.queryForMap("select * from operationsflow.audit_event");
        assertThat(audit.get("actor_kind")).isEqualTo("SYSTEM");
        assertThat(audit.get("actor_id")).isNull();
        assertThat(audit.get("target_id")).isEqualTo(account.get("id"));
        assertThat(audit.get("action")).isEqualTo("ADMIN_BOOTSTRAPPED");
    }
}
