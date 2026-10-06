package com.pis.identity;

import com.pis.api.*;
import com.pis.security.PisPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Domain authorization/command rejections persist after the command transaction rolled back. */
@RestControllerAdvice(basePackageClasses=IdentityController.class) @Order(-10)
public class IdentityFailureAdvice {
 private final JdbcTemplate jdbc; private final TransactionTemplate transaction;
 public IdentityFailureAdvice(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(3);}
 @ExceptionHandler({ApiException.class,AccessDeniedException.class})
 public ResponseEntity<ProblemDetail> failure(RuntimeException error,HttpServletRequest request){
  HttpStatus status=error instanceof ApiException e?e.status():HttpStatus.FORBIDDEN;
  String code=error instanceof ApiException e?e.code():"ACCESS_DENIED";
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  var match=java.util.regex.Pattern.compile("^/api/requests/admin/([0-9a-fA-F-]{36})(?:/|$)").matcher(request.getRequestURI());
  if(authentication!=null&&authentication.getPrincipal() instanceof PisPrincipal principal&&match.find()){
   UUID hospital=UUID.fromString(match.group(1));
   String journalCode=code;
   try{transaction.executeWithoutResult(s->jdbc.update("INSERT INTO identity_denial_event(id,hospital_id,actor_id,error_code,trace_id) VALUES(?,?,?,?,?)",UUID.randomUUID(),hospital,principal.id(),journalCode,TraceIdFilter.traceId(request)));}
   catch(org.springframework.dao.DataAccessException failed){status=HttpStatus.SERVICE_UNAVAILABLE;code="ADMIN_AUDIT_UNAVAILABLE";}
  }
  return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ApiProblems.create(request,status,code,"Administration request was rejected."));
 }
}
