package com.ply.exceptions.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

    @Bean
    public HmacVerifier hmacVerifier(WebhookProperties props) {
        return new HmacVerifier(props.maxClockSkewSeconds());
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
