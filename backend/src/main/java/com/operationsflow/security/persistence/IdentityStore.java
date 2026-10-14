package com.operationsflow.security.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.security.domain.Role;
import com.operationsflow.shared.domain.BusinessException;

@Repository
public class IdentityStore {
    private final JdbcTemplate jdbc;
    public IdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Account(UUID id, String email, String displayName, String passwordHash, Role role, boolean active,
                          boolean mustChangePassword, long version, long securityVersion) {
        @Override public String toString() { return "Account[redacted]"; }
    }
    private static Account map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Account(r.getObject("id",UUID.class),r.getString("email"),r.getString("display_name"),r.getString("password_hash"),Role.valueOf(r.getString("role")),r.getBoolean("active"),r.getBoolean("must_change_password"),r.getLong("version"),r.getLong("security_version"));
    }
    public Optional<Account> byEmail(String email) { return jdbc.query("select * from operationsflow.app_user where email = ?",IdentityStore::map,email).stream().findFirst(); }
    public Optional<Account> byId(UUID id) { return jdbc.query("select * from operationsflow.app_user where id = ?",IdentityStore::map,id).stream().findFirst(); }
    public Account lock(UUID id) {
        return jdbc.query("select * from operationsflow.app_user where id = ? for update",IdentityStore::map,id).stream().findFirst().orElseThrow(() -> new BusinessException("NOT_FOUND","User not found."));
    }
    public void guard() { jdbc.execute("set local lock_timeout = '3s'"); jdbc.queryForObject("select id from operationsflow.identity_guard where id = 1 for update",Integer.class); }
    public long count() { return jdbc.queryForObject("select count(*) from operationsflow.app_user",Long.class); }
    public long activeAdmins() { return jdbc.queryForObject("select count(*) from operationsflow.app_user where active and role = 'ADMIN'",Long.class); }
    public UUID create(String email,String name,String hash,Role role,boolean mustChange) {
        UUID id=UUID.randomUUID(); jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) values (?,?,?,?,?,?)",id,email,name,hash,role.name(),mustChange); return id;
    }
    public void update(UUID id,String email,String name,Role role,boolean active) {
        jdbc.update("update operationsflow.app_user set email=?,display_name=?,role=?,active=?,version=version+1,security_version=security_version+1,updated_at=clock_timestamp() where id=?",email,name,role.name(),active,id);
    }
    public void password(UUID id,String hash,boolean mustChange) {
        jdbc.update("update operationsflow.app_user set password_hash=?,must_change_password=?,version=version+1,security_version=security_version+1,updated_at=clock_timestamp() where id=?",hash,mustChange,id);
    }
    public List<Account> list(String term,int page,int size) { return jdbc.query("select * from operationsflow.app_user where strpos(email,?) > 0 or strpos(lower(display_name),?) > 0 order by email,id limit ? offset ?",IdentityStore::map,term,term,size,(long)page*size); }
    public long total(String term) { return jdbc.queryForObject("select count(*) from operationsflow.app_user where strpos(email,?) > 0 or strpos(lower(display_name),?) > 0",Long.class,term,term); }
}
