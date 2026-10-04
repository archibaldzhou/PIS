import com.pis.storage.*;import java.nio.file.*;
public class WorkerLockProbe {
 public static void main(String[] args)throws Exception{
  if(args.length>0){try(var p=new LocalStorageProvider(Path.of(args[0]))){System.out.println("OPENED");}catch(StorageProvider.Failure e){System.out.println(e.code);System.exit(23);}return;}
  var root=Files.createTempDirectory("pis-t35-lock-probe-");
  try(var p=new LocalStorageProvider(root)){
   var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"WorkerLockProbe",root.toString()).redirectErrorStream(true).start();if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("bounded child timeout");}String out=new String(child.getInputStream().readAllBytes());if(child.exitValue()!=23||!out.contains("STORAGE_BUSY"))throw new AssertionError(out);
   var clone=Files.createTempDirectory("pis-t35-lock-snapshot-");Files.copy(root.resolve(".pis-storage-root-v1"),clone.resolve(".pis-storage-root-v1"),StandardCopyOption.COPY_ATTRIBUTES);
   for(int i=0;i<2;i++){var c=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"WorkerLockProbe",clone.toString()).redirectErrorStream(true).start();if(!c.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){c.destroyForcibly();throw new AssertionError("bounded child timeout");}if(c.exitValue()!=0)throw new AssertionError(new String(c.getInputStream().readAllBytes()));}
   var log=Files.createTempFile("pis-t35-redact-",".txt");Files.writeString(log,"password=private\njdbc:postgresql://private/db\nCaused by: STORAGE_BUSY\n");var tail=com.pis.ai.WorkerProcessDiagnostics.tail(log);if(tail.contains("private")||!tail.contains("STORAGE_BUSY"))throw new AssertionError("redaction");
   System.out.println("PASS actual provider JVM exclusive-root failure, sequential snapshot-root opens, bounded redaction; NOT full Spring application restart");
  }
 }
}
