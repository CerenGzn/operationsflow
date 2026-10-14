package com.operationsflow;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogWarehouseIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String code() { return "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private JsonNode createProduct(String name, String sku) throws Exception {
        return body(mvc.perform(post("/api/v1/products").contentType("application/json")
            .content(json.writeValueAsString(Map.of("name", name, "description", "Synthetic test product", "skuCode", sku))))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")));
    }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    private String warehouse(String code, String name) throws Exception {
        return json.writeValueAsString(Map.of("code", code, "name", name, "addressLine", "Example street 1", "city", "Berlin", "postalCode", "10115", "countryCode", "DE"));
    }

    @Test void productLifecyclePreservesSkusAndRejectsStaleWrites() throws Exception {
        var p = createProduct("Test chair", code()); var path = "/api/v1/products/" + p.get("id").asText();
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.skus.length()").value(1));
        var updated = body(mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Test chair edited\",\"description\":\"\",\"version\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)));
        mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Stale\",\"description\":\"\",\"version\":0}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        var secondCode = code();
        updated = body(mvc.perform(post(path + "/skus").contentType("application/json")
            .content(json.writeValueAsString(Map.of("code", secondCode, "version", updated.get("version").asLong()))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.skus.length()").value(2)));
        mvc.perform(get("/api/v1/products").param("query", secondCode.toLowerCase(Locale.ROOT)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        var deactivate = json.writeValueAsString(Map.of("version", updated.get("version").asLong()));
        mvc.perform(post(path + "/deactivate").contentType("application/json").content(deactivate)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(post(path + "/deactivate").contentType("application/json").content(deactivate)).andExpect(status().isOk());
        mvc.perform(post(path + "/skus").contentType("application/json").content(json.writeValueAsString(Map.of("code", code(), "version", updated.get("version").asLong() + 1))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INACTIVE_RECORD"));
        mvc.perform(get("/api/v1/products").param("query", secondCode).param("status", "ACTIVE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        assertThatThrownBy(() -> jdbc.update("update operationsflow.sku set code = ? where product_id = ?", code(), UUID.fromString(p.get("id").asText())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void duplicateSkuRollsBackEntireProductAndCannotBeChangedViaPut() throws Exception {
        var sku = code(); var product = createProduct("Original", sku);
        var count = jdbc.queryForObject("select count(*) from operationsflow.product", Long.class);
        mvc.perform(post("/api/v1/products").contentType("application/json")
            .content(json.writeValueAsString(Map.of("name", "Duplicate", "description", "", "skuCode", sku))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDENTIFIER_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.product", Long.class)).isEqualTo(count);
        mvc.perform(put("/api/v1/products/" + product.get("id").asText()).contentType("application/json")
            .content("{\"name\":\"Changed\",\"description\":\"\",\"version\":0,\"skuCode\":\"OTHER\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void validationNotFoundAndBoundedLiteralSearch() throws Exception {
        mvc.perform(post("/api/v1/products").contentType("application/json").content("{\"name\":\"  \",\"description\":\"\",\"skuCode\":\"lowercase\"}"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get("/api/v1/products/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("sort", "description")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("status", "DELETED")).andExpect(status().isBadRequest());
        var unique = code(); createProduct(unique + " B", code()); createProduct(unique + " A", code());
        mvc.perform(get("/api/v1/products").param("query", unique).param("size", "1").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content[0].name").value(unique + " B"));
        mvc.perform(get("/api/v1/products").param("query", "%"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void warehousesEnforceUniquePermanentCodesAndLifecycle() throws Exception {
        var code = code();
        var w = body(mvc.perform(post("/api/v1/warehouses").contentType("application/json").content(warehouse(code, "Synthetic Berlin")))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")));
        var path = "/api/v1/warehouses/" + w.get("id").asText();
        mvc.perform(post("/api/v1/warehouses").contentType("application/json").content(warehouse(code, "Duplicate"))).andExpect(status().isConflict());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.city").value("Berlin"));
        ObjectNode edit = (ObjectNode) json.readTree(warehouse(code, "Updated warehouse")); edit.remove("code"); edit.put("version", 0);
        mvc.perform(put(path).contentType("application/json").content(edit.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).contentType("application/json").content(edit.toString())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/warehouses").param("query", code.toLowerCase(Locale.ROOT)).param("status", "ACTIVE").param("sort", "code").param("direction", "desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post(path + "/deactivate").contentType("application/json").content("{\"version\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(post(path + "/deactivate").contentType("application/json").content("{\"version\":1}")).andExpect(status().isOk());
        edit.put("version", 2);
        mvc.perform(put(path).contentType("application/json").content(edit.toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INACTIVE_RECORD"));
        assertThatThrownBy(() -> jdbc.update("update operationsflow.warehouse set code = ? where id = ?", code(), UUID.fromString(w.get("id").asText())))
            .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(post("/api/v1/warehouses").contentType("application/json").content("{\"code\":\"bad\",\"name\":\" \",\"countryCode\":\"de\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get("/api/v1/warehouses/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void concurrentCreationCannotDuplicateSku() throws Exception {
        var start = new CountDownLatch(1); var sku = code();
        var payload = json.writeValueAsString(Map.of("name", "Concurrent synthetic", "description", "", "skuCode", sku));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> { start.await(); return mvc.perform(post("/api/v1/products").contentType("application/json").content(payload)).andReturn().getResponse().getStatus(); };
            var first = executor.submit(call); var second = executor.submit(call); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.sku where code = ?", Long.class, sku)).isEqualTo(1L);
    }
}
