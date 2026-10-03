package com.pis.report;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SyntheticPdfTest {
 private SyntheticPdf.Document document(String text) { return new SyntheticPdf.Document(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),0,"SYN-REPORT",1,"SYN-TEXT-1","a".repeat(64),List.of(new SyntheticPdf.Section("人工合成诊断",text))); }
 @Test void producesDeterministicChinesePagedPdfAndDefensiveByteCopies() {
  var renderer=new SyntheticPdf();var d=document("合成演示中文分页验证，不包含真实患者资料。".repeat(150));var pdf=renderer.render(d);
  assertThat(pdf.pages()).isGreaterThan(1).isLessThanOrEqualTo(16);assertThat(pdf.bytes().length).isLessThanOrEqualTo(SyntheticPdf.MAX_BYTES);
  assertThat(new String(pdf.bytes(),0,8,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.4");assertThat(SyntheticPdf.sha256(pdf.bytes())).isEqualTo(pdf.sha256());assertThat(pdf.bytes()).isEqualTo(renderer.render(d).bytes());
  byte[] changed=pdf.bytes();changed[0]=0;assertThat(pdf.bytes()[0]).isEqualTo((byte)'%');
 }
 @Test void rejectsMissingGlyphControlCharacterLengthAndExcessiveNewlinePages() {
  var renderer=new SyntheticPdf();for(String text:List.of("x".repeat(4001),"\n".repeat(1000),"Synthetic\u0000text","Synthetic\u202Etext","Synthetic\uD83D\uDE00")) assertThatThrownBy(()->renderer.render(document(text))).isInstanceOf(SyntheticPdf.RenderException.class);
 }
 @Test void scriptAndUrlsAreOnlyRasterizedTextAndCannotBecomePdfActions() {
  var pdf=new SyntheticPdf().render(document("<script>合成</script> https://example.invalid/ ../../not-a-path"));String bytes=new String(pdf.bytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
  assertThat(bytes).doesNotContain("/JavaScript","/OpenAction","/Launch","/URI","/EmbeddedFile");assertThat(bytes).contains("/Subtype /Image","/DeviceGray","/Count 1");
 }
}
