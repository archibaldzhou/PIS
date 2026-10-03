package com.pis.storage;
import java.io.*;import java.nio.*;import java.nio.channels.*;import java.nio.file.*;import java.nio.file.attribute.*;import java.security.*;import java.util.*;import java.util.concurrent.*;
/** Linux/POSIX private root. No caller-controlled paths, overwrites or file deletion. */
public final class LocalStorageProvider implements StorageProvider {
 private final Path root;private final SecureDirectoryStream<Path> directory;private final FileChannel directorySync,lockChannel;private final FileLock lock;private final UUID rootId;private final Object fileKey;
 private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"synthetic-storage-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 private static final Set<PosixFilePermission> PRIVATE=PosixFilePermissions.fromString("rwx------");
 public LocalStorageProvider(Path configured)throws IOException{
  root=configured.toAbsolutePath().normalize();if(!configured.isAbsolute()||root.getParent()==null)throw new Failure("STORAGE_ROOT_INVALID");
  for(Path p=root.getParent();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new Failure("STORAGE_ROOT_INVALID");
  if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(root,PosixFilePermissions.asFileAttribute(PRIVATE));
  if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS).equals(PRIVATE)||!Files.getOwner(root,LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name")))throw new Failure("STORAGE_ROOT_INVALID");
  var opened=Files.newDirectoryStream(root);if(!(opened instanceof SecureDirectoryStream<Path> secure)){opened.close();throw new Failure("STORAGE_UNVERIFIED");}directory=secure;
  fileKey=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
  // Only an empty private root or our existing marker is accepted; never adopt user files.
  Path marker=Path.of(".pis-storage-root-v1");boolean found=false,other=false;
  for(Path p:directory){if(p.getFileName().equals(marker))found=true;else other=true;}
  if(!found&&other){directory.close();throw new Failure("STORAGE_ROOT_INVALID");}
  if(!found){UUID id=UUID.randomUUID();try(var out=channel(marker,true)){out.write(ByteBuffer.wrap(id.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));out.force(true);}}
  UUID identity;try(var c=channel(marker,false)){if(c.size()!=36)throw new Failure("STORAGE_ROOT_INVALID");var b=ByteBuffer.allocate(36);while(b.hasRemaining()&&c.read(b)>=0){}identity=UUID.fromString(new String(b.array(),java.nio.charset.StandardCharsets.US_ASCII));}catch(IllegalArgumentException e){throw new Failure("STORAGE_ROOT_INVALID");}rootId=identity;
  Path lockName=Path.of(".pis-storage-lock");FileChannel candidate;
  try{candidate=channel(lockName,true);}catch(FileAlreadyExistsException e){candidate=openWritable(lockName);}
  lockChannel=candidate;try{lock=lockChannel.tryLock();}catch(OverlappingFileLockException e){lockChannel.close();directory.close();throw new Failure("STORAGE_BUSY");}
  if(lock==null){lockChannel.close();directory.close();throw new Failure("STORAGE_BUSY");}
  directorySync=FileChannel.open(root,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);directorySync.force(true);
 }
 private FileChannel openWritable(Path name)throws IOException{return asFile(directory.newByteChannel(name,Set.of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)));}
 private FileChannel channel(Path name,boolean create)throws IOException{return asFile(directory.newByteChannel(name,create?Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS):Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))));}
 private static FileChannel asFile(SeekableByteChannel c)throws IOException{if(c instanceof FileChannel f)return f;c.close();throw new Failure("STORAGE_UNVERIFIED");}
 private static Path key(UUID id,boolean staged){return Path.of(Objects.requireNonNull(id).toString()+(staged?".part":".blob"));}
 public String state(){return "CONFIGURED_LOCAL";} public UUID rootId(){return rootId;}
 private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static void deadline(long end)throws Failure{if(Thread.currentThread().isInterrupted()||System.nanoTime()>end)throw new Failure("STORAGE_TIMEOUT");}
 private <T>T bounded(Callable<T> call)throws IOException{
  Future<T> task;try{task=io.submit(call);}catch(RejectedExecutionException e){throw new Failure("STORAGE_BUSY");}
  try{return task.get(30,TimeUnit.SECONDS);}catch(TimeoutException e){task.cancel(true);throw new Failure("STORAGE_TIMEOUT");}catch(InterruptedException e){task.cancel(true);Thread.currentThread().interrupt();throw new Failure("STORAGE_TIMEOUT");}catch(ExecutionException e){if(e.getCause() instanceof IOException x)throw x;throw new Failure("STORAGE_IO");}
 }
 public void stage(UUID id,InputStream input,long size,String hash)throws IOException{
  StoragePolicy.size(size);bounded(()->{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);try(var out=channel(key(id,true),true);var in=input){var md=digest();byte[] block=new byte[65536];long n=0;var prefix=new ByteArrayOutputStream();int count;
   while((count=in.read(block))!=-1){deadline(end);if(count==0)continue;if(n+count>size)throw new Failure("STORAGE_SIZE");if(prefix.size()<StoragePolicy.MARKER.length)prefix.write(block,0,Math.min(count,StoragePolicy.MARKER.length-prefix.size()));md.update(block,0,count);var b=ByteBuffer.wrap(block,0,count);while(b.hasRemaining())out.write(b);n+=count;}
   if(n!=size||!HexFormat.of().formatHex(md.digest()).equals(hash)||!Arrays.equals(prefix.toByteArray(),StoragePolicy.MARKER))throw new Failure("STORAGE_INTEGRITY");out.force(true);directorySync.force(true);return null;}});
 }
 private byte[] verify(Path name,long size,String hash,StoragePolicy.Range range)throws IOException{
  StoragePolicy.size(size);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);byte[] selected=range==null?new byte[0]:new byte[range.length()];
  try(var in=channel(name,false)){if(in.size()!=size)throw new Failure("STORAGE_INTEGRITY");var md=digest();var b=ByteBuffer.allocate(65536);long offset=0;var prefix=new ByteArrayOutputStream();int n;
   while((n=in.read(b))!=-1){deadline(deadline);if(n==0)continue;if(offset+n>size)throw new Failure("STORAGE_INTEGRITY");if(prefix.size()<StoragePolicy.MARKER.length)prefix.write(b.array(),0,Math.min(n,StoragePolicy.MARKER.length-prefix.size()));md.update(b.array(),0,n);
    if(range!=null){long a=Math.max(offset,range.start()),z=Math.min(offset+n,range.end()+1);if(z>a)System.arraycopy(b.array(),(int)(a-offset),selected,(int)(a-range.start()),(int)(z-a));}offset+=n;b.clear();}
   if(offset!=size||!HexFormat.of().formatHex(md.digest()).equals(hash)||!Arrays.equals(prefix.toByteArray(),StoragePolicy.MARKER))throw new Failure("STORAGE_INTEGRITY");return selected;
  }
 }
 public void verifyStage(UUID id,long size,String hash)throws IOException{bounded(()->{verify(key(id,true),size,hash,null);return null;});}
 public void publish(UUID id,long size,String hash)throws IOException{
  bounded(()->{try{verify(key(id,false),size,hash,null);return null;}catch(NoSuchFileException absent){/* Only an absent original may be created. */}verify(key(id,true),size,hash,null);
   try(var source=channel(key(id,true),false);var out=channel(key(id,false),true)){var b=ByteBuffer.allocate(65536);long n=0,end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);int count;while((count=source.read(b))!=-1){deadline(end);n+=count;if(n>size)throw new Failure("STORAGE_INTEGRITY");b.flip();while(b.hasRemaining())out.write(b);b.clear();}out.force(true);directorySync.force(true);}
   catch(FileAlreadyExistsException existing){/* Never overwrite, even if the previous process crashed. */}
   verify(key(id,false),size,hash,null);return null;});
 }
 public Slice read(UUID id,long size,String hash,String header)throws IOException{var range=StoragePolicy.range(header,size);return bounded(()->new Slice(verify(key(id,false),size,hash,range),range.start(),range.end(),size));}
 public Capacity capacity()throws IOException{if(!Objects.equals(fileKey,Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()))throw new Failure("STORAGE_ROOT_INVALID");var store=Files.getFileStore(root);return new Capacity(state(),rootId,store.getTotalSpace(),store.getUsableSpace());}
 public boolean hasStage(UUID id)throws IOException{try{return directory.getFileAttributeView(key(id,true),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes().isRegularFile();}catch(NoSuchFileException e){return false;}}
 public void close()throws IOException{io.shutdownNow();lock.release();lockChannel.close();directorySync.close();directory.close();}
}
