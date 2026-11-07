package io.github.xiiiiimu.content.worker;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.RelaySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OutboxRelay implements RelayRunner {
    private static final Logger LOG=LoggerFactory.getLogger(OutboxRelay.class);
    private final RelayStore store;
    private final RelaySender sender;
    private final RelayObserver observer;
    private final RelaySettings settings;
    public OutboxRelay(RelayStore store,RelaySender sender,RelayObserver observer,RelaySettings settings){this.store=store;this.sender=sender;this.observer=observer;this.settings=settings;}
    @Override public int tick() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Relay must run outside caller transactions");
        int processed=store.reapExhausted();
        for(int i=0;i<settings.batchSize();i++) {
            var claimed=store.claim();if(claimed.isEmpty())break;
            var lease=claimed.get();processed++;
            LOG.info("Relay claimed eventId={} token={} attempt={}",lease.eventId(),lease.token(),lease.attempt());
            observer.afterClaim(lease);
            try {
                var receipt=sender.send(lease);
                if(!receipt.acknowledged())throw new IllegalStateException("SEND_STATUS="+receipt.status());
                LOG.info("Relay SEND_OK eventId={} token={} mqMsgId={}",lease.eventId(),lease.token(),receipt.brokerMessageId());
                observer.afterAck(lease);
                if(!store.sent(lease))LOG.warn("Relay stale ACK eventId={} token={}",lease.eventId(),lease.token());
            } catch(Exception failure) {
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                StringBuilder cause=new StringBuilder();
                for(Throwable e=failure;e!=null;e=e.getCause())cause.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("; ");
                LOG.warn("Relay failed eventId={} token={} cause={}",lease.eventId(),lease.token(),cause);
                try {store.failed(lease,cause.toString());}
                catch(Exception databaseFailure){LOG.error("Relay failure state unavailable; lease recovery required for eventId={}",lease.eventId(),databaseFailure);}
                if(Thread.currentThread().isInterrupted())break;
            }
        }
        return processed;
    }
}
