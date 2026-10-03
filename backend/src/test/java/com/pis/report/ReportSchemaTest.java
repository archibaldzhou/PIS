package com.pis.report;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class ReportSchemaTest {
 private final JsonMapper json=JsonMapper.builder().build();
 @Test void validatesExactTypesAndVersionedClosedFieldsWithoutCoercion() {
  String text="\"gross\":\"\",\"microscopy\":\"Synthetic manual\",\"diagnosis\":\"\",\"notes\":\"\"";
  ReportSchema.validate("SYN-TEXT-1",json.readTree("{"+text+"}"));
  ReportSchema.validate("SYN-STRUCTURED-2",json.readTree("{"+text+",\"sampleCount\":0,\"manualChecked\":false}"));
  for(String extra:java.util.List.of("\"sampleCount\":\"1\",\"manualChecked\":true","\"sampleCount\":1.5,\"manualChecked\":true","\"sampleCount\":1001,\"manualChecked\":true","\"sampleCount\":1,\"manualChecked\":\"false\"","\"sampleCount\":null,\"manualChecked\":false"))
   assertThatThrownBy(()->ReportSchema.validate("SYN-STRUCTURED-2",json.readTree("{"+text+","+extra+"}"))).isInstanceOf(com.pis.api.ApiException.class);
  for(String invalid:java.util.List.of("{}","[]","{"+text+",\"unknown\":1}","{"+text.replace("\"gross\":\"\"","\"gross\":12")+"}")) assertThatThrownBy(()->ReportSchema.validate("SYN-TEXT-1",json.readTree(invalid))).isInstanceOf(com.pis.api.ApiException.class);
  assertThatThrownBy(()->ReportSchema.validate("UNKNOWN",json.readTree("{"+text+"}"))).isInstanceOf(com.pis.api.ApiException.class);
  assertThatThrownBy(()->ReportSchema.validate("SYN-TEXT-1",json.readTree("{"+text.replace("Synthetic manual","x".repeat(4001))+"}"))).isInstanceOf(com.pis.api.ApiException.class);
 }
}
