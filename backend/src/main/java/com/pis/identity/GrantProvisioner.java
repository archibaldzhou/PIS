package com.pis.identity;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Security grant administration adapter. Never edits business entities or clinical history. */
@Component
public class GrantProvisioner {
    private final JdbcTemplate jdbc;
    public GrantProvisioner(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<UUID,Set<String>> permissions(UUID user) {
        var queries=new ArrayList<String>(); var args=new ArrayList<Object>();
        for(var table:RoleCatalog.tables()) {
            String values=table.columns().entrySet().stream().map(e->"('"+e.getKey()+"',"+(e.getValue().equals("access")?"true":"g."+e.getValue())+")").collect(java.util.stream.Collectors.joining(","));
            queries.add("SELECT g.scope_id,p.code FROM "+table.table()+" g JOIN workflow_grant w ON w.user_id=g.user_id AND w.scope_id=g.scope_id JOIN workflow_scope s ON s.id=g.scope_id CROSS JOIN LATERAL (VALUES "+values+") p(code,allowed) WHERE g.user_id=? AND p.allowed AND s.enabled AND w.can_read AND w.revoked_at IS NULL AND w.valid_from<=statement_timestamp() AND (w.valid_until IS NULL OR w.valid_until>statement_timestamp()) AND g.revoked_at IS NULL AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())"+(table.validFrom()?" AND g.valid_from<=statement_timestamp()":""));
            args.add(user);
        }
        queries.add("SELECT a.scope_id,'AUDIT' FROM identity_scope_assignment a JOIN workflow_grant w ON w.user_id=a.user_id AND w.scope_id=a.scope_id JOIN workflow_scope s ON s.id=a.scope_id WHERE a.user_id=? AND 'AUDIT'=ANY(a.permissions) AND a.valid_until>statement_timestamp() AND s.enabled AND w.can_read AND w.revoked_at IS NULL AND w.valid_from<=statement_timestamp() AND (w.valid_until IS NULL OR w.valid_until>statement_timestamp())"); args.add(user);
        var result=new LinkedHashMap<UUID,Set<String>>();
        jdbc.query(String.join(" UNION ALL ",queries),r->{result.computeIfAbsent(r.getObject(1,UUID.class),unused->new LinkedHashSet<>()).add(r.getString(2));},args.toArray());
        return result;
    }
    public void replace(UUID user,UUID hospital,List<IdentityContracts.Assignment> assignments) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Grant changes require a transaction");
        // Account update is locked first. Existing business commands holding its SHARE lock finish before revocation.
        for(var table:RoleCatalog.tables()) jdbc.update("UPDATE "+table.table()+" SET revoked_at=statement_timestamp() WHERE user_id=? AND scope_id IN (SELECT id FROM workflow_scope WHERE hospital_id=?) AND revoked_at IS NULL",user,hospital);
        jdbc.update("DELETE FROM identity_scope_assignment WHERE user_id=? AND hospital_id=?",user,hospital);
        for(var assignment:assignments.stream().sorted(Comparator.comparing(IdentityContracts.Assignment::scopeId)).toList()) {
            var permissions=new HashSet<>(assignment.permissions());
            for(var table:RoleCatalog.tables()) {
                if(table.columns().keySet().stream().noneMatch(permissions::contains))continue;
                var columns=new ArrayList<>(List.of("user_id","scope_id","valid_until","revoked_at"));
                var args=new ArrayList<Object>(Arrays.asList(user,assignment.scopeId(),assignment.validUntil().atOffset(ZoneOffset.UTC),null));
                if(table.qualification()!=null){columns.add("qualification");args.add(table.qualification());}
                if(table.validFrom()){columns.add("valid_from");args.add(OffsetDateTime.now(ZoneOffset.UTC));}
                for(var entry:table.columns().entrySet())if(!entry.getValue().equals("access")){columns.add(entry.getValue());args.add(permissions.contains(entry.getKey()));}
                String update=columns.stream().filter(c->!c.equals("user_id")&&!c.equals("scope_id")).map(c->c+"=excluded."+c).collect(java.util.stream.Collectors.joining(","));
                if(table.table().equals("diagnosis_grant"))update+=",version=diagnosis_grant.version+1";
                jdbc.update("INSERT INTO "+table.table()+" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+") ON CONFLICT (user_id,scope_id) DO UPDATE SET "+update,args.toArray());
            }
            jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO identity_scope_assignment(user_id,hospital_id,scope_id,roles,permissions,qualification_verified,valid_until) VALUES(?,?,?,?,?,?,?)");
                statement.setObject(1,user);statement.setObject(2,hospital);statement.setObject(3,assignment.scopeId());
                statement.setArray(4,connection.createArrayOf("text",assignment.roles().toArray()));statement.setArray(5,connection.createArrayOf("text",assignment.permissions().toArray()));
                statement.setBoolean(6,assignment.qualificationVerified());statement.setObject(7,assignment.validUntil().atOffset(ZoneOffset.UTC));return statement;});
        }
    }
    static List<String> strings(Array array) throws java.sql.SQLException { return List.of((String[])array.getArray()); }
}
