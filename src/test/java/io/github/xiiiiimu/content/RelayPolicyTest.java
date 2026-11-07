package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.RelaySender;
import io.github.xiiiiimu.content.infrastructure.RelaySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RelayPolicyTest {
    @Test void exponentialBackoffCapsWithoutOverflow() {
        var policy=new RelaySettings("localhost:9876","topic",30000,3000,5,1000,300000,10);
        assertThat(policy.backoff(1)).isEqualTo(1000);
        assertThat(policy.backoff(5)).isEqualTo(16000);
        assertThat(policy.backoff(10)).isEqualTo(300000);
        assertThat(policy.backoff(Integer.MAX_VALUE)).isEqualTo(300000);
        assertThatThrownBy(() -> policy.backoff(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"FLUSH_DISK_TIMEOUT","FLUSH_SLAVE_TIMEOUT","SLAVE_NOT_AVAILABLE","NO_RESULT","send_ok",""})
    void onlyExactSendOkAcknowledges(String status) {
        assertThat(new RelaySender.Receipt(status,"id").acknowledged()).isFalse();
        assertThat(new RelaySender.Receipt("SEND_OK","id").acknowledged()).isTrue();
    }
    @Test void leaseMustExceedSendTimeout() {
        assertThatThrownBy(() -> new RelaySettings("localhost:9876","topic",3000,3000,5,1000,300000,10)).isInstanceOf(IllegalArgumentException.class);
    }
}
