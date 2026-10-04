import com.pis.viewer.ViewerRequestMeter;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class ViewerRequestMeterProbe {
    static void check(boolean yes){if(!yes)throw new AssertionError("Request meter regression");}
    public static void main(String[] args)throws Exception{
        var minute=OffsetDateTime.parse("2026-01-01T00:00:00Z");var actor=UUID.randomUUID();
        var meter=new ViewerRequestMeter();var units=new AtomicInteger();var bytes=new AtomicInteger();
        ViewerRequestMeter.Charge charge=(at,n,size)->{check(at.equals(minute));units.addAndGet(n);bytes.addAndGet(size);};
        for(int i=0;i<14;i++)meter.charge(actor,minute,0,charge);
        meter.charge(actor,minute.plusMinutes(1),128,charge);meter.charge(actor,minute.plusMinutes(1),64,charge);
        check(units.get()==1&&bytes.get()==192&&meter.snapshot().checks()==16);
        var failed=new ViewerRequestMeter();boolean rejected=false;
        try{failed.charge(actor,minute,0,(at,n,size)->{throw new IllegalStateException("Synthetic accounting failure");});}catch(IllegalStateException expected){rejected=true;}
        check(rejected&&failed.snapshot().requestUnits()==0);failed.charge(actor,minute,0,(at,n,size)->check(n==1));
        var concurrent=new ViewerRequestMeter();var count=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<16;i++)jobs.add(pool.submit(()->concurrent.charge(actor,minute,1,(at,n,size)->count.addAndGet(n))));
            for(var job:jobs)job.get(5,TimeUnit.SECONDS);
        }
        check(count.get()==1&&concurrent.snapshot().bytes()==16);
        new ViewerRequestMeter().charge(actor,minute,0,(at,n,size)->check(n==1));
        System.out.println("PASS actual request meter: 14 checks→1 unit; all bytes charged; minute crossing; failed charge; 16 concurrent checks; next request isolated. NOT Spring/HTTP validation.");
    }
}
