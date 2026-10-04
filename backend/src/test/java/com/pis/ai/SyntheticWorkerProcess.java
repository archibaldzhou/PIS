package com.pis.ai;
import com.pis.PisApplication;
import com.pis.accession.*;
import com.pis.audit.*;
import com.pis.security.*;
import com.pis.api.TraceIdFilter;
import com.pis.storage.StorageService;
import com.pis.idempotency.IdempotentCommands;
import java.time.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.*;
/** Test classpath only. A real application JVM over the parent's disposable schema. */
public final class SyntheticWorkerProcess {
 private SyntheticWorkerProcess(){}
 public static void main(String[] args)throws Exception{
  if(args.length!=7||!args[0].matches("pis_test_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned test schema required");
  String url=System.getenv().getOrDefault("PIS_TEST_DB_URL","jdbc:postgresql://127.0.0.1:5433/pis_test?connectTimeout=5&socketTimeout=10");
  if(!url.matches("jdbc:postgresql://[^/]+/[^?]*_test(?:\\?.*)?"))throw new IllegalArgumentException("Disposable database required");
  var app=new SpringApplication(PisApplication.class);
  var props=new HashMap<String,Object>();props.put("spring.datasource.url",url);props.put("spring.datasource.username",System.getenv().getOrDefault("PIS_TEST_DB_USERNAME","pis_test"));props.put("spring.datasource.password",Objects.requireNonNull(System.getenv("PIS_TEST_DB_PASSWORD")));props.put("spring.datasource.hikari.schema",args[0]);props.put("spring.flyway.schemas",args[0]);props.put("spring.flyway.default-schema",args[0]);props.put("spring.profiles.active","test");props.put("server.address","127.0.0.1");props.put("server.port",0);props.put("pis.workflow.development-enabled",true);props.put("pis.ai.synthetic-worker-enabled",true);props.put("pis.storage.local-root",args[1]);app.setAdditionalProfiles("test");app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("owned-synthetic-process",props)));
  // Command-line overrides application.properties defaults; no credentials are passed or printed in argv.
  System.err.println("SYN_WORKER_STAGE_APPLICATION_START");
  try(var c=app.run("--pis.workflow.development-enabled=true","--pis.ai.synthetic-worker-enabled=true","--pis.storage.local-root="+args[1],"--server.port=0")){
   System.err.println("SYN_WORKER_STAGE_AUTHENTICATE");
   var principal=new PisPrincipal(c.getBean(AccountRepository.class).findByUsername(args[2]).orElseThrow());principal.eraseCredentials();SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
   if(args[6].startsWith("ADAPTER_")) {
    var adapter=c.getBean(com.pis.integration.HospitalAdapterService.class);UUID request=UUID.fromString(args[3]),id=UUID.fromString(args[4]);
    new TraceIdFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),(req,res)->{
     for(String operation:args[6].equals("ADAPTER_CRASH")?List.of("CLAIM","RECEIVE"):List.of("CLAIM","RECEIVE","ACK","RECONCILE")) {
      var item=adapter.view(request,1).items().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow();var action=com.pis.integration.HospitalAdapterContracts.Action.valueOf(operation);
      adapter.step(request,id,action,new com.pis.integration.HospitalAdapterContracts.Command(item.caseId(),item.sourceId(),item.payloadHash(),item.version(),action==com.pis.integration.HospitalAdapterContracts.Action.CLAIM?null:item.attemptId(),"Synthetic process recovery",true),"adapter-process-"+item.version());
     }
     if(args[6].equals("ADAPTER_CRASH")){System.err.println("SYN_WORKER_STAGE_DURABLE_ADAPTER_RECEIVE_CRASH");Runtime.getRuntime().halt(23);}
    });return;
   }
   var engine=new AiTaskService(c.getBean(JdbcTemplate.class),c.getBean(WorkflowAccess.class),c.getBean(RequestService.class),c.getBean(AiRegistryService.class),c.getBean(StorageService.class),c.getBean(IdempotentCommands.class),c.getBean(AuditRecorder.class),c.getBean(jakarta.validation.Validator.class),c.getBean(SyntheticWorkerMode.class),c.getBean(PlatformTransactionManager.class),Clock.fixed(Instant.parse(args[5]),ZoneOffset.UTC));
   UUID request=UUID.fromString(args[3]),id=UUID.fromString(args[4]);
   new TraceIdFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),(req,res)->{
    System.err.println("SYN_WORKER_STAGE_CLAIM");
    var j=engine.detail(request,id).job();engine.command(request,id,"CLAIM",new AiTaskContracts.Command(j.version(),j.generation(),j.leaseId(),"Synthetic process claim"),"process-claim:"+j.version());
    if(args[6].equals("CRASH")){System.err.println("SYN_WORKER_STAGE_DURABLE_CLAIM_CRASH");Runtime.getRuntime().halt(23);}
    var running=engine.detail(request,id).job();engine.run(request,id,new AiTaskContracts.Command(running.version(),running.generation(),running.leaseId(),"Synthetic restart completion"));
    if(!java.util.Arrays.equals(engine.artifact(request,id).bytes(),engine.fixture(running)))throw new IllegalStateException("SYN_WORKER_ARTIFACT_MISMATCH");
    System.err.println("SYN_WORKER_STAGE_VERIFIED_COMPLETION");
   });SecurityContextHolder.clearContext();
  }
 }
}
