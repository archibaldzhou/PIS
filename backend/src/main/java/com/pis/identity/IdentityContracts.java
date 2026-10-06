package com.pis.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class IdentityContracts {
    private IdentityContracts() { }
    public record Assignment(@NotNull UUID scopeId,@NotNull @Size(max=20) List<@NotBlank String> roles,
        @NotNull @Size(max=80) List<@NotBlank String> permissions,boolean qualificationVerified,@NotNull Instant validUntil) { }
    public record SaveUser(@Min(0) long expectedVersion,
        @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9._-]{2,63}") String username,
        @NotBlank @Size(max=255) String displayName,@NotBlank @Size(max=64) String employeeNumber,
        boolean enabled,@Size(max=72) String initialPassword,@NotNull UUID defaultScopeId,
        @NotEmpty @Size(max=10) List<@NotNull @Valid Assignment> assignments,@NotBlank @Size(max=1000) String reason) { }
    public record ResetPassword(@Min(0) long expectedVersion,@NotBlank @Size(max=72) String password,
        @NotBlank @Size(max=1000) String reason) { }
    public record ChangePassword(@NotBlank @Size(max=72) String oldPassword,@NotBlank @Size(max=72) String newPassword) { }
    public record SaveScope(@Min(0) long expectedVersion,@NotNull UUID campusId,@NotNull UUID departmentId,
        @NotNull UUID sourceId,@NotBlank @Size(max=255) String name,boolean enabled,@NotBlank @Size(max=1000) String reason) { }
    public record CreateOrganization(@NotNull OrganizationKind kind,@NotBlank @Size(max=128) String code,
        @NotBlank @Size(max=255) String name,@NotBlank @Size(max=1000) String reason) { }
    public enum OrganizationKind { CAMPUS, DEPARTMENT, SOURCE }
    public record Option(UUID id,String name) { }
    public record Scope(UUID id,UUID campusId,UUID departmentId,UUID sourceId,String name,boolean enabled,long version) { }
    public record Catalog(List<RoleCatalog.Role> roles,List<RoleCatalog.Permission> permissions,
        List<Option> campuses,List<Option> departments,List<Option> sources,List<Scope> scopes) { }
    public record User(UUID id,String username,String displayName,String employeeNumber,boolean enabled,
        boolean passwordChangeRequired,boolean administrator,UUID defaultScopeId,long version,List<Assignment> assignments) { }
    public record UserPage(List<User> items,long total,int page) { }
    public record Event(UUID id,UUID actorId,UUID targetId,String action,String reason,long version,Instant occurredAt,String changeSet) { }
    public record WorkContext(UUID defaultScopeId,String name,List<com.pis.accession.WorkflowAccess.Scope> scopes,
        Set<String> menus,boolean administration,Set<UUID> writableScopes) { }
}
