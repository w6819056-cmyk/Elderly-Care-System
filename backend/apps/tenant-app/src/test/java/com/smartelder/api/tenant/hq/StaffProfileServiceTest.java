package com.smartelder.api.tenant.hq;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;
class StaffProfileServiceTest {
 private final StaffProfileService service=new StaffProfileService(null,"./target/test-avatars");
 @Test void rejectsDisguisedOrOversizedUploads(){assertThatThrownBy(()->service.validateAvatar("data:image/png;base64,SGVsbG8=" )).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.validateAvatar("data:image/svg+xml;base64,AAAA")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.validateAvatar("x".repeat(2800001))).isInstanceOf(IllegalArgumentException.class);}
 @Test void normalizesPngToSquareWithoutMetadata() throws Exception {var source=new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(source,"png",out);byte[] result=service.validateAvatar("data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray()));var decoded=ImageIO.read(new ByteArrayInputStream(result));assertThat(decoded.getWidth()).isEqualTo(256);assertThat(decoded.getHeight()).isEqualTo(256);}
 @Test void acceptsOmittedAvatar(){assertThat(service.validateAvatar(null)).isNull();}
}
