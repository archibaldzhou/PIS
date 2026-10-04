package com.pis.integration;
import java.io.*;import java.lang.reflect.Type;import org.springframework.core.MethodParameter;import org.springframework.http.*;import org.springframework.http.converter.HttpMessageConverter;import org.springframework.web.bind.annotation.ControllerAdvice;import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;import com.pis.api.ApiException;
@ControllerAdvice(assignableTypes={HospitalAdapterController.class,LocalEmrAdapterController.class})
public class AdapterBodyLimit extends RequestBodyAdviceAdapter {
 @Override public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){return true;}
 @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c)throws IOException {
  byte[] bytes=input.getBody().readNBytes(8193);if(bytes.length>8192)throw new ApiException(HttpStatus.valueOf(413),"ADAPTER_SIZE","Synthetic message exceeds bounded input");
  return new HttpInputMessage(){public HttpHeaders getHeaders(){return input.getHeaders();}public InputStream getBody(){return new ByteArrayInputStream(bytes);}};
 }
}
