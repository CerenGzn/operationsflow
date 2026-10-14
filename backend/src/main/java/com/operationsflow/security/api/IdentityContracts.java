package com.operationsflow.security.api;

import java.util.*;
import jakarta.validation.constraints.*;
import com.operationsflow.security.domain.Role;
import com.operationsflow.security.persistence.IdentityStore.Account;

public final class IdentityContracts {
    private IdentityContracts() {}
    public record Login(@NotBlank @Size(max=254) String email,@NotBlank @Size(max=72) String password) {
        @Override public String toString(){return "Login[redacted]";}
    }
    public record Create(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=100) String displayName,
                         @NotNull Role role,@NotBlank @Size(min=12,max=72) String password) {
        @Override public String toString(){return "CreateUser[redacted]";}
    }
    public record Update(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=100) String displayName,
                         @NotNull Role role,@NotNull Boolean active,@NotNull @PositiveOrZero Long version) {}
    public record Reset(@NotBlank @Size(min=12,max=72) String password,@NotNull @PositiveOrZero Long version) {
        @Override public String toString(){return "ResetPassword[redacted]";}
    }
    public record Change(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(min=12,max=72) String newPassword) {
        @Override public String toString(){return "ChangePassword[redacted]";}
    }
    public record View(UUID id,String email,String displayName,Role role,boolean active,boolean mustChangePassword,long version) {
        public static View from(Account a){return new View(a.id(),a.email(),a.displayName(),a.role(),a.active(),a.mustChangePassword(),a.version());}
    }
}
