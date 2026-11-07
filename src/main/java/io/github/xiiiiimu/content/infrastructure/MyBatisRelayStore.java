package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.*;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

/** Each operation commits independently, including when called from an unrelated transaction. */
@Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW)
public class MyBatisRelayStore implements RelayStore {
    private final RelayMapper mapper;
    private final RelaySettings settings;
    public MyBatisRelayStore(RelayMapper mapper,RelaySettings settings){this.mapper=mapper;this.settings=settings;}
    @Override public Optional<RelayLease> claim() {
        var row=mapper.candidate(settings.maxAttempts());
        if(row==null)return Optional.empty();
        if(mapper.claim(row.id(),row.token(),settings.maxAttempts(),settings.leaseMillis())!=1)
            throw new IllegalStateException("Locked Outbox claim changed unexpectedly");
        return Optional.of(new RelayLease(row.id(),row.payload(),Math.addExact(row.token(),1),Math.addExact(row.attempt(),1)));
    }
    @Override public boolean sent(RelayLease lease) {
        // Lock first: NOW in the following statement is evaluated after any lock wait.
        var row=mapper.lock(lease.eventId());
        return row!=null && mapper.sent(lease.eventId(),lease.token())==1;
    }
    @Override public boolean failed(RelayLease lease,String error) {
        var row=mapper.lock(lease.eventId());
        if(row==null || row.token()!=lease.token() || !row.status().equals("RUNNING"))return false;
        String detail=error==null ? "Unknown send failure" : error.substring(0,Math.min(error.length(),1024));
        return mapper.failed(row.id(),lease.token(),row.attempt()>=settings.maxAttempts()?"DEAD":"NEW",settings.backoff(row.attempt()),detail)==1;
    }
    @Override public int reapExhausted() {
        int changed=0;
        for(var row:mapper.exhausted(settings.maxAttempts()))changed+=mapper.dead(row.id(),row.token(),settings.maxAttempts());
        return changed;
    }
    @Override public boolean replay(long eventId,String operator,String reason) {
        if(eventId<=0 || operator==null || operator.isBlank() || operator.length()>256 || reason==null || reason.isBlank() || reason.length()>1024)
            throw new IllegalArgumentException("Replay requires event id, OS operator and a reason (max 1024 characters)");
        var row=mapper.lock(eventId);
        if(row==null || !row.status().equals("DEAD"))return false;
        long nextToken=Math.addExact(row.token(),1);
        if(mapper.replay(eventId,row.token())!=1)throw new IllegalStateException("Locked DEAD event changed");
        if(mapper.audit(eventId,operator,reason,row.token(),nextToken,row.attempt())!=1)throw new IllegalStateException("Missing replay audit");
        return true;
    }
}
