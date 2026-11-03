package com.smartelder.platform;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(
        scanBasePackages = "com.smartelder",
        exclude = UserDetailsServiceAutoConfiguration.class
)
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer platformMoneyJsonCustomizer() {
        return builder -> builder.serializerByType(
                BigDecimal.class,
                ToStringSerializer.instance
        );
    }
}
