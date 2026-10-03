package com.pis.viewer;

import com.pis.api.ApiException;
import com.pis.digitalqc.DigitalQcService;
import com.pis.digitalqc.DigitalQcService.ConsumerBinding;
import com.pis.idempotency.*;
import com.pis.audit.CurrentActor;
import com.pis.scan.ScanFormat;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ViewerService {
    public record Prepare(@NotNull @Min(0) Long publicationVersion) {}
    public record Content(UUID hospitalId,UUID requestId,UUID scanId,UUID slideId,UUID objectId,String objectHash,
        long scanVersion,String provider,int width,int height,int tileSize,int maxLevel,String manifestHash,List<SyntheticPyramid.Tile> tiles) {}
    public record Manifest(Content content,long publicationVersion,String capability,String calibration) {}
    public record Binary(byte[] bytes,String hash) {}
    private record Cached(Content content,SyntheticPyramid.Result pyramid) {}
    private final DigitalQcService qc;
    private final JdbcTemplate jdbc;
    private final IdempotentCommands commands;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Semaphore generators=new Semaphore(2);
    // Private decoded/encoded image cache. Authorization is never stored as a reusable decision.
    private final LinkedHashMap<ConsumerBinding,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private long cacheBytes;
    public ViewerService(DigitalQcService qc,JdbcTemplate jdbc,IdempotentCommands commands,PlatformTransactionManager manager){this.qc=qc;this.jdbc=jdbc;this.commands=commands;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    private synchronized Cached cached(ConsumerBinding key){return cache.get(key);}
    private synchronized void save(ConsumerBinding key,Cached value){
        var old=cache.remove(key);if(old!=null)cacheBytes-=old.pyramid().byteSize();
        cache.put(key,value);cacheBytes+=value.pyramid().byteSize();
        while(cache.size()>8||cacheBytes>16L*1024*1024){var it=cache.entrySet().iterator();var e=it.next();cacheBytes-=e.getValue().pyramid().byteSize();it.remove();}
    }
    private ConsumerBinding authorize(UUID request,UUID scan,long publication,String action){return qc.authorizeConsumer(request,scan,publication,action);}
    private void unchanged(ConsumerBinding expected,String action){if(!expected.equals(authorize(expected.requestId(),expected.scanId(),expected.publicationVersion(),action)))throw conflict("VIEWER_BINDING");}
    private Cached generate(ConsumerBinding binding){
        var cached=cached(binding);if(cached!=null)return cached;
        if(!generators.tryAcquire())throw problem(HttpStatus.TOO_MANY_REQUESTS,"VIEWER_BUSY");
        try {
            byte[] original=qc.consume(binding.requestId(),binding.scanId(),binding.publicationVersion(),null).bytes();
            if(!ScanFormat.sha(original).equals(binding.objectHash()))throw conflict("VIEWER_BINDING");
            var pyramid=SyntheticPyramid.generate(original);
            String digest=ScanFormat.sha((binding.hospitalId()+":"+binding.requestId()+":"+binding.scanId()+":"+binding.slideId()+":"+binding.objectId()+":"+binding.objectHash()+":"+binding.scanVersion()+":"+pyramid.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var content=new Content(binding.hospitalId(),binding.requestId(),binding.scanId(),binding.slideId(),binding.objectId(),binding.objectHash(),binding.scanVersion(),SyntheticPyramid.VERSION,pyramid.width(),pyramid.height(),SyntheticPyramid.TILE,pyramid.maxLevel(),digest,pyramid.tiles());
            unchanged(binding,"VIEWER_PREPARE");var result=new Cached(content,pyramid);save(binding,result);return result;
        }catch(IllegalArgumentException e){throw problem(HttpStatus.BAD_REQUEST,Set.of("VIEWER_SIZE","VIEWER_UNSUPPORTED","VIEWER_CORRUPT","VIEWER_TIMEOUT","VIEWER_ENCODER").contains(e.getMessage())?e.getMessage():"VIEWER_CORRUPT");}
        finally{generators.release();}
    }
    private Content stored(UUID scan){var rows=jdbc.queryForList("SELECT manifest::text FROM viewer_manifest WHERE scan_id=?",String.class,scan);if(rows.isEmpty())throw conflict("VIEWER_NOT_PREPARED");return json.readValue(rows.getFirst(),Content.class);}
    private void bind(Content content,ConsumerBinding b){if(!content.hospitalId().equals(b.hospitalId())||!content.requestId().equals(b.requestId())||!content.scanId().equals(b.scanId())||!content.slideId().equals(b.slideId())||!content.objectId().equals(b.objectId())||!content.objectHash().equals(b.objectHash())||content.scanVersion()!=b.scanVersion()||!content.provider().equals(SyntheticPyramid.VERSION))throw conflict("VIEWER_BINDING");}
    private void budget(ConsumerBinding b,int size){tx.executeWithoutResult(s->{unchanged(b,"VIEWER_MANIFEST");var minute=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);jdbc.update("INSERT INTO viewer_read_budget(user_id,minute,requests,bytes) VALUES(?,?,0,0) ON CONFLICT DO NOTHING",b.actorId(),minute);if(jdbc.update("UPDATE viewer_read_budget SET requests=requests+1,bytes=bytes+? WHERE user_id=? AND minute=? AND requests<240 AND bytes+?<=33554432",size,b.actorId(),minute,size)!=1)throw problem(HttpStatus.TOO_MANY_REQUESTS,"VIEWER_RATE");});}
    public IdempotentCommands.Result prepare(UUID request,UUID scan,long publication,String key){
        var b=authorize(request,scan,publication,"VIEWER_PREPARE");budget(b,0);var generated=generate(b);
        return commands.execute(b.hospitalId(),"VIEWER_PREPARE_V1",key,Map.of("request",request,"scan",scan,"publication",publication),new IdempotentCommands.Work(){
            public void authorize(CurrentActor.Actor actor){unchanged(b,"VIEWER_PREPARE");}
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);bind(stored(scan),b);}
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){unchanged(b,"VIEWER_PREPARE");jdbc.update("INSERT INTO viewer_manifest(scan_id,object_id,object_hash,scan_version,provider,manifest_hash,manifest,created_by) VALUES(?,?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",scan,b.objectId(),b.objectHash(),b.scanVersion(),SyntheticPyramid.VERSION,generated.content().manifestHash(),json.writeValueAsString(generated.content()),b.actorId());var saved=stored(scan);bind(saved,b);if(!saved.equals(generated.content()))throw conflict("VIEWER_BINDING");return new IdempotentCommands.Mutation(new CommandReceipt(200,"VIEWER_MANIFEST",scan,0),null);}
        });
    }
    public Manifest manifest(UUID request,UUID scan,long publication){var b=authorize(request,scan,publication,"VIEWER_MANIFEST");var content=stored(scan);bind(content,b);budget(b,0);unchanged(b,"VIEWER_MANIFEST");return new Manifest(content,publication,"SYNTHETIC_RGB_ONLY","UNAVAILABLE_NO_PHYSICAL_SCALE");}
    public Content annotationHistory(UUID request,UUID scan){var b=qc.authorizeAnnotationHistory(request,scan);var c=stored(scan);if(!c.hospitalId().equals(b.hospitalId())||!c.requestId().equals(b.requestId())||!c.scanId().equals(b.scanId())||!c.slideId().equals(b.slideId())||!c.objectId().equals(b.objectId())||!c.objectHash().equals(b.objectHash()))throw conflict("VIEWER_BINDING");return c;}
    public Binary tile(UUID request,UUID scan,long publication,int level,int x,int y,boolean thumbnail){
        String action=thumbnail?"VIEWER_THUMBNAIL":"VIEWER_TILE";var b=authorize(request,scan,publication,action);var saved=stored(scan);bind(saved,b);
        if(thumbnail){level=Math.max(0,saved.maxLevel()-2);x=0;y=0;}
        if(level<0||level>saved.maxLevel()||x<0||y<0||x>3||y>3)throw problem(HttpStatus.BAD_REQUEST,"VIEWER_COORDINATE");
        final int l=level,tx=x,ty=y;var spec=saved.tiles().stream().filter(t->t.level()==l&&t.x()==tx&&t.y()==ty).findFirst().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"VIEWER_TILE_MISSING"));
        budget(b,spec.size());var generated=generate(b);if(!saved.equals(generated.content()))throw conflict("VIEWER_BINDING");
        byte[] bytes=generated.pyramid().bytes().get(SyntheticPyramid.key(l,tx,ty));if(bytes==null||!ScanFormat.sha(bytes).equals(spec.sha256()))throw conflict("VIEWER_TILE_MISSING");
        unchanged(b,action);return new Binary(bytes.clone(),spec.sha256());
    }
    private static ApiException conflict(String code){return problem(HttpStatus.CONFLICT,code);}
    private static ApiException problem(HttpStatus status,String code){return new ApiException(status,code,"Synthetic viewer unavailable; verify the authorized exact scan version");}
}
