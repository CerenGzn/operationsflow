package com.operationsflow.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
class SystemControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void exposesVersionedConnectivityContract() throws Exception {
        mvc.perform(get("/api/v1/system"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.application").value("OperationsFlow"))
            .andExpect(jsonPath("$.apiVersion").value("v1"));
    }
}
