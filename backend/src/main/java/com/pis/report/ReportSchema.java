package com.pis.report;
import com.pis.api.ApiException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
/** Closed, versioned synthetic schema. No coercion, generated diagnosis or default clinical values. */
public final class ReportSchema {
 private ReportSchema() { }
 public static void validate(String schema,JsonNode fields) {
  boolean structured=schema.equals("SYN-STRUCTURED-2");
  if(!structured&&!schema.equals("SYN-TEXT-1")) throw invalid();
  var text=Set.of("gross","microscopy","diagnosis","notes");
  var allowed=structured?Set.of("gross","microscopy","diagnosis","notes","sampleCount","manualChecked"):text;
  if(fields==null||!fields.isObject()||!new java.util.HashSet<>(fields.propertyNames()).equals(allowed)) throw invalid();
  if(fields.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32000) throw invalid();
  for(String name:text) { var v=fields.get(name); if(!v.isString()||v.stringValue().length()>4000) throw invalid(); }
  if(structured) {
   var count=fields.get("sampleCount");var flag=fields.get("manualChecked");
   if(!count.isIntegralNumber()||!count.canConvertToInt()||count.intValue()<0||count.intValue()>1000||!flag.isBoolean()) throw invalid();
  }
 }
 private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"REPORT_FIELDS_INVALID","Fields do not match the immutable template schema"); }
}
