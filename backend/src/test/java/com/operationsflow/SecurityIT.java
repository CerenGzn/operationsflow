package com.operationsflow;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.security.core.context.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.catalog.api.ProductContracts;
import com.operationsflow.inventory.application.InventoryService;
import com.operationsflow.inventory.api.InventoryContracts;
import com.operationsflow.security.application.IdentityService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class SecurityIT {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PasswordEncoder passwords;@Autowired TestRestTemplate http;
    @Autowired IdentityService identities;@Autowired ProductService products;@Autowired InventoryService inventory;@Autowired PlatformTransactionManager transactions;
    private static final String PASSWORD="Synthetic-security-test-5!";
    private String hash;private Account admin;
    record Account(UUID id,String email,String role){}
    record Token(MockHttpSession session,String header,String value){}
    @BeforeEach void setup(){jdbc.update("update operationsflow.app_user set active=false");hash=passwords.encode(PASSWORD);admin=account("ADMIN",false);SecurityContextHolder.clearContext();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private Account account(String role,boolean mustChange){UUID id=UUID.randomUUID();String email="synthetic-"+id+"@example.invalid";jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) values (?,?,'Synthetic user',?,?,?)",id,email,hash,role,mustChange);return new Account(id,email,role);}
    private Token token(MockHttpSession session)throws Exception{
        var request=get("/api/v1/auth/csrf");if(session!=null)request.session(session);
        var result=mvc.perform(request).andExpect(status().isOk()).andReturn();var body=json.readTree(result.getResponse().getContentAsString());
        return new Token((MockHttpSession)result.getRequest().getSession(false),body.get("headerName").asText(),body.get("token").asText());
    }
    private MockHttpSession login(Account account)throws Exception{return login(account,PASSWORD);}
    private MockHttpSession login(Account account,String password)throws Exception{
        var token=token(null);String old=token.session().getId();
        var result=mvc.perform(post("/api/v1/auth/login").session(token.session()).header(token.header(),token.value()).with(r->{r.setRemoteAddr(account.id().toString());return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("email",account.email(),"password",password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(account.role())).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);assertThat(session.getId()).isNotEqualTo(old);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(password,"passwordHash","securityVersion");return session;
    }
    private MvcResult mutate(MockHttpSession session,String method,String path,Object body)throws Exception{
        var csrf=token(session);return mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),path).session(session).header(csrf.header(),csrf.value()).contentType("application/json").content(json.writeValueAsString(body))).andReturn();
    }
    private Map<String,Object> update(Account a,String role,boolean active,long version){return Map.of("email",a.email(),"displayName","Synthetic updated user","role",role,"active",active,"version",version);}
    private long auditCount(String action){return jdbc.queryForObject("select count(*) from operationsflow.audit_event where action=?",Long.class,action);}
    private void use(MockHttpSession session){SecurityContextHolder.setContext((SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT"));}

    @Test void loginRotatesSessionAndLogoutInvalidatesItAndCsrf()throws Exception{
        var session=login(admin);mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(admin.id().toString()));
        var result=mutate(session,"POST","/api/v1/auth/logout",Map.of());assertThat(result.getResponse().getStatus()).isEqualTo(204);assertThat(session.isInvalid()).isTrue();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly","SameSite=Strict","Max-Age=0");
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.audit_event where actor_id=? and action in ('LOGIN_SUCCEEDED','LOGOUT')",Long.class,admin.id())).isEqualTo(2L);
    }
    @Test void realServletSessionCookieIsHttpOnlyStrictAndLocalHttpCompatible(){
        var response=http.getForEntity("/api/v1/auth/csrf",String.class);assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("JSESSIONID=","HttpOnly","SameSite=Strict").doesNotContain("Secure");
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
    }
    @Test void csrfIsMandatoryForLoginAndEveryAuthenticatedMutation()throws Exception{
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("email",admin.email(),"password",PASSWORD)))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var session=login(admin);
        for(String path:List.of("/api/v1/products","/api/v1/warehouses","/api/v1/customers","/api/v1/orders","/api/v1/inventory/receipts","/api/v1/users","/api/v1/auth/logout","/api/v1/auth/change-password"))
            mvc.perform(post(path).session(session).contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }
    @Test void unauthorizedDirectReadsAndPublicSignupAreRejected()throws Exception{
        for(String path:List.of("/api/v1/products","/api/v1/warehouses","/api/v1/customers","/api/v1/orders","/api/v1/inventory/balances","/api/v1/inventory/availability","/api/v1/inventory/movements","/api/v1/inventory/reservations","/api/v1/users","/api/v1/audit-events","/v3/api-docs","/api/v1/system"))mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var csrf=token(null);mvc.perform(post("/api/v1/users").session(csrf.session()).header(csrf.header(),csrf.value()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
    @Test void roleMatrixProtectsEveryOperationalWriteFamily()throws Exception{
        var warehouse=login(account("WAREHOUSE_OPERATOR",false));var sales=login(account("SALES_OPERATOR",false));
        String uuid=UUID.randomUUID().toString();
        for(var session:List.of(warehouse,sales)){
            for(String path:List.of("/api/v1/products","/api/v1/products/"+uuid+"/skus","/api/v1/products/"+uuid+"/deactivate","/api/v1/warehouses","/api/v1/warehouses/"+uuid+"/deactivate","/api/v1/users","/api/v1/users/"+uuid+"/reset-password"))assertThat(mutate(session,"POST",path,Map.of()).getResponse().getStatus()).isEqualTo(403);
            for(String path:List.of("/api/v1/products/"+uuid,"/api/v1/warehouses/"+uuid,"/api/v1/users/"+uuid))assertThat(mutate(session,"PUT",path,Map.of()).getResponse().getStatus()).isEqualTo(403);
        }
        for(String path:List.of("/api/v1/inventory/receipts","/api/v1/inventory/adjustments","/api/v1/inventory/reservations","/api/v1/inventory/reservations/"+uuid+"/release","/api/v1/inventory/reservations/"+uuid+"/consume")){
            assertThat(mutate(sales,"POST",path,Map.of()).getResponse().getStatus()).isEqualTo(403);
            assertThat(mutate(warehouse,"POST",path,Map.of()).getResponse().getStatus()).isEqualTo(400);
        }
        for(String path:List.of("/api/v1/customers","/api/v1/customers/"+uuid+"/deactivate","/api/v1/orders","/api/v1/orders/"+uuid+"/cancel")){
            assertThat(mutate(warehouse,"POST",path,Map.of()).getResponse().getStatus()).isEqualTo(403);
            assertThat(mutate(sales,"POST",path,Map.of()).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(mutate(warehouse,"PUT","/api/v1/customers/"+uuid,Map.of()).getResponse().getStatus()).isEqualTo(403);
        assertThat(mutate(sales,"PUT","/api/v1/customers/"+uuid,Map.of()).getResponse().getStatus()).isEqualTo(400);
    }
    @Test void operatorReadAccessIsLimitedToDocumentedNeeds()throws Exception{
        var warehouse=login(account("WAREHOUSE_OPERATOR",false));var sales=login(account("SALES_OPERATOR",false));
        for(var session:List.of(warehouse,sales)){
            for(String path:List.of("/api/v1/products","/api/v1/warehouses","/api/v1/orders","/api/v1/inventory/balances","/api/v1/system"))mvc.perform(get(path).session(session)).andExpect(status().isOk());
            for(String path:List.of("/api/v1/users","/api/v1/audit-events","/v3/api-docs"))mvc.perform(get(path).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/customers").session(warehouse)).andExpect(status().isForbidden());mvc.perform(get("/api/v1/customers").session(sales)).andExpect(status().isOk());
        for(String path:List.of("/api/v1/inventory/movements","/api/v1/inventory/reservations")){mvc.perform(get(path).session(warehouse)).andExpect(status().isOk());mvc.perform(get(path).session(sales)).andExpect(status().isForbidden());}
    }
    @Test void userCreationHashesPasswordRejectsDuplicatesAndNeverReturnsCredentials()throws Exception{
        var session=login(admin);String email="new-"+UUID.randomUUID()+"@example.invalid";
        var body=Map.of("email",email,"displayName","Synthetic sales","role","SALES_OPERATOR","password",PASSWORD);
        var created=mutate(session,"POST","/api/v1/users",body);assertThat(created.getResponse().getStatus()).isEqualTo(201);assertThat(created.getResponse().getContentAsString()).doesNotContain(PASSWORD,"passwordHash");
        var row=jdbc.queryForMap("select password_hash,must_change_password from operationsflow.app_user where email=?",email);
        assertThat(row.get("password_hash").toString()).startsWith("$2a$12$").isNotEqualTo(PASSWORD);assertThat(passwords.matches(PASSWORD,row.get("password_hash").toString())).isTrue();assertThat(row.get("must_change_password")).isEqualTo(true);
        assertThat(mutate(session,"POST","/api/v1/users",body).getResponse().getStatus()).isEqualTo(409);
        assertThat(mutate(session,"POST","/api/v1/users",Map.of("email",email,"displayName","Synthetic","role","ADMIN","password","short")).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(get("/api/v1/users").session(session)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(hash))));
    }
    @Test void deactivationAndRoleEditsRevokePreviouslyAuthenticatedSessions()throws Exception{
        var operator=account("SALES_OPERATOR",false);var session=login(operator);var root=login(admin);
        assertThat(mutate(root,"PUT","/api/v1/users/"+operator.id(),update(operator,"SALES_OPERATOR",false,0)).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/v1/orders").session(session)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));assertThat(session.isInvalid()).isTrue();
        var other=account("WAREHOUSE_OPERATOR",false);var old=login(other);
        assertThat(mutate(root,"PUT","/api/v1/users/"+other.id(),update(other,"SALES_OPERATOR",true,0)).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/v1/inventory/movements").session(old)).andExpect(status().isUnauthorized());
    }
    @Test void resetForcesPasswordChangeAndOldSessionsAndPasswordStopWorking()throws Exception{
        var operator=account("SALES_OPERATOR",false);var old=login(operator);var root=login(admin);String temporary="Synthetic-reset-password!",next="Synthetic-new-password!";
        assertThat(mutate(root,"POST","/api/v1/users/"+operator.id()+"/reset-password",Map.of("password",temporary,"version",0)).getResponse().getStatus()).isEqualTo(204);
        mvc.perform(get("/api/v1/auth/session").session(old)).andExpect(status().isUnauthorized());
        var session=login(operator,temporary);mvc.perform(get("/api/v1/orders").session(session)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        assertThat(mutate(session,"POST","/api/v1/auth/change-password",Map.of("currentPassword","Incorrect password!","newPassword",next)).getResponse().getStatus()).isEqualTo(400);
        assertThat(mutate(session,"POST","/api/v1/auth/change-password",Map.of("currentPassword",temporary,"newPassword",next)).getResponse().getStatus()).isEqualTo(204);assertThat(session.isInvalid()).isTrue();
        var changed=login(operator,next);mvc.perform(get("/api/v1/orders").session(changed)).andExpect(status().isOk());
        assertThat(passwords.matches(PASSWORD,jdbc.queryForObject("select password_hash from operationsflow.app_user where id=?",String.class,operator.id()))).isFalse();
    }
    @Test void finalActiveAdministratorCannotBeDisabledDemotedOrLostByRace()throws Exception{
        var root=login(admin);
        for(var body:List.of(update(admin,"ADMIN",false,0),update(admin,"SALES_OPERATOR",true,0)))assertThat(mutate(root,"PUT","/api/v1/users/"+admin.id(),body).getResponse().getStatus()).isEqualTo(409);
        var other=account("ADMIN",false);var second=login(other);var tokens=List.of(token(root),token(second));var accounts=List.of(admin,other);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{start.await();var t=tokens.get(index);return mvc.perform(put("/api/v1/users/"+accounts.get(index).id()).session(t.session()).header(t.header(),t.value()).contentType("application/json").content(json.writeValueAsString(update(accounts.get(index),"SALES_OPERATOR",true,0)))).andReturn().getResponse().getStatus();}));}
            start.countDown();assertThat(List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.app_user where active and role='ADMIN'",Long.class)).isEqualTo(1);
    }
    @Test void staleAndEscalationPayloadsCannotChangePrivileges()throws Exception{
        var operator=account("SALES_OPERATOR",false);var sales=login(operator);var root=login(admin);
        assertThat(mutate(sales,"PUT","/api/v1/users/"+operator.id(),update(operator,"ADMIN",true,0)).getResponse().getStatus()).isEqualTo(403);
        assertThat(mutate(root,"PUT","/api/v1/users/"+operator.id(),update(operator,"SALES_OPERATOR",true,9)).getResponse().getStatus()).isEqualTo(409);
        assertThat(mutate(sales,"POST","/api/v1/customers",Map.of("role","ADMIN")).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select role from operationsflow.app_user where id=?",String.class,operator.id())).isEqualTo("SALES_OPERATOR");
    }
    @Test void failedLoginIsGenericAuditedAndEventuallyThrottled()throws Exception{
        var target=account("SALES_OPERATOR",false);var t=token(null);long before=auditCount("LOGIN_FAILED");
        for(int i=0;i<10;i++)mvc.perform(post("/api/v1/auth/login").session(t.session()).header(t.header(),t.value()).with(r->{r.setRemoteAddr(target.id().toString());return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("email",target.email(),"password","Wrong synthetic password"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid email or password."));
        mvc.perform(post("/api/v1/auth/login").session(t.session()).header(t.header(),t.value()).contentType("application/json").content(json.writeValueAsString(Map.of("email",target.email(),"password",PASSWORD))))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900"));
        assertThat(auditCount("LOGIN_FAILED")-before).isEqualTo(10);assertThat(jdbc.queryForObject("select count(*) from operationsflow.audit_event where detail like '%password%' or detail like '%JSESSIONID%'",Long.class)).isZero();
    }
    @Test void businessAuditIsAttributedAppendOnlyAndRollsBackWithBusinessTransaction()throws Exception{
        var root=login(admin);use(root);long before=auditCount("PRODUCT_CREATED");UUID[] id={null};
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{id[0]=products.create(new ProductContracts.Create("Synthetic audited item","","AUDIT-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT))).id();throw new IllegalStateException("Synthetic failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(auditCount("PRODUCT_CREATED")).isEqualTo(before);assertThat(jdbc.queryForObject("select count(*) from operationsflow.product where id=?",Long.class,id[0])).isZero();
        var p=products.create(new ProductContracts.Create("Synthetic audited item","","AUDIT-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        assertThat(jdbc.queryForObject("select actor_id from operationsflow.audit_event where target_id=? and action='PRODUCT_CREATED'",UUID.class,p.id())).isEqualTo(admin.id());
        assertThatThrownBy(()->jdbc.update("delete from operationsflow.audit_event where target_id=?",p.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void historicalMovementActorRemainsUnattributedAndNewMovementsIdentifyUser()throws Exception{
        var root=login(admin);use(root);var p=products.create(new ProductContracts.Create("Synthetic item","","HISTORY-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));var sku=p.skus().getFirst().id();UUID warehouse=UUID.randomUUID(),legacy=UUID.randomUUID();
        jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())",warehouse,"W-"+warehouse.toString().substring(0,20).toUpperCase(Locale.ROOT));
        jdbc.update("insert into operationsflow.inventory_balance(warehouse_id,sku_id,on_hand,reserved) values (?,?,1,0)",warehouse,sku);
        jdbc.update("insert into operationsflow.inventory_movement(id,warehouse_id,sku_id,type,on_hand_delta,reserved_delta,on_hand_after,reserved_after,operation_id,reason,actor) values (?,?,?,'RECEIPT',1,0,1,0,?,'Legacy synthetic receipt','UNAUTHENTICATED')",legacy,warehouse,sku,UUID.randomUUID());
        var movement=inventory.receive(UUID.randomUUID(),new InventoryContracts.Receipt(warehouse,sku,2,"Synthetic receipt"));assertThat(movement.actor()).isEqualTo("user:"+admin.id());
        assertThat(jdbc.queryForObject("select actor from operationsflow.inventory_movement where id=?",String.class,legacy)).isEqualTo("UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("select actor_id from operationsflow.audit_event where target_id=?",UUID.class,movement.id())).isEqualTo(admin.id());
        long events=auditCount("INVENTORY_RECEIPT");assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{inventory.receive(UUID.randomUUID(),new InventoryContracts.Receipt(warehouse,sku,5,"Rolled back receipt"));throw new IllegalStateException("Rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(auditCount("INVENTORY_RECEIPT")).isEqualTo(events);assertThat(jdbc.queryForObject("select on_hand from operationsflow.inventory_balance where warehouse_id=? and sku_id=?",Long.class,warehouse,sku)).isEqualTo(3);
    }
    @Test void invalidCsrfAndDisabledOrUnknownAccountsCannotEstablishSessions()throws Exception{
        var t=token(null);
        mvc.perform(post("/api/v1/auth/login").session(t.session()).header(t.header(),"forged-token").contentType("application/json").content(json.writeValueAsString(Map.of("email",admin.email(),"password",PASSWORD))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        jdbc.update("update operationsflow.app_user set active=false where id=?",admin.id());
        for(String email:List.of(admin.email(),"unknown-"+UUID.randomUUID()+"@example.invalid")){
            mvc.perform(post("/api/v1/auth/login").session(t.session()).header(t.header(),t.value()).contentType("application/json").content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid email or password."));
        }
        assertThat(t.session().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test void operatorsCanCompleteTheirWorkflowsWithTruthfulAuditAttribution()throws Exception{
        var root=login(admin);use(root);
        var product=products.create(new ProductContracts.Create("Synthetic role workflow","","ROLE-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        var sku=product.skus().getFirst().id();UUID warehouse=UUID.randomUUID();
        jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())",warehouse,"R-"+warehouse.toString().substring(0,20).toUpperCase(Locale.ROOT));
        SecurityContextHolder.clearContext();
        var warehouseUser=account("WAREHOUSE_OPERATOR",false);var warehouseSession=login(warehouseUser);var t=token(warehouseSession);
        var receipt=mvc.perform(post("/api/v1/inventory/receipts").session(warehouseSession).header(t.header(),t.value()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(new InventoryContracts.Receipt(warehouse,sku,5,"Synthetic operator receipt")))).andExpect(status().isCreated()).andExpect(jsonPath("$.actor").value("user:"+warehouseUser.id())).andReturn();
        var movementId=UUID.fromString(json.readTree(receipt.getResponse().getContentAsString()).get("id").asText());
        assertThat(jdbc.queryForObject("select actor_id from operationsflow.audit_event where target_id=?",UUID.class,movementId)).isEqualTo(warehouseUser.id());
        var seller=account("SALES_OPERATOR",false);var sales=login(seller);
        var customer=mutate(sales,"POST","/api/v1/customers",Map.of("referenceNumber","C-"+UUID.randomUUID().toString().substring(0,20).toUpperCase(Locale.ROOT),"name","Synthetic business","addressLine","Example 1","city","Berlin","postalCode","10115","countryCode","DE"));
        assertThat(customer.getResponse().getStatus()).isEqualTo(201);var customerId=json.readTree(customer.getResponse().getContentAsString()).get("id").asText();
        t=token(sales);UUID key=UUID.randomUUID();String body=json.writeValueAsString(Map.of("customerId",customerId,"lines",List.of(Map.of("skuId",sku,"quantity",3))));
        var created=mvc.perform(post("/api/v1/orders").session(sales).header(t.header(),t.value()).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        UUID order=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/api/v1/orders").session(sales).header(t.header(),t.value()).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isCreated());
        for(int i=0;i<2;i++)assertThat(mutate(sales,"POST","/api/v1/orders/"+order+"/cancel",Map.of("reason","Synthetic cancellation")).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForList("select actor_id from operationsflow.audit_event where target_id=? order by action",UUID.class,order)).containsExactly(seller.id(),seller.id());
        assertThat(jdbc.queryForList("select actor from operationsflow.inventory_movement where sku_id=? and type in ('RESERVATION','RELEASE')",String.class,sku)).containsExactly("user:"+seller.id(),"user:"+seller.id());
        assertThat(jdbc.queryForObject("select reserved from operationsflow.inventory_balance where sku_id=?",Long.class,sku)).isZero();
    }

    @Test void bootstrapCannotCreateAnotherAdministratorAfterInitialization()throws Exception{
        assertThatThrownBy(()->identities.bootstrap("bootstrap@example.invalid",PASSWORD)).isInstanceOf(IllegalStateException.class).hasMessageContaining("empty user table");
    }
}
