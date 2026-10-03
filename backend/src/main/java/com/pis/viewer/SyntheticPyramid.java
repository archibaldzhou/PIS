package com.pis.viewer;

import com.pis.scan.ScanFormat;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;

/** Only our bounded RGB bytes, never ImageIO readers, URLs, HTML or external codecs. */
public final class SyntheticPyramid {
    public static final String VERSION="SYN-RGB-PYRAMID-1";
    public static final int TILE=128,MAX=512,HEADER=ScanFormat.TRANSPORT.length+98;
    private static final byte[] MAGIC="PISRGB1\n".getBytes(StandardCharsets.US_ASCII);
    public record Tile(int level,int x,int y,int width,int height,String sha256,int size) {}
    public record Result(int width,int height,int maxLevel,List<Tile> tiles,Map<String,byte[]> bytes,String digest) {
        public long byteSize(){return bytes.values().stream().mapToLong(b->b.length).sum();}
    }
    private SyntheticPyramid(){}
    public static byte[] fixture(UUID patient,UUID caseId,UUID slide,String barcode,int width,int height,int variant) {
        if(width<1||height<1||width>MAX||height>MAX)throw new IllegalArgumentException("VIEWER_SIZE");
        var base=ScanFormat.fixture(patient,caseId,slide,barcode,width,height);
        byte[] data=Arrays.copyOf(base,HEADER+width*height*3);System.arraycopy(MAGIC,0,data,ScanFormat.TRANSPORT.length,8);
        // Deterministic geometric cells and quadrants, deliberately not real pathology.
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int dx=x%64-32,dy=y%64-32;boolean cell=dx*dx+dy*dy<500,grid=x%64<2||y%64<2;
            int r=grid?32:cell?160:245,g=grid?80:cell?72:210,b=grid?110:cell?155:230;
            if(variant%2!=0){int swap=r;r=b;b=g;g=swap;}
            int p=HEADER+(y*width+x)*3;data[p]=(byte)r;data[p+1]=(byte)g;data[p+2]=(byte)b;
        }
        return data;
    }
    public static Result generate(byte[] data) {
        long end=System.nanoTime()+5_000_000_000L;
        if(data.length<HEADER||data.length>HEADER+MAX*MAX*3)throw new IllegalArgumentException("VIEWER_SIZE");
        if(!Arrays.equals(Arrays.copyOfRange(data,ScanFormat.TRANSPORT.length,ScanFormat.TRANSPORT.length+8),MAGIC))throw new IllegalArgumentException("VIEWER_UNSUPPORTED");
        var header=ScanFormat.inspect(Arrays.copyOf(data,Math.min(512,data.length)));
        if(!header.code().equals("SYNTHETIC_RGB_ONLY"))throw new IllegalArgumentException("VIEWER_CORRUPT");
        int width=header.width(),height=header.height();
        if(width>MAX||height>MAX||data.length!=HEADER+width*height*3)throw new IllegalArgumentException("VIEWER_SIZE");
        int max=0;while((1<<max)<Math.max(width,height))max++;
        var tiles=new ArrayList<Tile>();var bytes=new LinkedHashMap<String,byte[]>();var canonical=new StringBuilder(VERSION+":"+width+":"+height+":");
        for(int level=0;level<=max;level++) {
            int divisor=1<<(max-level),w=(width+divisor-1)/divisor,h=(height+divisor-1)/divisor;
            for(int y=0;y<(h+TILE-1)/TILE;y++)for(int x=0;x<(w+TILE-1)/TILE;x++) {
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>end)throw new IllegalArgumentException("VIEWER_TIMEOUT");
                int tw=Math.min(TILE,w-x*TILE),th=Math.min(TILE,h-y*TILE);var image=new BufferedImage(tw,th,BufferedImage.TYPE_INT_RGB);
                for(int py=0;py<th;py++)for(int px=0;px<tw;px++) {
                    int sx=Math.min(width-1,(x*TILE+px)*divisor),sy=Math.min(height-1,(y*TILE+py)*divisor),p=HEADER+(sy*width+sx)*3;
                    image.setRGB(px,py,((data[p]&255)<<16)|((data[p+1]&255)<<8)|(data[p+2]&255));
                }
                byte[] png;try(var output=new ByteArrayOutputStream();var imageOutput=new javax.imageio.stream.MemoryCacheImageOutputStream(output)){if(!ImageIO.write(image,"png",imageOutput))throw new IOException();imageOutput.flush();png=output.toByteArray();}catch(IOException e){throw new IllegalArgumentException("VIEWER_ENCODER");}finally{image.flush();}
                var tile=new Tile(level,x,y,tw,th,ScanFormat.sha(png),png.length);tiles.add(tile);bytes.put(key(level,x,y),png);
                canonical.append(level).append('/').append(x).append('/').append(y).append(':').append(tile.sha256()).append(';');
            }
        }
        return new Result(width,height,max,List.copyOf(tiles),Collections.unmodifiableMap(bytes),ScanFormat.sha(canonical.toString().getBytes(StandardCharsets.US_ASCII)));
    }
    public static String key(int level,int x,int y){return level+"/"+x+"/"+y;}
}
