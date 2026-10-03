import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.ManagementFactory;
import javax.tools.ToolProvider;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
/** T33 bounded provider validation, deliberately NOT Spring/HTTP/restart or WSI compatibility. */
class ViewerValidationProbe {
 static void rejected(java.lang.reflect.Method generate,byte[] bytes,String code)throws Exception {
  try { generate.invoke(null,(Object)bytes);throw new AssertionError("accepted "+code); }
  catch(java.lang.reflect.InvocationTargetException e){require(e.getCause() instanceof IllegalArgumentException && code.equals(e.getCause().getMessage()),"exact rejection "+code);}
 }
 static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  Path classes=Files.createTempDirectory("pis-t33-provider-");
  String src="backend/src/main/java/com/pis/";
  require(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),src+"label/LabelBarcode.java",src+"scan/ScanFormat.java",src+"viewer/SyntheticPyramid.java")==0,"compile actual provider");
  try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
   var type=loader.loadClass("com.pis.viewer.SyntheticPyramid");
   UUID id=UUID.fromString("11111111-1111-4111-8111-111111111131");
   String barcode=(String)loader.loadClass("com.pis.label.LabelBarcode").getMethod("create",UUID.class).invoke(null,id);
   var fixture=type.getMethod("fixture",UUID.class,UUID.class,UUID.class,String.class,int.class,int.class,int.class);
   var generate=type.getMethod("generate",byte[].class);
   var result=loader.loadClass("com.pis.viewer.SyntheticPyramid$Result");
   byte[][] sources=new byte[2][];String[] hashes=new String[2];long[] sizes=new long[2];
   for(int v=0;v<2;v++) {
    sources[v]=(byte[])fixture.invoke(null,id,id,id,barcode,512,384,v);
    Object p=generate.invoke(null,(Object)sources[v]);hashes[v]=(String)result.getMethod("digest").invoke(p);
    sizes[v]=(Long)result.getMethod("byteSize").invoke(p);
    require(result.getMethod("bytes").invoke(p) instanceof Map<?,?>,"tile map");
    var tiles=(Map<?,?>)result.getMethod("bytes").invoke(p);require(tiles.size()==24,"exact tile count");
    long checked=0;
    for(var entry:tiles.entrySet()) {
     String[] coords=((String)entry.getKey()).split("/");int l=Integer.parseInt(coords[0]),x=Integer.parseInt(coords[1]),y=Integer.parseInt(coords[2]),scale=1<<(9-l);
     byte[] png=(byte[])entry.getValue();require(png.length<=100000 && png[0]==(byte)137 && png[1]==80,"bounded PNG");
     var image=ImageIO.read(new ByteArrayInputStream(png));require(image!=null,"decode PNG");
     require(image.getWidth()==Math.min(128,(512+scale-1)/scale-x*128)&&image.getHeight()==Math.min(128,(384+scale-1)/scale-y*128),"edge dimensions");
     for(int py=0;py<image.getHeight();py++)for(int px=0;px<image.getWidth();px++) {
      int sx=Math.min(511,(x*128+px)*scale),sy=Math.min(383,(y*128+py)*scale),dx=sx%64-32,dy=sy%64-32;
      boolean grid=sx%64<2||sy%64<2,cell=dx*dx+dy*dy<500;
      int r=grid?32:cell?160:245,g=grid?80:cell?72:210,b=grid?110:cell?155:230;
      if(v==1){int t=r;r=b;b=g;g=t;}
      require((image.getRGB(px,py)&0xffffff)==((r<<16)|(g<<8)|b),"exact RGB channel including downsample coordinates");checked++;
     }
     // Encoder emits no ICC profile; this validates raw channels, not a calibrated display.
     for(int pos=8;pos+12<=png.length;){int n=java.nio.ByteBuffer.wrap(png,pos,4).getInt();require(n>=0&&n<=png.length-pos-12,"chunk bounds");String chunk=new String(png,pos+4,4,java.nio.charset.StandardCharsets.US_ASCII);require(!chunk.equals("iCCP"),"unexpected ICC support");pos+=12+n;}
     image.flush();
    }
    System.out.println("FIXTURE variant="+v+" source_sha256="+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sources[v]))+" manifest="+hashes[v]+" bytes="+sizes[v]+" exact_pixels="+checked);
   }
   require(!hashes[0].equals(hashes[1]),"variant isolation");
   rejected(generate,Arrays.copyOf(sources[0],sources[0].length-1),"VIEWER_SIZE");
   rejected(generate,new byte[800000],"VIEWER_SIZE");
   byte[] unknown=sources[0].clone();int magicOffset=((byte[])loader.loadClass("com.pis.scan.ScanFormat").getField("TRANSPORT").get(null)).length;unknown[magicOffset]='?';rejected(generate,unknown,"VIEWER_UNSUPPORTED");
   byte[] corruptTransport=sources[0].clone();corruptTransport[0]='?';rejected(generate,corruptTransport,"VIEWER_CORRUPT");
   byte[] headerOnly=(byte[])loader.loadClass("com.pis.scan.ScanFormat").getMethod("fixture",UUID.class,UUID.class,UUID.class,String.class,int.class,int.class).invoke(null,id,id,id,barcode,32,32);
   rejected(generate,headerOnly,"VIEWER_UNSUPPORTED");
   Object edge=generate.invoke(null,(Object)(byte[])fixture.invoke(null,id,id,id,barcode,257,129,0));
   var edgeBytes=(Map<?,?>)result.getMethod("bytes").invoke(edge);
   var edgeImage=ImageIO.read(new ByteArrayInputStream((byte[])edgeBytes.get("9/2/1")));
   require(edgeImage.getWidth()==1&&edgeImage.getHeight()==1,"non-power-of-two clipped edge");edgeImage.flush();
   System.out.println("PASS rejected truncated, oversized, unknown magic and header-only; 257x129 edge tile 1x1");

   var active=new AtomicInteger();var peak=new AtomicInteger();long started=System.nanoTime();
   var pool=Executors.newFixedThreadPool(2);var tasks=new ArrayList<Callable<String>>();
   for(int i=0;i<32;i++){final int v=i%2;tasks.add(()->{int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);try{Object p=generate.invoke(null,(Object)sources[v]);require(result.getMethod("digest").invoke(p).equals(hashes[v]),"concurrent deterministic result");return hashes[v];}finally{active.decrementAndGet();}});}
   try{for(var f:pool.invokeAll(tasks,60,TimeUnit.SECONDS)){require(!f.isCancelled(),"development 60 second limit");f.get();}}finally{pool.shutdownNow();require(pool.awaitTermination(10,TimeUnit.SECONDS),"worker cleanup");}
   require(active.get()==0 && peak.get()<=2,"worker bound");
   Thread.currentThread().interrupt();try{generate.invoke(null,(Object)sources[0]);throw new AssertionError("interrupt ignored");}catch(java.lang.reflect.InvocationTargetException expected){require(expected.getCause() instanceof IllegalArgumentException&&expected.getCause().getMessage().equals("VIEWER_TIMEOUT"),"cancel boundary");}finally{Thread.interrupted();}
   var pools=ManagementFactory.getMemoryPoolMXBeans();long peakHeap=pools.stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
   System.out.println("LOAD jobs=32 threads="+peak.get()+" duration_ms="+(System.nanoTime()-started)/1_000_000+" max_heap_bytes="+Runtime.getRuntime().maxMemory()+" sum_pool_peak_heap_bytes="+peakHeap+" java="+System.getProperty("java.version")+" os="+System.getProperty("os.name")+" arch="+System.getProperty("os.arch"));
   System.out.println("PASS provider-only bounded concurrency, exact RGB, levels, cancellation; ICC/real WSI/GPU/full application restart NOT VERIFIED");
  } finally {try(var paths=Files.walk(classes)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
