package com.pis.operations;

import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.audit.AuditRecorder;
import com.pis.storage.StorageProvider;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A case-scoped development snapshot, never a global management or export endpoint. */
@Service
public class OperationsService {
    private final RequestService requests;
    private final WorkflowAccess access;
    private final JdbcTemplate jdbc;
    private final AuditRecorder audit;
    private final StorageProvider storage;
    public OperationsService(RequestService requests, WorkflowAccess access, JdbcTemplate jdbc,
                             AuditRecorder audit, StorageProvider storage) {
        this.requests=requests; this.access=access; this.jdbc=jdbc; this.audit=audit; this.storage=storage;
    }
    public record Event(UUID id, Instant at, String operation, String resourceType, long version, UUID traceId) { }
    public record Snapshot(UUID requestId, Instant observedAt, long pending, long failed, long readyObjects,
                           String capacityState, Long volumeTotal, Long volumeUsable, String encryption,
                           String offsiteBackup, String recovery, List<Event> events) { }
    @Transactional(timeout=5, isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(UUID requestId) {
        var scope=requests.authorizedScope(requestId);
        var actor=access.actor();
        var granted=jdbc.queryForList("""
            SELECT user_id FROM operations_grant WHERE user_id=? AND scope_id=?
              AND qualification='SYN-OPS-1' AND revoked_at IS NULL
              AND valid_until>statement_timestamp() FOR SHARE
            """, actor.id(),scope.id());
        if(granted.isEmpty()) throw new AccessDeniedException("Operations scope is not permitted");
        // Read-only business view; access itself is audited in the same writable transaction.
        var at=jdbc.queryForObject("SELECT transaction_timestamp()",java.sql.Timestamp.class).toInstant();
        var counts=jdbc.queryForMap("""
            SELECT count(*) FILTER(WHERE o.state IN('QUEUED','ATTEMPTING','RETRY_WAIT')) pending,
                   count(*) FILTER(WHERE o.state IN('REJECTED','DEAD')) failed
            FROM adapter_outbox o JOIN adapter_message m ON m.id=o.message_id WHERE m.request_id=?
            """,requestId);
        long ready=jdbc.queryForObject("SELECT count(*) FROM storage_version WHERE request_id=? AND state='READY'",Long.class,requestId);
        var events=jdbc.query("""
            SELECT id,occurred_at,operation_code,resource_type,result_version,trace_id FROM audit_event
             WHERE hospital_id=? AND resource_id=? ORDER BY occurred_at DESC,id DESC LIMIT 20
            """,(r,i)->new Event(r.getObject(1,UUID.class),r.getTimestamp(2).toInstant(),r.getString(3),r.getString(4),r.getLong(5),r.getObject(6,UUID.class)),scope.hospitalId(),requestId);
        StorageProvider.Capacity capacity;
        try { capacity=storage.capacity(); }
        catch(IOException unavailable) { capacity=new StorageProvider.Capacity("UNAVAILABLE",null,null,null); }
        audit.append(scope.hospitalId(),"OPERATIONS_READ","PATHOLOGY_REQUEST",requestId,null,0);
        return new Snapshot(requestId,at,((Number)counts.get("pending")).longValue(),((Number)counts.get("failed")).longValue(),ready,
            capacity.state(),capacity.volumeTotal(),capacity.volumeUsable(),"NOT_CONFIGURED","NOT_CONFIGURED","NOT_VERIFIED",events);
    }
}
