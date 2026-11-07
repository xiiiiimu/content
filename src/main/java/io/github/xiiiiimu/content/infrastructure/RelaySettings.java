package io.github.xiiiiimu.content.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("content.relay")
public record RelaySettings(@DefaultValue("127.0.0.1:29876") String namesrv,
                            @DefaultValue("content-post-published-v1") String topic,
                            @DefaultValue("30000") long leaseMillis,
                            @DefaultValue("3000") long sendTimeoutMillis,
                            @DefaultValue("5") int maxAttempts,
                            @DefaultValue("1000") long baseBackoffMillis,
                            @DefaultValue("300000") long maxBackoffMillis,
                            @DefaultValue("10") int batchSize) {
    public RelaySettings {
        if(namesrv==null || namesrv.isBlank() || topic==null || topic.isBlank()
                || sendTimeoutMillis<=0 || leaseMillis<=sendTimeoutMillis || leaseMillis>3600000
                || maxAttempts<=0 || maxAttempts>100 || baseBackoffMillis<=0
                || maxBackoffMillis<baseBackoffMillis || maxBackoffMillis>3600000 || batchSize<=0 || batchSize>100)
            throw new IllegalArgumentException("Invalid Relay configuration: require positive bounded values and lease > send timeout");
    }
    public long backoff(int attempt) {
        if(attempt<1)throw new IllegalArgumentException("Attempt starts at 1");
        long delay=baseBackoffMillis;
        for(int i=1;i<attempt && delay<maxBackoffMillis;i++)
            delay=delay>maxBackoffMillis/2 ? maxBackoffMillis : Math.min(maxBackoffMillis,delay*2);
        return delay;
    }
}
