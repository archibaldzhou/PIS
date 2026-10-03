package com.pis.viewer;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SyntheticPyramidTest {
 private byte[] source(int width,int height){UUID id=UUID.randomUUID();return SyntheticPyramid.fixture(id,id,id,com.pis.label.LabelBarcode.create(id),width,height,0);}
 @Test void realPngPyramidHasExactCoordinatesPixelsAndDeterministicHashes()throws Exception{var bytes=source(512,384);var p=SyntheticPyramid.generate(bytes);assertThat(p.maxLevel()).isEqualTo(9);assertThat(p.tiles()).hasSize(24);assertThat(SyntheticPyramid.generate(bytes).digest()).isEqualTo(p.digest());for(var t:p.tiles()){var png=p.bytes().get(SyntheticPyramid.key(t.level(),t.x(),t.y()));assertThat(Arrays.copyOf(png,8)).containsExactly((byte)137,(byte)80,(byte)78,(byte)71,(byte)13,(byte)10,(byte)26,(byte)10);assertThat(com.pis.scan.ScanFormat.sha(png)).isEqualTo(t.sha256());var image=ImageIO.read(new ByteArrayInputStream(png));assertThat(image.getWidth()).isEqualTo(t.width());assertThat(image.getHeight()).isEqualTo(t.height());image.flush();}var full=ImageIO.read(new ByteArrayInputStream(p.bytes().get("9/0/0")));assertThat(full.getRGB(32,32)).isNotEqualTo(full.getRGB(0,0));}
 @Test void unknownHeaderTruncatedOversizedAndInterruptedInputsFailClosed(){var bytes=source(128,128);assertThatThrownBy(()->SyntheticPyramid.generate(Arrays.copyOf(bytes,bytes.length-1))).isInstanceOf(IllegalArgumentException.class).hasMessage("VIEWER_SIZE");assertThatThrownBy(()->source(513,1)).isInstanceOf(IllegalArgumentException.class);UUID id=UUID.randomUUID();assertThatThrownBy(()->SyntheticPyramid.generate(com.pis.scan.ScanFormat.fixture(id,id,id,com.pis.label.LabelBarcode.create(id),32,32))).hasMessage("VIEWER_UNSUPPORTED");Thread.currentThread().interrupt();try{assertThatThrownBy(()->SyntheticPyramid.generate(bytes)).hasMessage("VIEWER_TIMEOUT");}finally{Thread.interrupted();}}
 @Test void nonPowerOfTwoEdgesStayInsideActualDimensions(){var p=SyntheticPyramid.generate(source(257,129));var edge=p.tiles().stream().filter(t->t.level()==9&&t.x()==2&&t.y()==1).findFirst().orElseThrow();assertThat(edge.width()).isEqualTo(1);assertThat(edge.height()).isEqualTo(1);}
}
