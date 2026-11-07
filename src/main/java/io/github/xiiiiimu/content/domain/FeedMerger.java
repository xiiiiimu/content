package io.github.xiiiiimu.content.domain;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Internal continuation only: not an authenticated client cursor. */
public final class FeedMerger {
    public enum PageState { HAS_MORE, UNKNOWN, EXHAUSTED }
    public record Continuation(long snapshotTime, FeedCandidate globalBoundary, FeedCandidate consumedBoundary) {}
    /** exhausted proves this source's queried range ended normally, never a timeout or cache-readiness claim. */
    public record Batch(List<FeedCandidate> items, boolean exhausted) {
        public Batch { items=List.copyOf(items); }
    }
    public record Decision(FeedCandidate canonical, boolean visible) {}
    @FunctionalInterface public interface Source {
        Batch read(long snapshotTime, FeedCandidate exclusiveBoundary, int limit) throws Exception;
    }
    /** Must bind the reader context and check canonical tuple plus visibility/follow epoch. Missing posts are (null,false). */
    @FunctionalInterface public interface Authority {
        Decision check(FeedCandidate candidate) throws Exception;
    }
    public record Budget(int batchSize, int maxScanned, int maxReads, int maxChecks, Duration timeout) {
        public static Budget defaults() { return new Budget(32,256,64,256,Duration.ofSeconds(2)); }
    }
    public record Page(List<FeedCandidate> items, Continuation next, PageState pageState,
                       int scanned, int fetched, int sourceReads, int checks,
                       Map<String,FeedCandidate> sourcePositions) {
        public Page {
            items=List.copyOf(items);
            sourcePositions=Collections.unmodifiableMap(new LinkedHashMap<>(sourcePositions));
        }
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message,cause); }
    }
    private static final Comparator<FeedCandidate> ORDER=Comparator.comparingLong(FeedCandidate::publishedAtMs).thenComparingLong(FeedCandidate::postId);
    private final ExecutorService executor;
    /** Caller owns a bounded executor and source connection lifecycles. No runtime worker is registered. */
    public FeedMerger(ExecutorService executor) {this.executor=Objects.requireNonNull(executor);}
    public Page read(Map<String,Source> sources, Authority authority, Continuation input, int pageSize, Budget budget) {
        validate(sources,authority,input,pageSize,budget);
        return new Scan(new LinkedHashMap<>(sources),authority,input,pageSize,budget).run();
    }
    private static FeedCandidate min(FeedCandidate a,FeedCandidate b) {
        return a==null?b:b==null?a:ORDER.compare(a,b)<=0?a:b;
    }
    private static void validate(Map<String,Source> sources,Authority authority,Continuation input,int pageSize,Budget b) {
        if(sources==null || sources.isEmpty() || sources.size()>8 || authority==null || input==null || b==null)
            throw new IllegalArgumentException("One to eight sources, authority, continuation and budget are required");
        if(sources.entrySet().stream().anyMatch(e -> e.getKey()==null || e.getKey().isBlank() || e.getValue()==null))
            throw new IllegalArgumentException("Source names and implementations are required");
        if(pageSize<1 || pageSize>100 || b.batchSize()<1 || b.batchSize()>32 || b.maxScanned()<1 || b.maxScanned()>256
                || b.maxReads()<sources.size() || b.maxReads()>64 || b.maxChecks()<1 || b.maxChecks()>256
                || b.timeout()==null || b.timeout().isNegative() || b.timeout().isZero() || b.timeout().compareTo(Duration.ofSeconds(2))>0)
            throw new IllegalArgumentException("Invalid bounded merge configuration");
        FeedMemberCodec.encode(new FeedCandidate(input.snapshotTime(),1));
        if(input.globalBoundary()!=null)FeedMemberCodec.encode(input.globalBoundary());
        if(input.consumedBoundary()!=null)FeedMemberCodec.encode(input.consumedBoundary());
        if((input.globalBoundary()!=null && (input.consumedBoundary()==null || ORDER.compare(input.consumedBoundary(),input.globalBoundary())>0))
                || (input.globalBoundary()!=null && input.globalBoundary().publishedAtMs()>input.snapshotTime())
                || (input.consumedBoundary()!=null && input.consumedBoundary().publishedAtMs()>input.snapshotTime()))
            throw new IllegalArgumentException("Continuation boundaries violate snapshot/consumption order");
    }
    private <T> T call(Callable<T> operation,Duration timeout,String stage) {
        Future<T> future=null;
        try {
            future=executor.submit(operation);
            return future.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
        }catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new Unavailable(stage+" interrupted; retry original continuation",e);
        }catch(ExecutionException | TimeoutException | RejectedExecutionException e) {
            throw new Unavailable(stage+" unavailable; retry original continuation",e);
        }finally {if(future!=null && !future.isDone())future.cancel(true);}
    }
    private static final class Position {
        final Source source;
        final ArrayDeque<FeedCandidate> pending=new ArrayDeque<>();
        FeedCandidate consumed;
        boolean exhausted;
        Position(Source source,FeedCandidate initial) {this.source=source;consumed=initial;}
    }
    /** All committed scan state is request-local; timed-out tasks cannot publish positions. */
    private final class Scan {
        final Map<String,Position> positions=new LinkedHashMap<>();
        final Map<Long,FeedCandidate> observed=new HashMap<>(); // Bounded by fetched entries in this request, never a cursor field.
        final List<FeedCandidate> items=new ArrayList<>();
        final Authority authority;final Continuation input;final int pageSize;final Budget budget;
        FeedCandidate global,consumed;int scanned,fetched,reads,checks;
        Scan(Map<String,Source> sources,Authority authority,Continuation input,int pageSize,Budget budget) {
            this.authority=authority;this.input=input;this.pageSize=pageSize;this.budget=budget;
            global=input.globalBoundary();consumed=input.consumedBoundary();
            sources.forEach((name,source) -> positions.put(name,new Position(source,min(global,consumed))));
        }
        Page run() {
            while(true) {
                boolean confirmed=confirmHeads();
                boolean known=positions.values().stream().anyMatch(p -> !p.pending.isEmpty());
                boolean done=positions.values().stream().allMatch(p -> p.exhausted && p.pending.isEmpty());
                if(done)return result(PageState.EXHAUSTED);
                if(items.size()==pageSize || scanned==budget.maxScanned() || checks==budget.maxChecks() || !confirmed) {
                    if(scanned==0 && items.isEmpty())throw new Unavailable("Cannot safely advance; retry original continuation");
                    return result(known?PageState.HAS_MORE:PageState.UNKNOWN);
                }
                FeedCandidate greatest=positions.values().stream().filter(p -> !p.pending.isEmpty()).map(p -> p.pending.peekFirst()).max(ORDER).orElseThrow();
                checks++;
                Decision verdict=call(() -> authority.check(greatest),budget.timeout(),"Authority check");
                if(verdict==null || (verdict.visible() && verdict.canonical()==null)
                        || (verdict.canonical()!=null && !greatest.equals(verdict.canonical())))
                    throw new Unavailable("Authority canonical tuple mismatch");
                // Commit only after all heads and the authority verdict are known. Consume every equal head together.
                for(Position p:positions.values()) {
                    while(greatest.equals(p.pending.peekFirst())) {p.pending.removeFirst();p.consumed=greatest;}
                }
                consumed=greatest;scanned++;
                if(verdict.visible()) {items.add(greatest);global=greatest;}
            }
        }
        boolean confirmHeads() {
            boolean confirmed=true;
            for(Position p:positions.values()) {
                if(!p.pending.isEmpty() || p.exhausted)continue;
                if(reads==budget.maxReads()) {confirmed=false;continue;}
                FeedCandidate upper=min(min(global,consumed),p.consumed);
                reads++;
                Batch batch=call(() -> p.source.read(input.snapshotTime(),upper,budget.batchSize()),budget.timeout(),"Source read");
                if(batch==null || batch.items().size()>budget.batchSize() || (batch.items().isEmpty() && !batch.exhausted()))
                    throw new Unavailable("Source returned an invalid or non-progressing batch");
                FeedCandidate previous=null;
                for(FeedCandidate value:batch.items()) {
                    try {FeedMemberCodec.encode(value);}catch(IllegalArgumentException e) {throw new Unavailable("Invalid source tuple",e);}
                    if(value.publishedAtMs()>input.snapshotTime() || (upper!=null && ORDER.compare(value,upper)>=0)
                            || (previous!=null && ORDER.compare(value,previous)>0))
                        throw new Unavailable("Source violated snapshot, exclusive boundary or descending order");
                    FeedCandidate old=observed.putIfAbsent(value.postId(),value);
                    if(old!=null && !old.equals(value))throw new Unavailable("One post has conflicting immutable tuples");
                    previous=value;
                }
                fetched+=batch.items().size();p.pending.addAll(batch.items());p.exhausted=batch.exhausted();
            }
            return confirmed;
        }
        Page result(PageState state) {
            var consumedPositions=new LinkedHashMap<String,FeedCandidate>();positions.forEach((name,p) -> consumedPositions.put(name,p.consumed));
            return new Page(items,state==PageState.EXHAUSTED?null:new Continuation(input.snapshotTime(),global,consumed),state,
                    scanned,fetched,reads,checks,consumedPositions);
        }
    }
}
