package com.operationsflow.shared.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "operationsflow.ui.enabled", havingValue = "true")
public class BundledUiController {
    // Explicit UI routes only: never forward an unknown API/Actuator path to HTML.
    @GetMapping({"/", "/dashboard", "/products", "/warehouses", "/inventory",
        "/customers", "/orders", "/users", "/fulfillment", "/reports"})
    public ResponseEntity<Resource> index() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.TEXT_HTML).body(new ClassPathResource("static/index.html"));
    }
}
