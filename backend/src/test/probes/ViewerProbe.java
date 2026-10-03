import java.nio.file.*;
import javax.tools.ToolProvider;
import java.net.*;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
/** Real JDK generator compilation, binary/pixel checks and reproducible synthetic browser fixtures. */
class ViewerProbe {
 public static void main(String[] args)throws Exception {
  Path out=Files.createTempDirectory("pis-t31-classes-");String base="backend/src/main/java/com/pis/";
  if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",out.toString(),base+"label/LabelBarcode.java",base+"scan/ScanFormat.java",base+"viewer/SyntheticPyramid.java")!=0)throw new AssertionError("compile");
  try(var loader=new URLClassLoader(new URL[]{out.toUri().toURL()})) {
   var type=loader.loadClass("com.pis.viewer.SyntheticPyramid");var label=loader.loadClass("com.pis.label.LabelBarcode");
   UUID id=UUID.fromString("11111111-1111-4111-8111-111111111131");String barcode=(String)label.getMethod("create",UUID.class).invoke(null,id);
   for(int variant=0;variant<2;variant++) {
    byte[] source=(byte[])type.getMethod("fixture",UUID.class,UUID.class,UUID.class,String.class,int.class,int.class,int.class).invoke(null,id,id,id,barcode,512,384,variant);
    var generate=type.getMethod("generate",byte[].class);var pyramid=generate.invoke(null,(Object)source);var again=generate.invoke(null,(Object)source);var result=pyramid.getClass();
    if(!result.getMethod("digest").invoke(pyramid).equals(result.getMethod("digest").invoke(again)))throw new AssertionError("determinism");
    Object raw=result.getMethod("bytes").invoke(pyramid);if(!(raw instanceof Map<?,?> bytes))throw new AssertionError();
    Path dir=Path.of("frontend/ui-tests/fixtures/viewer/"+(variant==0?"a":"b"));Files.createDirectories(dir);
    StringBuilder tiles=new StringBuilder("[");boolean first=true;
    for(var entry:bytes.entrySet()) {
     if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof byte[] png))throw new AssertionError();
     if(png.length>100000||png[0]!=(byte)137||png[1]!=80||png[2]!=78||png[3]!=71)throw new AssertionError("PNG magic");
     var decoded=ImageIO.read(new ByteArrayInputStream(png));if(decoded==null||decoded.getWidth()>128||decoded.getHeight()>128)throw new AssertionError("dimensions");
     var parts=key.split("/");if(Integer.parseInt(parts[0])==9&&parts[1].equals("0")&&parts[2].equals("0")&&decoded.getRGB(32,32)==decoded.getRGB(0,0))throw new AssertionError("actual pixels");
     String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
     if(!first)tiles.append(',');first=false;
     tiles.append("{\"level\":").append(parts[0]).append(",\"x\":").append(parts[1]).append(",\"y\":").append(parts[2]).append(",\"width\":").append(decoded.getWidth()).append(",\"height\":").append(decoded.getHeight()).append(",\"sha256\":\"").append(hash).append("\",\"size\":").append(png.length).append('}');
     Files.write(dir.resolve(key.replace('/','-')+".png"),png);decoded.flush();
    }
    Files.writeString(dir.resolve("tiles.json"),tiles.append(']').toString()+"\n");
    byte[] truncated=Arrays.copyOf(source,source.length-1);try{generate.invoke(null,(Object)truncated);throw new AssertionError("accepted truncation");}catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof IllegalArgumentException))throw expected;}
    source[23]='?';try{generate.invoke(null,(Object)source);throw new AssertionError("accepted unknown format");}catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof IllegalArgumentException))throw expected;}
   }
  }
  System.out.println("PASS actual JDK pyramid: deterministic PNG hashes, decoded dimensions/pixels, truncated/unknown rejection; browser fixtures generated");
 }
}
