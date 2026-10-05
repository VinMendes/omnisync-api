package com.puccampinas.omnisync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "marketplace.delivery")
public class MarketplaceDeliveryConfig {

    private boolean enabled = true;
    private Duration pollInterval = Duration.ofSeconds(1);
    private int batchSize = 50;
    private Duration leaseDuration = Duration.ofSeconds(60);
    private int maxAttempts = 8;
    private Duration initialBackoff = Duration.ofSeconds(5);
    private Duration maxBackoff = Duration.ofMinutes(15);
    private double jitterFactor = 0.20d;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
    public double getJitterFactor() { return jitterFactor; }
    public void setJitterFactor(double jitterFactor) { this.jitterFactor = jitterFactor; }
}
