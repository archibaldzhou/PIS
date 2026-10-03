package com.pis.report;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.DeflaterOutputStream;

/** Narrow image-only PDF writer. No PDF/HTML input, scripts, URLs, system fonts or subprocesses. */
public final class SyntheticPdf {
 public static final String VERSION="SYN-RASTER-PDF-1";
 public static final int MAX_BYTES=4*1024*1024, MAX_PAGES=16;
 private static final int WIDTH=1240,HEIGHT=1754,LEFT=85,TOP=190,LINE=31,LINES=46;
 private static final Semaphore SLOTS=new Semaphore(2);
 private static final String FONT_RESOURCE="/report-fonts/PISSyntheticSans.otf";
 public record Section(String name,String text) { }
 public record Document(UUID caseId,UUID revisionId,UUID signatureId,UUID reviewId,long draftVersion,String templateCode,int templateVersion,String schemaCode,String dependencyToken,List<Section> fields) { public Document { fields=List.copyOf(fields); } }
 public record Rendered(byte[] bytes,int pages,String sha256,String fontHash) { public Rendered { bytes=bytes.clone(); } @Override public byte[] bytes() { return bytes.clone(); } }
 public static final class RenderException extends RuntimeException { public final String code; public RenderException(String code) { super(code);this.code=code; } }
 private record Typeface(Font font,String hash) { }
 private static Typeface loadFont() {
  try(var stream=SyntheticPdf.class.getResourceAsStream(FONT_RESOURCE)) {
   if(stream==null) throw new RenderException("REPORT_FONT_UNAVAILABLE");byte[] bytes=stream.readNBytes(16*1024*1024+1);
   if(bytes.length>16*1024*1024||!sha256(bytes).equals(FONT_SHA256)) throw new RenderException("REPORT_FONT_UNAVAILABLE");
   return new Typeface(Font.createFont(Font.TRUETYPE_FONT,new java.io.ByteArrayInputStream(bytes)),sha256(bytes));
  } catch(RenderException e) { throw e; } catch(Exception e) { throw new RenderException("REPORT_FONT_UNAVAILABLE"); }
 }
 private static Typeface cachedFont;
 private static synchronized Typeface typeface() { if(cachedFont==null) cachedFont=loadFont();return cachedFont; }
 public Rendered render(Document doc) {
  if(!SLOTS.tryAcquire()) throw new RenderException("REPORT_RENDER_BUSY");
  long deadline=System.nanoTime()+5_000_000_000L;
  try {
   Typeface type=typeface();Font font=type.font().deriveFont(25f);
   if(doc==null||doc.caseId()==null||doc.revisionId()==null||doc.signatureId()==null||doc.reviewId()==null||doc.draftVersion()<0||doc.templateCode()==null||!doc.templateCode().matches("[A-Z][A-Z0-9_-]{0,63}")||doc.templateVersion()<1||!List.of("SYN-TEXT-1","SYN-STRUCTURED-2").contains(doc.schemaCode())||doc.dependencyToken()==null||!doc.dependencyToken().matches("[a-f0-9]{64}")||doc.fields().size()>6) throw new RenderException("REPORT_RENDER_INPUT_INVALID");
   var measure=new BufferedImage(1,1,BufferedImage.TYPE_BYTE_GRAY);var mg=measure.createGraphics();mg.setFont(font);FontMetrics metrics=mg.getFontMetrics();var lines=new ArrayList<String>();
   try {
    var sections=new ArrayList<Section>();sections.add(new Section("病例",doc.caseId().toString()));sections.add(new Section("冻结报告修订",doc.revisionId()+" / v"+doc.draftVersion()));sections.add(new Section("模拟事件 / 原复核",doc.signatureId()+"\n"+doc.reviewId()));sections.add(new Section("模板 / 字段模式",doc.templateCode()+" v"+doc.templateVersion()+" / "+doc.schemaCode()));sections.add(new Section("冻结QC与复核依赖摘要",doc.dependencyToken()));sections.addAll(doc.fields());
    int total=0;
    for(var section:sections) {
     if(section.name()==null||section.text()==null||section.name().length()>64||section.text().length()>4000) throw new RenderException("REPORT_RENDER_INPUT_INVALID");
     String text=section.name()+"：\n"+section.text().replace("\r\n","\n").replace('\r','\n').replace("\t","  ");total+=text.length();if(total>24000) throw new RenderException("REPORT_RENDER_LIMIT");
     for(String paragraph:text.split("\n",-1)) {
      var line=new StringBuilder();int width=0;
      for(int offset=0;offset<paragraph.length();) { check(deadline);int cp=paragraph.codePointAt(offset);offset+=Character.charCount(cp);if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT||!font.canDisplay(cp)) throw new RenderException("REPORT_GLYPH_UNSUPPORTED");int size=metrics.charWidth(cp);if(width+size>WIDTH-LEFT*2&&line.length()>0){lines.add(line.toString());line.setLength(0);width=0;}line.appendCodePoint(cp);width+=size; }
      lines.add(line.toString());if(lines.size()>LINES*MAX_PAGES) throw new RenderException("REPORT_RENDER_LIMIT");
     }
     lines.add("");
    }
   } finally { mg.dispose(); }
   int pages=(lines.size()+LINES-1)/LINES;if(pages<1||pages>MAX_PAGES) throw new RenderException("REPORT_RENDER_LIMIT");
   var objects=new ArrayList<byte[]>();objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));var kids=new StringBuilder();for(int i=0;i<pages;i++)kids.append(3+i*3).append(" 0 R ");objects.add(ascii("<< /Type /Pages /Count "+pages+" /Kids ["+kids+"] >>"));
   int compressedTotal=0;
   for(int page=0;page<pages;page++) {
    check(deadline);var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_BYTE_GRAY);var g=image.createGraphics();
    try {
     g.setColor(Color.WHITE);g.fillRect(0,0,WIDTH,HEIGHT);g.setColor(Color.BLACK);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
     g.setFont(type.font().deriveFont(32f));g.drawString("合成演示／非临床报告",LEFT,75);g.setFont(type.font().deriveFont(18f));g.drawString("固定历史快照 · 无临床、CA或法律签署效力 · 不代表当前QC就绪",LEFT,112);g.drawLine(LEFT,135,WIDTH-LEFT,135);
     g.setFont(font);for(int row=0;row<LINES&&page*LINES+row<lines.size();row++){check(deadline);g.drawString(lines.get(page*LINES+row),LEFT,TOP+row*LINE);}
     g.setFont(type.font().deriveFont(16f));g.drawLine(LEFT,1650,WIDTH-LEFT,1650);g.drawString("合成演示／非临床报告 · 第 "+(page+1)+" / "+pages+" 页",LEFT,1683);g.drawString("修订 "+doc.revisionId()+" · 模板版本 "+doc.templateVersion(),LEFT,1715);
    } finally { g.dispose(); }
    var compressed=new ByteArrayOutputStream();try(var z=new DeflaterOutputStream(compressed)){z.write(((DataBufferByte)image.getRaster().getDataBuffer()).getData());}byte[] pixels=compressed.toByteArray();compressedTotal+=pixels.length;if(compressedTotal>MAX_BYTES) throw new RenderException("REPORT_RENDER_LIMIT");
    int number=3+page*3;objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im "+(number+2)+" 0 R >> >> /Contents "+(number+1)+" 0 R >>"));
    objects.add(stream("",ascii("q 595 0 0 842 0 0 cm /Im Do Q")));objects.add(stream("/Type /XObject /Subtype /Image /Width "+WIDTH+" /Height "+HEIGHT+" /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",pixels));
   }
   var pdf=new ByteArrayOutputStream();pdf.write(ascii("%PDF-1.4\n%PIS-SYNTHETIC\n"));var offsets=new ArrayList<Integer>();offsets.add(0);
   for(int i=0;i<objects.size();i++){offsets.add(pdf.size());pdf.write(ascii((i+1)+" 0 obj\n"));pdf.write(objects.get(i));pdf.write(ascii("\nendobj\n"));}
   int xref=pdf.size();pdf.write(ascii("xref\n0 "+offsets.size()+"\n0000000000 65535 f \n"));for(int i=1;i<offsets.size();i++)pdf.write(ascii(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets.get(i))));pdf.write(ascii("trailer\n<< /Size "+offsets.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n"));check(deadline);
   byte[] bytes=pdf.toByteArray();if(bytes.length>MAX_BYTES) throw new RenderException("REPORT_RENDER_LIMIT");return new Rendered(bytes,pages,sha256(bytes),type.hash());
  } catch(RenderException e) { throw e; } catch(java.io.IOException e) { throw new RenderException("REPORT_RENDER_FAILED"); } finally { SLOTS.release(); }
 }
 private static void check(long deadline) { if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted()) throw new RenderException("REPORT_RENDER_LIMIT"); }
 private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
 private static byte[] stream(String attributes,byte[] bytes) throws java.io.IOException { var out=new ByteArrayOutputStream();out.write(ascii("<< "+attributes+" /Length "+bytes.length+" >>\nstream\n"));out.write(bytes);out.write(ascii("\nendstream"));return out.toByteArray(); }
 public static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
 public static final String FONT_SHA256="56f62f6e18eabb294a0598bd0fadaed372541189ce03ccbdce3b21cb1d3ebc5b";
}
