package com.pis.worklist;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.processing.TechnicalService;
import com.pis.processing.TechnicalContracts;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.worklist.WorklistContracts.*;
@Service
public class WorklistService {
 private final JdbcTemplate jdbc; private final WorkflowAccess access; private final RequestService requests; private final TechnicalService technical; private final Validator validator; private final Clock clock; private final int dueMinutes;
 public WorklistService(JdbcTemplate jdbc,WorkflowAccess access,RequestService requests,TechnicalService technical,Validator validator,@org.springframework.beans.factory.annotation.Qualifier("worklistClock") Clock clock,@Value("${pis.worklist.synthetic-due-minutes:240}") int dueMinutes) {
  SyntheticDeadline.dueAt(java.time.Instant.EPOCH,dueMinutes); this.jdbc=jdbc;this.access=access;this.requests=requests;this.technical=technical;this.validator=validator;this.clock=clock;this.dueMinutes=dueMinutes;
 }
 // Shared SQL authorization for rows and totals, including each domain's independent permission.
 private static final String AUTH="""
  JOIN workflow_scope s ON s.id=w.scope_id JOIN workflow_grant g ON g.scope_id=s.id
  WHERE s.id=? AND s.enabled AND g.user_id=? AND g.can_read AND g.revoked_at IS NULL
  AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())
  AND CASE w.permission WHEN 'READ' THEN true WHEN 'RECEIVE' THEN g.can_receive WHEN 'PROCESS' THEN g.can_process
  WHEN 'QC' THEN g.can_qc WHEN 'GROSS' THEN g.can_gross WHEN 'MATERIAL' THEN g.can_material WHEN 'PRINT' THEN g.can_print ELSE false END
  """;
 private void scope(UUID id) { try { access.require(id,Permission.READ); } catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); } }
 private void paging(int page,int size) { if(page<1||page>10000||size<1||size>50) throw new ApiException(HttpStatus.BAD_REQUEST,"WORKLIST_PAGE_INVALID","Invalid worklist pagination"); }
 @Transactional(timeout=10)
 public Page list(UUID scope,Kind kind,State state,Due due,Sort sort,int page,int pageSize) {
  scope(scope); if(kind!=Kind.ALL) {
   Permission permission=switch(kind) { case REQUEST -> Permission.READ; case RECEPTION -> Permission.RECEIVE; case TECHNICAL -> Permission.PROCESS; case QUALITY -> Permission.QC; default -> throw new IllegalArgumentException("Unknown worklist kind"); };
   try { access.require(scope,permission); } catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); }
  } paging(page,pageSize); var actor=access.actor(); var asOf=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  jdbc.execute("SET LOCAL statement_timeout='5s'");
  String order=switch(sort) { case OLDEST -> "created_at ASC,kind,entity_id"; case NEWEST -> "created_at DESC,kind,entity_id"; case NUMBER -> "request_number ASC,kind,entity_id"; };
  String sql="WITH visible AS (SELECT w.*,(w.active AND w.created_at<?::timestamptz-(? * interval '1 minute')) AS overdue FROM workflow_work_item w "+AUTH+"), filtered AS MATERIALIZED (SELECT * FROM visible WHERE (?='ALL' OR kind=?) AND (?='ALL' OR state=?) AND (?='ALL' OR (?='OVERDUE' AND overdue) OR (?='NOT_OVERDUE' AND NOT overdue))), totals AS (SELECT count(*) total FROM filtered), items AS (SELECT * FROM filtered ORDER BY "+order+" LIMIT ? OFFSET ?) SELECT totals.total,items.* FROM totals LEFT JOIN items ON true ORDER BY "+order;
  var rows=jdbc.queryForList(sql,OffsetDateTime.ofInstant(asOf,java.time.ZoneOffset.UTC),dueMinutes,scope,actor.id(),kind.name(),kind.name(),state.name(),state.name(),due.name(),due.name(),due.name(),pageSize,(page-1)*pageSize);
  var items=new ArrayList<Item>(); for(var r:rows) if(r.get("entity_id")!=null) {
   var created=((java.sql.Timestamp)r.get("created_at")).toInstant(); boolean active=(boolean)r.get("active");
   items.add(new Item((String)r.get("kind"),(UUID)r.get("entity_id"),(UUID)r.get("request_id"),(UUID)r.get("patient_id"),(String)r.get("request_number"),(String)r.get("state"),((Number)r.get("version")).longValue(),created,(UUID)r.get("cassette_id"),(boolean)r.get("blocked"),active,active?SyntheticDeadline.dueAt(created,dueMinutes):null,(boolean)r.get("overdue")));
  }
  scope(scope); return new Page(((Number)rows.getFirst().get("total")).longValue(),page,pageSize,asOf,dueMinutes,List.copyOf(items));
 }
 @Transactional(timeout=10)
 public Trace trace(UUID request,int page,int size) {
  var q=requests.detail(request); scope(q.scopeId()); paging(page,size); var actor=access.actor(); jdbc.execute("SET LOCAL statement_timeout='5s'");
  String sql="WITH filtered AS MATERIALIZED (SELECT e.* FROM workflow_trace_event e JOIN request_workflow rw ON rw.request_id=e.request_id CROSS JOIN LATERAL (SELECT rw.scope_id,e.permission) w "+AUTH+" AND e.request_id=?), totals AS (SELECT count(*) total FROM filtered), items AS (SELECT * FROM filtered ORDER BY occurred_at,domain,event_id LIMIT ? OFFSET ?) SELECT totals.total,items.* FROM totals LEFT JOIN items ON true ORDER BY occurred_at,domain,event_id";
  var rows=jdbc.queryForList(sql,q.scopeId(),actor.id(),request,size,(page-1)*size); var events=new ArrayList<TraceEvent>();
  for(var r:rows) if(r.get("event_id")!=null) events.add(new TraceEvent((UUID)r.get("event_id"),(String)r.get("domain"),(UUID)r.get("entity_id"),((Number)r.get("version")).longValue(),(String)r.get("action"),(UUID)r.get("related_id"),(String)r.get("related_type"),((java.sql.Timestamp)r.get("occurred_at")).toInstant()));
  scope(q.scopeId()); return new Trace(request,((Number)rows.getFirst().get("total")).longValue(),page,size,List.copyOf(events));
 }
 /** Each item owns its T07 transaction. Never wrap the batch in a transaction. */
 public BatchResult claim(UUID scope,Batch input) {
  var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors); scope(scope);
  if(input.items().stream().map(Claim::taskId).distinct().count()!=input.items().size()) throw new ApiException(HttpStatus.BAD_REQUEST,"WORKLIST_DUPLICATE_ITEM","A task can occur only once per batch");
  var results=new ArrayList<ItemResult>();
  for(var item:input.items()) {
   try {
    var result=technical.bulkClaim(scope,item.taskId(),new TechnicalContracts.Decision(item.expectedVersion(),item.confirmedCassetteId(),input.reason()),"bulk-"+input.batchId()+"-"+item.taskId());
    results.add(new ItemResult(item.taskId(),"SUCCESS",result.receipt().status(),"CLAIMED",result.receipt().version(),result.replayed()));
   } catch(ApiException e) { results.add(new ItemResult(item.taskId(),e.status().is4xxClientError()&&!List.of("COMMAND_BUSY","COMMAND_TIMEOUT").contains(e.code())?"REJECTED":"UNKNOWN",e.status().value(),e.status().value()==404?"WORKLIST_ITEM_UNAVAILABLE":e.code(),null,false)); }
   catch(org.springframework.security.access.AccessDeniedException e) { results.add(new ItemResult(item.taskId(),"REJECTED",404,"WORKLIST_ITEM_UNAVAILABLE",null,false)); }
   catch(org.springframework.dao.DataAccessException e) { results.add(new ItemResult(item.taskId(),"UNKNOWN",503,"WORKLIST_RESULT_UNCONFIRMED",null,false)); }
  }
  return new BatchResult(input.batchId(),List.copyOf(results));
 }
 private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"WORKLIST_NOT_FOUND","Worklist is unavailable"); }
}
