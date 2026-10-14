package com.operationsflow.shared.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    @Operation(summary = "Identify the OperationsFlow API", description = "Connectivity endpoint; use Actuator readiness for database health.")
    @GetMapping
    public SystemInfo info() {
        return new SystemInfo("OperationsFlow", "v1");
    }

    public record SystemInfo(String application, String apiVersion) {}
}
