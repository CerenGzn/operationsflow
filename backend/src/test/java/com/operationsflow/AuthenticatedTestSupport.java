package com.operationsflow;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.operationsflow.security.domain.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Explicit test-only identity; production filters, CSRF and account-version validation stay enabled. */
abstract class AuthenticatedTestSupport {
    static final UUID TEST_ADMIN=UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String HASH=new BCryptPasswordEncoder(12).encode("Synthetic-test-password-5!");
    @Autowired JdbcTemplate authJdbc;
    @Autowired WebApplicationContext authContext;
    @BeforeEach void authenticateFixture(){
        authJdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) values (?,'fixture-admin@example.invalid','Synthetic test admin',?,'ADMIN',false) on conflict do nothing",TEST_ADMIN,HASH);
        var auth=UsernamePasswordAuthenticationToken.authenticated(new SessionActor(TEST_ADMIN,Role.ADMIN,0),null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        var mvc=MockMvcBuilders.webAppContextSetup(authContext).apply(springSecurity()).defaultRequest(get("/").with(authentication(auth)).with(csrf())).alwaysDo(result -> SecurityContextHolder.getContext().setAuthentication(auth)).build();
        ReflectionTestUtils.setField(this,"mvc",mvc);
    }
    @AfterEach void clearFixture(){SecurityContextHolder.clearContext();}
}
