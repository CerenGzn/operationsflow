package com.operationsflow;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DemoViewerMigrationIT {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Test void expandsV7RoleConstraintWithoutChangingExistingUsersOrCreatingAccounts(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).target("7").load().migrate();var jdbc=new JdbcTemplate(ds);
        String hash=new BCryptPasswordEncoder(12).encode("Synthetic-migration-password-11!");
        for(String role:new String[]{"ADMIN","SALES_OPERATOR","WAREHOUSE_OPERATOR"})
            jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) values (?,?,?, ?,?,false)",UUID.randomUUID(),role.toLowerCase()+"@example.invalid","Synthetic migration user",hash,role);
        var before=jdbc.queryForList("select * from operationsflow.app_user order by email");
        var flyway=Flyway.configure().dataSource(ds).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select * from operationsflow.app_user order by email")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class)).isZero();
        jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role) values (?,'reviewer@example.invalid','Synthetic viewer',?,'DEMO_VIEWER')",UUID.randomUUID(),hash);
        assertThatThrownBy(()->jdbc.update("update operationsflow.app_user set role='UNKNOWN' where role='DEMO_VIEWER'"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.app_user where role='DEMO_VIEWER'",Long.class)).isEqualTo(1);
    }
}
