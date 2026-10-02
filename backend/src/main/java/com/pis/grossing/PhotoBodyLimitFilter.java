package com.pis.grossing;
import com.pis.api.ApiProblems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** Bounds JSON before parsing, including chunked requests. Runs after the security chain. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class PhotoBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX=32768;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST")||!request.getServletPath().matches("/api/grossing/records/[^/]+/photos");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(request.getContentLengthLong()>MAX) { reject(response); return; }
        byte[] bytes=request.getInputStream().readNBytes(MAX+1);
        if(bytes.length>MAX) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private final ByteArrayInputStream input=new ByteArrayInputStream(bytes);
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b,int off,int len) { return input.read(b,off,len); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous gross photo endpoint"); }
                };
            }
        },response);
    }
    private static void reject(HttpServletResponse response) throws IOException { ApiProblems.write(response,413,"GROSS_PHOTO_TOO_LARGE","Synthetic photo request exceeds the size limit"); }
}
