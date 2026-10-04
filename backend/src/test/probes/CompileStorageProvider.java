import javax.tools.ToolProvider;
/** Run from repository root; compiles the real dependency-free provider, not Spring services. */
class CompileStorageProvider {
 public static void main(String[] args) {
  if(args.length!=1)throw new IllegalArgumentException("Supply an existing task-owned temporary class directory");
  String base="backend/src/main/java/com/pis/storage/";
  if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",args[0],base+"StorageProvider.java",base+"StoragePolicy.java",base+"S3StorageContract.java",base+"LocalStorageProvider.java","backend/src/test/java/com/pis/storage/StorageConcurrencyContract.java")!=0)throw new AssertionError("Provider compilation failed");
 }
}
