package com.pis.grossing;
import com.pis.api.ApiException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
@Component
public final class SyntheticPhotoStore implements PhotoStore {
    private static final String KEY="grossing-assets/synthetic-v1.png";
    private static final String SHA="adaacd24ed0218efbde244a9a0c314cf26b8f27f612146bd9d164971fcb92623";
    private final byte[] content;
    public SyntheticPhotoStore() {
        try(var input=getClass().getClassLoader().getResourceAsStream(KEY)) {
            if(input==null) throw new IllegalStateException("Synthetic photo resource missing");
            content=input.readAllBytes();
            if(content.length!=656||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(SHA)) throw new IllegalStateException("Synthetic photo resource checksum mismatch");
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("Synthetic photo unavailable",error); }
    }
    public ObjectInfo validateSynthetic(String base64) {
        if(base64==null||base64.length()>22000) throw rejected();
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(base64); } catch(IllegalArgumentException e) { throw rejected(); }
        if(bytes.length>16384||!MessageDigest.isEqual(bytes,content)) throw rejected();
        return sample();
    }
    public byte[] read(String key,String sha256) {
        if(!KEY.equals(key)||!SHA.equals(sha256)) throw rejected();
        return content.clone();
    }
    public ObjectInfo sample() { return new ObjectInfo(KEY,SHA,content.length,256,160); }
    private static ApiException rejected() { return new ApiException(HttpStatus.BAD_REQUEST,"GROSS_PHOTO_REJECTED","Only the bundled synthetic PNG is accepted"); }
}
