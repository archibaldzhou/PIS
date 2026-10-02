package com.pis.label;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LabelBarcodeTest {
    @Test void fixedVectorsChecksumAndMalformedInputs() {
        String zero="S"+"0".repeat(32)+"S";
        assertThat(LabelBarcode.create(new UUID(0,0))).isEqualTo(zero);
        assertThat(LabelBarcode.create(new UUID(0,1))).isEqualTo("S"+"0".repeat(31)+"1T");
        assertThat(LabelBarcode.valid(zero)).isTrue();
        for(String bad:java.util.List.of(zero+"\n",zero.toLowerCase(),zero.substring(0,33)+"T","*"+zero+"*",zero+" ","<script>")) assertThat(LabelBarcode.valid(bad)).isFalse();
        assertThat(LabelBarcode.valid(null)).isFalse();
    }
}
