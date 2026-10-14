package com.operationsflow.security.api;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.security.application.IdentityService;
import com.operationsflow.security.api.IdentityContracts.*;
import com.operationsflow.shared.api.PageResponse;
@RestController
@RequestMapping("/api/v1/users")
@Tag(name="User administration",description="ADMIN only. All changes revoke existing sessions. Resets require a change at next login. Final active administrator is protected.")
public class UserController {
    private final IdentityService users;public UserController(IdentityService users){this.users=users;}
    @GetMapping public PageResponse<View> list(@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return users.list(query,page,size);}
    @PostMapping public ResponseEntity<View> create(@Valid @RequestBody Create body){var user=users.create(body);return ResponseEntity.created(java.net.URI.create("/api/v1/users/"+user.id())).body(user);}
    @PutMapping("/{id}") public View update(@PathVariable UUID id,@Valid @RequestBody Update body){return users.update(id,body);}
    @PostMapping("/{id}/reset-password") public ResponseEntity<Void> reset(@PathVariable UUID id,@Valid @RequestBody Reset body){users.reset(id,body);return ResponseEntity.noContent().build();}
}
