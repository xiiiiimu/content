package io.github.xiiiiimu.content.domain;

public interface RelaySender {
    record Receipt(String status, String brokerMessageId) {
        public boolean acknowledged() { return "SEND_OK".equals(status); }
    }
    Receipt send(RelayLease lease) throws Exception;
}
