package com.pis.grossing;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SyntheticPhotoStoreTest {
    @Test void acceptsOnlyTheBoundedBundledObjectAndDoesNotParseArbitraryPhotos() {
        var store=new SyntheticPhotoStore(); var object=store.sample(); var bytes=store.read(object.key(),object.sha256());
        assertThat(bytes).hasSize(656); assertThat(object.width()).isEqualTo(256); assertThat(object.height()).isEqualTo(160);
        assertThat(store.validateSynthetic(Base64.getEncoder().encodeToString(bytes))).isEqualTo(object);
        bytes[0]=0;
        for(String bad:java.util.List.of(Base64.getEncoder().encodeToString(bytes),"<svg onload='x'/>","data:image/png;base64,AAAA","!",Base64.getEncoder().encodeToString(new byte[17000])))
            assertThatThrownBy(()->store.validateSynthetic(bad)).isInstanceOf(com.pis.api.ApiException.class);
        assertThatThrownBy(()->store.read("../../secret",object.sha256())).isInstanceOf(com.pis.api.ApiException.class);
        assertThatThrownBy(()->store.read(object.key(),"0".repeat(64))).isInstanceOf(com.pis.api.ApiException.class);
        assertThat(store.read(object.key(),object.sha256())[0]).isEqualTo((byte)0x89);
    }
}
