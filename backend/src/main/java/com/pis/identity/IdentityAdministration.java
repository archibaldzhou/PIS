package com.pis.identity;

import com.pis.accession.WorkflowAccess;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.pis.identity.IdentityContracts.*;

@Service
public class IdentityAdministration {
    private final JdbcTemplate jdbc; private final WorkflowAccess access; private final IdempotentCommands commands;
    private final GrantProvisioner grants; private final PasswordEncoder passwords; private final Validator validator;
    public IdentityAdministration(JdbcTemplate jdbc,WorkflowAccess access,IdempotentCommands commands,GrantProvisioner grants,PasswordEncoder passwords,Validator validator) {
        this.jdbc=jdbc;this.access=access;this.commands=commands;this.grants=grants;this.passwords=passwords;this.validator=validator;
    }
    private void validate(Object body) { var failures=validator.validate(body);if(!failures.isEmpty())throw new jakarta.validation.ConstraintViolationException(failures); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Administration change requires review"); }
    private static ApiException bad(String code) { return new ApiException(HttpStatus.BAD_REQUEST,code,"Invalid administration input"); }
    static void password(String value) {
        if(value==null||value.getBytes(StandardCharsets.UTF_8).length<16||value.getBytes(StandardCharsets.UTF_8).length>72||value.isBlank())throw bad("PASSWORD_POLICY");
    }
    private boolean administrator(UUID hospital,UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity_admin_grant WHERE hospital_id=? AND user_id=? AND revoked_at IS NULL AND valid_until>statement_timestamp())",Boolean.class,hospital,user));
    }
    private CurrentActor.Actor requireAdmin(UUID hospital) {
        var actor=access.actor(); if(!administrator(hospital,actor.id()))throw new AccessDeniedException("Administration unavailable");
        if(TransactionSynchronizationManager.isActualTransactionActive()) {
            // Every management write uses the same hospital -> actor -> grant -> target order.
            jdbc.queryForList("SELECT id FROM hospital WHERE id=? FOR UPDATE",hospital);
            jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",actor.id());
            jdbc.queryForList("SELECT user_id FROM identity_admin_grant WHERE user_id=? AND hospital_id=? FOR SHARE",actor.id(),hospital);
            actor=access.actor(); if(!administrator(hospital,actor.id()))throw new AccessDeniedException("Administration unavailable");
        }
        return actor;
    }
    public record Hospital(UUID id,String name,boolean manage) { }
    public List<Hospital> hospitals() {
        var actor=access.actor();
        return jdbc.query("""
            SELECT h.id,h.name,EXISTS(SELECT 1 FROM identity_admin_grant g WHERE g.hospital_id=h.id
                AND g.user_id=? AND g.revoked_at IS NULL AND g.valid_until>statement_timestamp()) AS manage
            FROM hospital h WHERE EXISTS(SELECT 1 FROM identity_admin_grant g WHERE g.hospital_id=h.id
                AND g.user_id=? AND g.revoked_at IS NULL AND g.valid_until>statement_timestamp())
            OR EXISTS(SELECT 1 FROM identity_scope_assignment a JOIN workflow_scope s ON s.id=a.scope_id
                JOIN workflow_grant w ON w.user_id=a.user_id AND w.scope_id=a.scope_id
                WHERE a.user_id=? AND s.hospital_id=h.id AND s.enabled AND 'AUDIT'=ANY(a.permissions)
                AND a.valid_until>statement_timestamp() AND w.can_read AND w.revoked_at IS NULL
                AND w.valid_from<=statement_timestamp() AND (w.valid_until IS NULL OR w.valid_until>statement_timestamp()))
            ORDER BY h.id LIMIT 100
            """,(r,i)->new Hospital(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3)),actor.id(),actor.id(),actor.id());
    }
    @Transactional(timeout=10) public Catalog catalog(UUID hospital) {
        requireAdmin(hospital);
        return new Catalog(RoleCatalog.roles(),RoleCatalog.permissions(),options("campus",hospital),options("department",hospital),options("source_system",hospital),
            jdbc.query("SELECT * FROM workflow_scope WHERE hospital_id=? ORDER BY name,id LIMIT 200",(r,i)->new Scope(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),r.getObject("department_id",UUID.class),r.getObject("source_system_id",UUID.class),r.getString("name"),r.getBoolean("enabled"),r.getLong("admin_version")),hospital));
    }
    private List<Option> options(String table,UUID hospital) { return jdbc.query("SELECT id,name FROM "+table+" WHERE hospital_id=? ORDER BY name,id LIMIT 200",(r,i)->new Option(r.getObject(1,UUID.class),r.getString(2)),hospital); }
    private static final String USERS="SELECT u.id,u.username,u.display_name,u.enabled,u.password_change_required,a.* FROM identity_account a JOIN app_user u ON u.id=a.user_id WHERE a.hospital_id=?";
    private User map(java.sql.ResultSet r,int i) throws java.sql.SQLException {
        return new User(r.getObject("user_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getString("employee_number"),r.getBoolean("enabled"),r.getBoolean("password_change_required"),false,r.getObject("default_scope_id",UUID.class),r.getLong("version"),List.of());
    }
    @Transactional(timeout=10) public UserPage users(UUID hospital,String search,int page) {
        requireAdmin(hospital);if(page<1||page>10000||search.length()>128)throw bad("ADMIN_QUERY_INVALID");
        String where=" AND (?='' OR u.username ILIKE ? OR u.display_name ILIKE ? OR a.employee_number ILIKE ?)";String q="%"+search+"%";
        long total=jdbc.queryForObject("SELECT count(*) FROM identity_account a JOIN app_user u ON u.id=a.user_id WHERE a.hospital_id=?"+where,Long.class,hospital,search,q,q,q);
        return new UserPage(jdbc.query(USERS+where+" ORDER BY u.username,u.id LIMIT 20 OFFSET ?",this::map,hospital,search,q,q,q,(page-1)*20),total,page);
    }
    private void target(UUID hospital,UUID user,boolean lock) {
        if(lock)jdbc.queryForList("SELECT u.id FROM app_user u JOIN identity_account a ON a.user_id=u.id WHERE a.hospital_id=? AND u.id=? FOR UPDATE OF u,a",hospital,user);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity_account a JOIN app_user u ON u.id=a.user_id WHERE a.hospital_id=? AND a.user_id=? AND u.synthetic_only)",Boolean.class,hospital,user)))throw new AccessDeniedException("Managed account unavailable");
    }
    @Transactional(timeout=10) public User user(UUID hospital,UUID id) {
        requireAdmin(hospital);target(hospital,id,false);var u=jdbc.query(USERS+" AND a.user_id=?",this::map,hospital,id).getFirst();
        var assignments=jdbc.query("SELECT * FROM identity_scope_assignment WHERE user_id=? AND hospital_id=? ORDER BY scope_id LIMIT 10",(r,i)->new Assignment(r.getObject("scope_id",UUID.class),GrantProvisioner.strings(r.getArray("roles")),GrantProvisioner.strings(r.getArray("permissions")),r.getBoolean("qualification_verified"),r.getObject("valid_until",OffsetDateTime.class).toInstant()),id,hospital);
        return new User(u.id(),u.username(),u.displayName(),u.employeeNumber(),u.enabled(),u.passwordChangeRequired(),administrator(hospital,id),u.defaultScopeId(),u.version(),assignments);
    }
    private void validateAssignments(UUID hospital,SaveUser body) {
        if(!body.displayName().equals(body.displayName().strip())||!body.employeeNumber().equals(body.employeeNumber().strip()))throw bad("ADMIN_INPUT_INVALID");
        var ids=new HashSet<UUID>();var roles=RoleCatalog.roles().stream().filter(RoleCatalog.Role::available).map(RoleCatalog.Role::code).toList();
        for(var a:body.assignments()) {
            if(!ids.add(a.scopeId())||new HashSet<>(a.permissions()).size()!=a.permissions().size()||new HashSet<>(a.roles()).size()!=a.roles().size()||!roles.containsAll(a.roles())||a.permissions().stream().anyMatch(p->!RoleCatalog.known(p)))throw bad("ADMIN_PERMISSION_INVALID");
            if(!a.permissions().contains("READ")||a.validUntil().isBefore(Instant.now().plusSeconds(60)))throw bad("ADMIN_PERMISSION_INVALID");
            if(a.permissions().stream().anyMatch(RoleCatalog::professional)&&!a.qualificationVerified())throw bad("QUALIFICATION_REQUIRED");
            if((a.permissions().contains("SIGN")||a.permissions().contains("REVIEW"))&&!a.permissions().contains("DIAGNOSE"))throw bad("QUALIFICATION_REQUIRED");
            if(a.permissions().contains("REPRINT")&&!a.permissions().contains("PRINT")||a.permissions().contains("HANDOFF")&&!a.permissions().contains("PROCESS"))throw bad("ADMIN_PERMISSION_INVALID");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow_scope WHERE id=? AND hospital_id=? AND enabled)",Boolean.class,a.scopeId(),hospital)))throw new AccessDeniedException("Scope unavailable");
        }
        if(!ids.contains(body.defaultScopeId()))throw bad("DEFAULT_SCOPE_REQUIRED");
    }
    private void event(UUID hospital,UUID actor,UUID target,String action,String reason,long version) {
        jdbc.update("""
            INSERT INTO identity_change_event(id,hospital_id,actor_id,target_id,action,reason,result_version,change_set)
            VALUES(?,?,?,?,?,?,?,jsonb_build_object('previous',coalesce((SELECT change_set->'current' FROM identity_change_event
                WHERE hospital_id=? AND target_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1),'{}'::jsonb),
                'current',coalesce((SELECT jsonb_build_object('enabled',u.enabled,'defaultScopeId',a.default_scope_id,
                    'assignments',(SELECT jsonb_agg(jsonb_build_object('scopeId',g.scope_id,'roles',g.roles,'permissions',g.permissions,
                        'qualificationVerified',g.qualification_verified,'validUntil',g.valid_until) ORDER BY g.scope_id)
                        FROM identity_scope_assignment g WHERE g.user_id=a.user_id))
                    FROM identity_account a JOIN app_user u ON u.id=a.user_id WHERE a.user_id=? AND a.hospital_id=?),
                    (SELECT jsonb_build_object('enabled',s.enabled,'name',s.name,'version',s.admin_version) FROM workflow_scope s WHERE s.id=? AND s.hospital_id=?),'{}'::jsonb)))
            """,UUID.randomUUID(),hospital,actor,target,action,reason,version,hospital,target,target,hospital,target,hospital);
    }
    private interface Mutation { IdempotentCommands.Mutation run(CurrentActor.Actor actor); }
    private IdempotentCommands.Result command(UUID hospital,String operation,String key,Object body,UUID target,Mutation mutation) {
        return commands.execute(hospital,operation,key,body,new IdempotentCommands.Work(){
            public void authorize(CurrentActor.Actor actor){requireAdmin(hospital);if(target!=null)target(hospital,target,false);}
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){requireAdmin(hospital);}
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){return mutation.run(actor);}
        });
    }
    public IdempotentCommands.Result saveUser(UUID hospital,UUID id,SaveUser body,String key) {
        validate(body);requireAdmin(hospital);validateAssignments(hospital,body);
        if(id!=null&&id.equals(access.actor().id()))throw conflict("ADMIN_SELF_CHANGE");
        if(id==null)password(body.initialPassword());else if(body.initialPassword()!=null&&!body.initialPassword().isEmpty())throw bad("USE_PASSWORD_RESET");
        String hash=id==null?passwords.encode(body.initialPassword()):null;
        try {return command(hospital,"IDENTITY_USER_SAVE_V1",key,Map.of("id",id==null?"NEW":id,"input",body),id,actor->{
            validateAssignments(hospital,body);UUID saved=id==null?UUID.randomUUID():id;
            if(id==null) {
                jdbc.update("INSERT INTO app_user(id,username,display_name,password_hash,enabled,synthetic_only,password_change_required) VALUES(?,?,?,?,?,true,true)",saved,body.username(),body.displayName(),hash,body.enabled());
                jdbc.update("INSERT INTO identity_account(user_id,hospital_id,employee_number) VALUES(?,?,?)",saved,hospital,body.employeeNumber());
            } else {
                target(hospital,id,true);
                if(!body.username().equals(jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,id)))throw bad("USERNAME_IMMUTABLE");
                if(jdbc.update("UPDATE identity_account SET employee_number=?,version=version+1 WHERE user_id=? AND hospital_id=? AND version=?",body.employeeNumber(),id,hospital,body.expectedVersion())!=1)throw conflict("ADMIN_VERSION_CONFLICT");
                jdbc.update("UPDATE app_user SET display_name=?,enabled=?,auth_version=auth_version+1,updated_at=statement_timestamp() WHERE id=?",body.displayName(),body.enabled(),id);
            }
            grants.replace(saved,hospital,body.assignments());
            jdbc.update("UPDATE identity_account SET default_scope_id=? WHERE user_id=? AND hospital_id=?",body.defaultScopeId(),saved,hospital);
            var admin=body.assignments().stream().filter(a->a.roles().contains("ADMIN")).map(Assignment::validUntil).max(Comparator.naturalOrder());
            if(admin.isPresent())jdbc.update("INSERT INTO identity_admin_grant(user_id,hospital_id,valid_until) VALUES(?,?,?) ON CONFLICT(user_id,hospital_id) DO UPDATE SET valid_until=excluded.valid_until,revoked_at=NULL",saved,hospital,admin.get().atOffset(ZoneOffset.UTC));
            else jdbc.update("UPDATE identity_admin_grant SET revoked_at=statement_timestamp() WHERE user_id=? AND hospital_id=?",saved,hospital);
            long version=id==null?0:body.expectedVersion()+1;event(hospital,actor.id(),saved,"USER_SAVE",body.reason(),version);
            return new IdempotentCommands.Mutation(new CommandReceipt(id==null?201:200,"IDENTITY_USER",saved,version),id==null?null:body.expectedVersion());
        });}catch(DuplicateKeyException e){throw conflict("ACCOUNT_ALREADY_EXISTS");}
    }
    public IdempotentCommands.Result reset(UUID hospital,UUID id,ResetPassword body,String key) {
        validate(body);requireAdmin(hospital);password(body.password());if(id.equals(access.actor().id()))throw conflict("ADMIN_SELF_CHANGE");
        String hash=passwords.encode(body.password());
        return command(hospital,"IDENTITY_PASSWORD_RESET_V1",key,Map.of("id",id,"input",body),id,actor->{target(hospital,id,true);
            if(jdbc.update("UPDATE identity_account SET version=version+1 WHERE user_id=? AND hospital_id=? AND version=?",id,hospital,body.expectedVersion())!=1)throw conflict("ADMIN_VERSION_CONFLICT");
            jdbc.update("UPDATE app_user SET password_hash=?,password_change_required=true,auth_version=auth_version+1 WHERE id=?",hash,id);
            event(hospital,actor.id(),id,"PASSWORD_RESET",body.reason(),body.expectedVersion()+1);
            return new IdempotentCommands.Mutation(new CommandReceipt(200,"IDENTITY_USER",id,body.expectedVersion()+1),body.expectedVersion());});
    }
    public IdempotentCommands.Result saveScope(UUID hospital,UUID id,SaveScope body,String key) {
        validate(body);requireAdmin(hospital);
        return command(hospital,"IDENTITY_SCOPE_SAVE_V1",key,Map.of("id",id==null?"NEW":id,"input",body),null,actor->{
            for(var entry:Map.of("campus",body.campusId(),"department",body.departmentId(),"source_system",body.sourceId()).entrySet())
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+entry.getKey()+" WHERE hospital_id=? AND id=?)",Boolean.class,hospital,entry.getValue())))throw new AccessDeniedException("Organization unavailable");
            UUID saved=id==null?UUID.randomUUID():id;
            if(id==null) {
                jdbc.update("INSERT INTO department_campus(hospital_id,campus_id,department_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",hospital,body.campusId(),body.departmentId());
                try {jdbc.update("INSERT INTO workflow_scope(id,hospital_id,campus_id,department_id,source_system_id,name,enabled) VALUES(?,?,?,?,?,?,?)",saved,hospital,body.campusId(),body.departmentId(),body.sourceId(),body.name().strip(),body.enabled());}
                catch(DuplicateKeyException e){throw conflict("SCOPE_ALREADY_EXISTS");}
            } else if(jdbc.update("UPDATE workflow_scope SET name=?,enabled=?,admin_version=admin_version+1 WHERE id=? AND hospital_id=? AND campus_id=? AND department_id=? AND source_system_id=? AND admin_version=?",body.name().strip(),body.enabled(),id,hospital,body.campusId(),body.departmentId(),body.sourceId(),body.expectedVersion())!=1)throw conflict("ADMIN_VERSION_CONFLICT");
            long version=id==null?0:body.expectedVersion()+1;event(hospital,actor.id(),saved,"SCOPE_SAVE",body.reason(),version);
            return new IdempotentCommands.Mutation(new CommandReceipt(id==null?201:200,"IDENTITY_SCOPE",saved,version),id==null?null:body.expectedVersion());});
    }
    public IdempotentCommands.Result organization(UUID hospital,CreateOrganization body,String key) {
        validate(body);requireAdmin(hospital);return command(hospital,"IDENTITY_ORGANIZATION_CREATE_V1",key,body,null,actor->{
            String table=switch(body.kind()){case CAMPUS->"campus";case DEPARTMENT->"department";case SOURCE->"source_system";};UUID id=UUID.randomUUID();
            try {jdbc.update("INSERT INTO "+table+"(id,hospital_id,code,name) VALUES(?,?,?,?)",id,hospital,body.code().strip(),body.name().strip());}catch(DuplicateKeyException e){throw conflict("ORGANIZATION_ALREADY_EXISTS");}
            event(hospital,actor.id(),id,"ORGANIZATION_CREATE",body.reason(),0);return new IdempotentCommands.Mutation(new CommandReceipt(201,"IDENTITY_ORGANIZATION",id,0),null);});
    }
    @Transactional(timeout=10) public List<Event> events(UUID hospital,int page) {
        if(page<1||page>10000)throw bad("ADMIN_QUERY_INVALID");
        if(hospitals().stream().noneMatch(h->h.id().equals(hospital)))throw new AccessDeniedException("Audit unavailable");
        return jdbc.query("SELECT * FROM (SELECT id,actor_id,target_id,action,reason,result_version,occurred_at,change_set::text FROM identity_change_event WHERE hospital_id=? UNION ALL SELECT id,actor_id,hospital_id,'REJECTED',error_code,0,occurred_at,jsonb_build_object('traceId',trace_id)::text FROM identity_denial_event WHERE hospital_id=?) e ORDER BY occurred_at DESC,id DESC LIMIT 20 OFFSET ?",(r,i)->new Event(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getObject("target_id",UUID.class),r.getString("action"),r.getString("reason"),r.getLong("result_version"),r.getObject("occurred_at",OffsetDateTime.class).toInstant(),r.getString("change_set")),hospital,hospital,(page-1)*20);
    }
    @Transactional(timeout=10) public WorkContext context() {
        var actor=access.actor();var permissions=grants.permissions(actor.id());
        var scopes=jdbc.query("SELECT s.* FROM workflow_scope s JOIN workflow_grant g ON g.scope_id=s.id WHERE g.user_id=? AND s.enabled AND g.can_read AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp()) ORDER BY s.id LIMIT 100",(r,i)->new WorkflowAccess.Scope(r.getObject("id",UUID.class),r.getObject("hospital_id",UUID.class),r.getObject("campus_id",UUID.class),r.getObject("department_id",UUID.class),r.getObject("source_system_id",UUID.class),r.getString("name")),actor.id());
        var configured=jdbc.query("SELECT default_scope_id FROM identity_account WHERE user_id=?",(r,i)->r.getObject(1,UUID.class),actor.id());
        UUID selected=configured.isEmpty()?(scopes.size()==1?scopes.getFirst().id():null):configured.getFirst();
        if(selected!=null&&scopes.stream().noneMatch(s->s.id().equals(selected)))return new WorkContext(null,"默认工作范围已停用或授权已失效，请联系管理员",scopes,Set.of(),!hospitals().isEmpty(),Set.of());
        var codes=new LinkedHashSet<String>();permissions.values().forEach(codes::addAll);
        String name=selected==null?"请联系管理员设置默认工作范围":scopes.stream().filter(s->s.id().equals(selected)).findFirst().orElseThrow().name();
        var writable=permissions.entrySet().stream().filter(e->e.getValue().contains("WRITE")).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        return new WorkContext(selected,name,scopes,RoleCatalog.menus(codes),!hospitals().isEmpty(),writable);
    }
}
