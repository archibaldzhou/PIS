package com.pis.ai;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
/** Bounded test-only failure evidence; never print raw configuration or credential lines. */
public final class WorkerProcessDiagnostics {
 private WorkerProcessDiagnostics(){}
 public static String tail(Path log)throws IOException{
  try(var in=Files.newByteChannel(log,StandardOpenOption.READ)){
   long size=in.size();in.position(Math.max(0,size-8192));var bytes=ByteBuffer.allocate((int)Math.min(size,8192));while(bytes.hasRemaining()&&in.read(bytes)>0){}
   String raw=new String(bytes.array(),0,bytes.position(),StandardCharsets.UTF_8);String[] lines=raw.split("\\R");var result=new StringBuilder();
   for(int i=Math.max(0,lines.length-64);i<lines.length;i++){
    String line=lines[i].replaceAll("\\x1b\\[[0-9;]*m","");
    if(line.matches("(?i).*(password|secret|token|credential|authorization|jdbc:|datasource|username).*"))line="[sensitive configuration line omitted]";
    else line=line.replaceAll("https?://\\S+","[url]").replaceAll("/[A-Za-z0-9_./-]+","[path]").replaceAll("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}","[id]");
    result.append(line,0,Math.min(line.length(),240)).append('\n');
   }return result.toString();
  }
 }
}
