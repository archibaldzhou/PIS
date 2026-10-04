package com.pis.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free test contract shared by JUnit and the actual-provider local probe. */
public final class StorageConcurrencyContract {
    private StorageConcurrencyContract() { }
    private static final int MAX_ATTEMPTS=32;
    private static final long RETRY_NANOS=TimeUnit.SECONDS.toNanos(2);
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static byte[] data() { return data(65536); }
    private static byte[] data(int size) { byte[] bytes=new byte[size];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);System.arraycopy(StoragePolicy.MARKER,0,bytes,0,StoragePolicy.MARKER.length);return bytes; }
    private static String hash(byte[] bytes)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    // Admission BUSY means this callable never ran. Only this code is retryable; no integrity/IO error is swallowed.
    private static <T>T retryBusy(Callable<T> work)throws Exception {
        long end=System.nanoTime()+RETRY_NANOS;
        for(int attempt=1;;attempt++) {
            try { return work.call(); }
            catch(StorageProvider.Failure failure) {
                if(!failure.code.equals("STORAGE_BUSY"))throw failure;
                long remaining=end-System.nanoTime();
                if(attempt>=MAX_ATTEMPTS||remaining<=0)throw failure;
                long backoff=TimeUnit.MILLISECONDS.toNanos(Math.min(100L,1L<<Math.min(attempt-1,7)));
                // Explicit bounded backoff only after proven admission rejection, not timing-based synchronization.
                TimeUnit.NANOSECONDS.sleep(Math.min(backoff,remaining));
            }
        }
    }
    private static void publish(LocalStorageProvider p,UUID id,byte[] bytes,String hash)throws Exception {
        retryBusy(()->{p.publish(id,bytes.length,hash);return null;});
    }
    private static void exact(LocalStorageProvider p,Path root,UUID id,byte[] bytes,String expected)throws Exception {
        byte[] original=Files.readAllBytes(root.resolve(id+".blob"));
        check(Arrays.equals(original,bytes)&&hash(original).equals(expected),"Original bytes/hash changed");
        var returned=new java.io.ByteArrayOutputStream();
        for(int start=0;start<bytes.length;start+=1048576) {
            int end=Math.min(bytes.length-1,start+1048575);
            String range=bytes.length<=1048576?null:"bytes="+start+"-"+end;
            var read=retryBusy(()->p.read(id,bytes.length,expected,range));
            check(read.start()==start&&read.end()==end&&read.total()==bytes.length,"Replay range changed");
            returned.write(read.bytes());
        }
        check(Arrays.equals(returned.toByteArray(),bytes)&&hash(returned.toByteArray()).equals(expected),"Replay bytes/hash differ");
    }
    public static void simultaneous(Path root)throws Exception { simultaneous(root,65536); }
    public static void simultaneous(Path root,int size)throws Exception {
        UUID id=UUID.randomUUID();byte[] bytes=data(size);String expected=hash(bytes);
        try(var p=new LocalStorageProvider(root);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            p.stage(id,new ByteArrayInputStream(bytes),bytes.length,expected);
            var start=new CyclicBarrier(3);
            Callable<String> work=()->{
                start.await(5,TimeUnit.SECONDS);
                try {p.publish(id,bytes.length,expected);return "PUBLISHED";}
                catch(StorageProvider.Failure failure) {
                    // The losing reader can see the winner's incomplete CREATE_NEW file and must fail closed.
                    if(!List.of("STORAGE_BUSY","STORAGE_INTEGRITY").contains(failure.code))throw failure;
                    return failure.code;
                }
            };
            var a=pool.submit(work);var b=pool.submit(work);start.await(5,TimeUnit.SECONDS);
            var outcomes=List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            check(outcomes.contains("PUBLISHED"),"No publisher succeeded: "+outcomes);
            publish(p,id,bytes,expected);exact(p,root,id,bytes,expected);
            byte[] changed=bytes.clone();changed[changed.length-1]^=1;String other=hash(changed);
            try {publish(p,id,changed,other);throw new AssertionError("Different payload was accepted");}
            catch(StorageProvider.Failure failure) {check(failure.code.equals("STORAGE_INTEGRITY"),"Unexpected payload rejection: "+failure.code);}
            exact(p,root,id,bytes,expected);
        }
        // All I/O was joined before close; the same physical root must be acquirable and exactly replayable.
        try(var reopened=new LocalStorageProvider(root)) {publish(reopened,id,bytes,expected);exact(reopened,root,id,bytes,expected);}
    }
    private static final class GatedInput extends ByteArrayInputStream {
        private final CountDownLatch entered,release;
        final AtomicBoolean closed=new AtomicBoolean();
        GatedInput(byte[] bytes,CountDownLatch entered,CountDownLatch release) {super(bytes);this.entered=entered;this.release=release;}
        @Override public synchronized int read(byte[] bytes,int offset,int length) {
            entered.countDown();
            try {check(release.await(10,TimeUnit.SECONDS),"Test did not release staged reader");}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError("Reader interrupted",e);}
            return super.read(bytes,offset,length);
        }
        @Override public void close()throws IOException {closed.set(true);super.close();}
    }
    public static void saturatedAdmissionAndRecovery(Path root)throws Exception {
        byte[] bytes=data();String expected=hash(bytes);UUID target=UUID.randomUUID();
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var p=new LocalStorageProvider(root);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            p.stage(target,new ByteArrayInputStream(bytes),bytes.length,expected);
            var first=new GatedInput(bytes,entered,release);var second=new GatedInput(bytes,entered,release);
            UUID firstId=UUID.randomUUID(),secondId=UUID.randomUUID();
            Callable<Void> one=()->retryBusy(()->{p.stage(firstId,first,bytes.length,expected);return null;});
            Callable<Void> two=()->retryBusy(()->{p.stage(secondId,second,bytes.length,expected);return null;});
            var a=pool.submit(one);var b=pool.submit(two);
            try {
                check(entered.await(5,TimeUnit.SECONDS),"Two actual I/O workers did not enter concurrently");
                List<String> names;try(var paths=Files.list(root)){names=paths.map(v->v.getFileName().toString()).sorted().toList();}
                try {p.publish(target,bytes.length,expected);throw new AssertionError("Saturated admission succeeded");}
                catch(StorageProvider.Failure failure) {check(failure.code.equals("STORAGE_BUSY"),"Not an admission rejection");}
                var attempts=new AtomicInteger();
                try {retryBusy(()->{attempts.incrementAndGet();p.publish(target,bytes.length,expected);return null;});throw new AssertionError("Exhausted retry became success");}
                catch(StorageProvider.Failure failure) {check(failure.code.equals("STORAGE_BUSY"),"Exhaustion lost BUSY");}
                check(attempts.get()>1&&attempts.get()<=MAX_ATTEMPTS,"Retry was not bounded");
                check(!Files.exists(root.resolve(target+".blob")),"Rejected request wrote an original");
                check(Arrays.equals(Files.readAllBytes(root.resolve(target+".part")),bytes),"Rejected request changed staging");
                try(var paths=Files.list(root)){check(names.equals(paths.map(v->v.getFileName().toString()).sorted().toList()),"Admission rejection created partial files");}
            } finally {release.countDown();}
            a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
            check(first.closed.get()&&second.closed.get(),"Staging inputs were not released");
            publish(p,target,bytes,expected);exact(p,root,target,bytes,expected);
            publish(p,target,bytes,expected);exact(p,root,target,bytes,expected);
        } finally {release.countDown();}
        try(var reopened=new LocalStorageProvider(root)){exact(reopened,root,target,bytes,expected);}
    }
    public static void nonBusyErrorsAreNeverRetried()throws Exception {
        var attempts=new AtomicInteger();
        try {retryBusy(()->{attempts.incrementAndGet();throw new StorageProvider.Failure("STORAGE_INTEGRITY");});throw new AssertionError("Integrity failure swallowed");}
        catch(StorageProvider.Failure failure) {check(failure.code.equals("STORAGE_INTEGRITY"),"Failure changed");}
        check(attempts.get()==1,"Retried a non-admission failure");
    }
    public static void main(String[] args)throws Exception {
        Path base=Files.createTempDirectory("pis-storage-concurrency-");
        for(int i=0;i<100;i++)simultaneous(base.resolve("race-"+i));
        saturatedAdmissionAndRecovery(base.resolve("saturated"));nonBusyErrorsAreNeverRetried();
        System.out.println("PASS real provider: 100 barrier races, immutable bytes/SHA/replay, different payload rejected; two-worker saturation BUSY without files, bounded exhaustion, stream/owner release and exact retry; non-BUSY never retried. Not Spring/HTTP E2E.");
    }
}
