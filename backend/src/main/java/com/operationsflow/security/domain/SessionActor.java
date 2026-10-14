package com.operationsflow.security.domain;
import java.io.Serializable;
import java.util.UUID;
/** Session contains identity/version only, never password hashes or credentials. */
public record SessionActor(UUID id, Role role, long securityVersion) implements Serializable {}
