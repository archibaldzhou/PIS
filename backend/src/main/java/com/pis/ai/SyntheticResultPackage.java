package com.pis.ai;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.pis.scan.ScanFormat;
/** Small fixed visual fixture, never model output, clinical probability or a learned ROI. */
public final class SyntheticResultPackage {
 public static final String SCHEMA="SYN-RESULT-1",GENERATOR="SYN-OVERLAY-1";
 public static final byte[] PREFIX="PIS-SYNTHETIC-STORAGE-V1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 private SyntheticResultPackage(){}
 public record Binding(UUID resultId,UUID taskId,long taskVersion,int generation,UUID hospitalId,UUID slideId,long identityVersion,UUID inputArtifactId,String inputArtifactHash,String taskBindingHash,AiRegistryService.WorkerBinding input,int width,int height,String generator,String runtime){}
 public record Region(double x,double y,double width,double height){}
 public record Package(String schema,Binding binding,int heatmapWidth,int heatmapHeight,int tileCount,List<Integer> intensities,List<Region> regions,String pngBase64,String pngHash,String meaning,boolean executionAllowed){}
 public static Package generate(Binding b){if(b.resultId()==null||b.taskId()==null||b.hospitalId()==null||b.slideId()==null||b.inputArtifactId()==null||b.input()==null||b.identityVersion()<0||b.taskVersion()<0||b.generation()<1||b.generation()>3||b.inputArtifactHash()==null||!b.inputArtifactHash().matches("[a-f0-9]{64}")||!b.generator().equals(GENERATOR)||!b.runtime().equals(Runtime.version().toString())||b.width()<1||b.height()<1||b.width()>2048||b.height()>2048)throw new IllegalArgumentException("Unsupported result generator");var values=new ArrayList<Integer>();byte[] seed=HexFormat.of().parseHex(b.inputArtifactHash());for(int n=0;n<16;n++)values.add(Byte.toUnsignedInt(seed[n]));var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<64;y++)for(int x=0;x<64;x++){int v=values.get((y/16)*4+x/16);image.setRGB(x,y,0xff000000|(v<<16)|((255-v)<<8)|64);}byte[] png;try(var out=new ByteArrayOutputStream()){if(!ImageIO.write(image,"png",out))throw new IOException("PNG writer missing");png=out.toByteArray();}catch(IOException e){throw new IllegalStateException("Synthetic result encoding failed",e);}finally{image.flush();}
  var p=new Package(SCHEMA,b,64,64,1,List.copyOf(values),List.of(new Region(b.width()/4.0,b.height()/4.0,b.width()/2.0,b.height()/2.0)),Base64.getEncoder().encodeToString(png),ScanFormat.sha(png),"SYNTHETIC_INTENSITY_0_255_NOT_DISEASE_RISK",false);validate(p,b);return p;
 }
 public static byte[] validate(Package p,Binding expected){if(p==null||!SCHEMA.equals(p.schema())||!expected.equals(p.binding())||p.executionAllowed()||!"SYNTHETIC_INTENSITY_0_255_NOT_DISEASE_RISK".equals(p.meaning())||p.heatmapWidth()!=64||p.heatmapHeight()!=64||p.tileCount()!=1||p.intensities()==null||p.intensities().size()!=16||p.intensities().stream().anyMatch(v->v==null||v<0||v>255)||p.regions()==null||p.regions().size()>4||p.regions().isEmpty()||p.pngBase64()==null||p.pngBase64().length()>32768)throw new IllegalArgumentException("Invalid result schema");
  for(var r:p.regions())if(r==null||!Double.isFinite(r.x())||!Double.isFinite(r.y())||!Double.isFinite(r.width())||!Double.isFinite(r.height())||r.x()<0||r.y()<0||r.width()<=0||r.height()<=0||r.x()+r.width()>expected.width()||r.y()+r.height()>expected.height())throw new IllegalArgumentException("Invalid result region");
  byte[] png=Base64.getDecoder().decode(p.pngBase64());if(png.length>24576||png.length<33||!ScanFormat.sha(png).equals(p.pngHash())||!Arrays.equals(Arrays.copyOf(png,8),new byte[]{(byte)137,80,78,71,13,10,26,10})||java.nio.ByteBuffer.wrap(png,16,4).getInt()!=64||java.nio.ByteBuffer.wrap(png,20,4).getInt()!=64)throw new IllegalArgumentException("Invalid result PNG");return png;
 }
}
