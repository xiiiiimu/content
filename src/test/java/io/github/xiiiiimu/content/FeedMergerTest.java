package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.domain.FeedMerger.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;

class FeedMergerTest {
    ExecutorService executor; FeedMerger merger;
    static final Comparator<FeedCandidate> ORDER=Comparator.comparingLong(FeedCandidate::publishedAtMs).thenComparingLong(FeedCandidate::postId);
    static FeedCandidate k(long id) { return new FeedCandidate(1000,id); }
    static final Authority VISIBLE=c -> new Decision(c,true);
    static Budget budget(int batch,int scan,int reads) { return new Budget(batch,scan,reads,256,Duration.ofSeconds(2)); }
    @BeforeEach void setup() {executor=Executors.newFixedThreadPool(8);merger=new FeedMerger(executor);}
    @AfterEach void close() {executor.shutdownNow();}
    static Source source(List<FeedCandidate> values) {
        return (snapshot,boundary,limit) -> {
            var eligible=values.stream().filter(c -> c.publishedAtMs()<=snapshot && (boundary==null || ORDER.compare(c,boundary)<0)).sorted(ORDER.reversed()).toList();
            return new Batch(eligible.stream().limit(limit).toList(),eligible.size()<=limit);
        };
    }
    static Source ids(long... ids) {return source(LongStream.of(ids).mapToObj(FeedMergerTest::k).toList());}
    static Continuation start() {return new Continuation(1000,null,null);}
    @Test void prefetchSurvivesRequestReconstructionAndEqualHeadsAreConsumedTogether() {
        var sources=Map.of("A",ids(100,80,60),"B",ids(90,80,70));
        var actual=new ArrayList<FeedCandidate>();Continuation next=start();int scanned=0;
        do {
            var page=merger.read(sources,VISIBLE,next,2,Budget.defaults());actual.addAll(page.items());scanned+=page.scanned();
            if(next.consumedBoundary()!=null && page.next()!=null)assertThat(ORDER.compare(page.next().consumedBoundary(),next.consumedBoundary())).isNegative();
            if(actual.size()==2) {
                assertThat(page.sourcePositions()).containsEntry("A",k(100)).containsEntry("B",k(90));
                assertThat(page.next().globalBoundary()).isEqualTo(k(90));
            }
            next=page.next();
        }while(next!=null);
        assertThat(actual).containsExactly(k(100),k(90),k(80),k(70),k(60));assertThat(scanned).isEqualTo(5);
    }
    @Test void continuousEmptyUnknownPagesAdvanceConsumedBoundaryWithoutMovingGlobalBoundary() {
        Source all=source(LongStream.rangeClosed(1,105).mapToObj(FeedMergerTest::k).toList());
        Authority visible=c -> new Decision(c,c.postId()<=5);Continuation next=start();int empty=0;var actual=new ArrayList<FeedCandidate>();
        for(int request=0;next!=null && request<30;request++) {
            var page=merger.read(Map.of("A",all),visible,next,5,budget(1,16,16));
            if(page.items().isEmpty()) {
                empty++;assertThat(page.pageState()).isEqualTo(PageState.UNKNOWN);assertThat(page.scanned()).isEqualTo(16);
                assertThat(page.next().globalBoundary()).isNull();
                if(next.consumedBoundary()!=null)assertThat(ORDER.compare(page.next().consumedBoundary(),next.consumedBoundary())).isNegative();
            }
            actual.addAll(page.items());next=page.next();
        }
        assertThat(empty).isEqualTo(6);assertThat(actual).containsExactly(k(5),k(4),k(3),k(2),k(1));assertThat(next).isNull();
    }
    @Test void unknownHeadBlocksConsumptionUntilAllSourcesConfirm() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var checks=new AtomicInteger();
        Source blocked=(s,b,n) -> {entered.countDown();assertThat(release.await(1,TimeUnit.SECONDS)).isTrue();return new Batch(List.of(k(100)),true);};
        var sources=new LinkedHashMap<String,Source>();sources.put("A",ids(90));sources.put("B",blocked);
        var request=CompletableFuture.supplyAsync(() -> merger.read(sources,c -> {checks.incrementAndGet();return new Decision(c,true);},start(),1,Budget.defaults()));
        try {assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(checks).hasValue(0);assertThat(request).isNotDone();}
        finally {release.countDown();}
        var page=request.get(2,TimeUnit.SECONDS);assertThat(page.items()).containsExactly(k(100));assertThat(page.sourcePositions().get("A")).isNull();
    }
    @Test void initialAndRefillExceptionsNeverBecomeExhaustionOrPartialSuccess() {
        Source broken=(s,b,n) -> {throw new IllegalStateException("source down");};
        assertThatThrownBy(() -> merger.read(Map.of("A",broken),VISIBLE,start(),2,Budget.defaults())).isInstanceOf(Unavailable.class);
        Source refill=(s,b,n) -> {if(b!=null)throw new IllegalStateException("refill down");return new Batch(List.of(k(10)),false);};
        assertThatThrownBy(() -> merger.read(Map.of("A",refill),VISIBLE,start(),2,Budget.defaults())).isInstanceOf(Unavailable.class);
    }
    @Test void sourceTimeoutIsAnErrorAndRetryUsesOriginalContinuation() {
        var gate=new CountDownLatch(1);
        var small=new Budget(1,10,10,10,Duration.ofMillis(40));
        Source blocked=(s,b,n) -> {gate.await();return new Batch(List.of(),true);};
        try {assertThatThrownBy(() -> merger.read(Map.of("A",blocked),VISIBLE,start(),2,small)).isInstanceOf(Unavailable.class);}
        finally {gate.countDown();}
        assertThat(merger.read(Map.of("A",ids(9,8)),VISIBLE,start(),2,Budget.defaults()).items()).containsExactly(k(9),k(8));
    }
    @Test void lateCandidatesRespectConsumedBoundaryAndSnapshot() {
        var values=new ArrayList<>(List.of(k(100),k(80),k(60)));var sources=Map.of("A",source(values));
        var first=merger.read(sources,VISIBLE,start(),1,Budget.defaults());
        values.addAll(List.of(k(110),k(100),k(90),new FeedCandidate(1001,1)));
        var later=merger.read(sources,VISIBLE,first.next(),100,Budget.defaults());
        assertThat(later.items()).containsExactly(k(90),k(80),k(60));
        assertThat(merger.read(sources,VISIBLE,start(),100,Budget.defaults()).items()).containsExactly(k(110),k(100),k(90),k(80),k(60));
    }
    @Test void statesAreCandidateKnowledgeNotPromisesOfVisibleItems() {
        var more=merger.read(Map.of("A",ids(3,2,1)),VISIBLE,start(),1,Budget.defaults());
        assertThat(more.pageState()).isEqualTo(PageState.HAS_MORE);assertThat(more.next()).isNotNull();
        var unknown=merger.read(Map.of("A",ids(3,2)),c -> new Decision(c,false),start(),1,budget(1,1,1));
        assertThat(unknown.items()).isEmpty();assertThat(unknown.pageState()).isEqualTo(PageState.UNKNOWN);assertThat(unknown.next().consumedBoundary()).isEqualTo(k(3));
        var done=merger.read(Map.of("A",ids(1)),VISIBLE,start(),1,Budget.defaults());
        assertThat(done.pageState()).isEqualTo(PageState.EXHAUSTED);assertThat(done.next()).isNull();
        assertThat(merger.read(Map.of("A",ids()),VISIBLE,start(),1,Budget.defaults()).pageState()).isEqualTo(PageState.EXHAUSTED);
    }
    @Test void allFilteredAndExactBudgetCanStillConfirmExhaustion() {
        var p=merger.read(Map.of("A",ids(3,2,1)),c -> new Decision(c,false),start(),1,budget(3,3,1));
        assertThat(p.items()).isEmpty();assertThat(p.scanned()).isEqualTo(3);assertThat(p.pageState()).isEqualTo(PageState.EXHAUSTED);
    }
    @Test void checksAndReadsAreBoundedAndPositionsOnlyAdvanceOnConsumption() {
        var calls=new ArrayList<FeedCandidate>();
        Source tracked=(s,b,n) -> {assertThat(n).isEqualTo(1);calls.add(b);return ids(9,8,7).read(s,b,n);};
        var p=merger.read(Map.of("A",tracked),VISIBLE,start(),100,new Budget(1,256,2,1,Duration.ofSeconds(2)));
        assertThat(p.items()).containsExactly(k(9));assertThat(p.checks()).isEqualTo(1);assertThat(p.sourceReads()).isEqualTo(2);
        assertThat(p.fetched()).isEqualTo(2);assertThat(p.sourcePositions()).containsEntry("A",k(9));assertThat(calls).containsExactly(null,k(9));
        assertThat(p.pageState()).isEqualTo(PageState.HAS_MORE);
    }
    @Test void insufficientReadBudgetBeforeUnknownHeadCannotConsumeSmallerKnownItem() {
        var sources=new LinkedHashMap<String,Source>();sources.put("A",ids(10,8));sources.put("B",ids(9,7));
        var p=merger.read(sources,VISIBLE,start(),100,budget(1,256,2));
        assertThat(p.items()).containsExactly(k(10));assertThat(p.pageState()).isEqualTo(PageState.HAS_MORE);
        assertThat(p.next().consumedBoundary()).isEqualTo(k(10));assertThat(p.sourcePositions().get("B")).isNull();
    }
    @Test void rejectsInvalidConfigurationBeforeReading() {
        var calls=new AtomicInteger();Source s=(t,b,n) -> {calls.incrementAndGet();return new Batch(List.of(),true);};
        for(var b:List.of(budget(0,1,1),budget(33,1,1),budget(1,0,1),budget(1,257,1),budget(1,1,0),budget(1,1,65),new Budget(1,1,1,0,Duration.ofSeconds(2)),new Budget(1,1,1,1,Duration.ZERO)))
            assertThatThrownBy(() -> merger.read(Map.of("A",s),VISIBLE,start(),1,b)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> merger.read(Map.of("A",s,"B",s),VISIBLE,start(),1,budget(1,1,1))).isInstanceOf(IllegalArgumentException.class);
        for(int size:new int[]{0,101})assertThatThrownBy(() -> merger.read(Map.of("A",s),VISIBLE,start(),size,Budget.defaults())).isInstanceOf(IllegalArgumentException.class);
        var tooMany=new HashMap<String,Source>();for(int i=0;i<9;i++)tooMany.put("s"+i,s);
        assertThatThrownBy(() -> merger.read(tooMany,VISIBLE,start(),1,Budget.defaults())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> merger.read(Map.of(),VISIBLE,start(),1,Budget.defaults())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void invalidBatchesAndConflictingCanonicalIdentityAreErrors() {
        var bad=List.<Source>of((s,b,n) -> new Batch(List.of(k(1),k(2)),true),
                (s,b,n) -> new Batch(List.of(k(1),new FeedCandidate(999,1)),true),
                (s,b,n) -> new Batch(List.of(),false), (s,b,n) -> new Batch(List.of(new FeedCandidate(1001,1)),true),
                (s,b,n) -> new Batch(List.of(k(0)),true), (s,b,n) -> null);
        for(var s:bad)assertThatThrownBy(() -> merger.read(Map.of("A",s),VISIBLE,start(),2,Budget.defaults())).isInstanceOf(Unavailable.class);
        assertThatThrownBy(() -> merger.read(Map.of("A",ids(1)),c -> new Decision(new FeedCandidate(999,1),true),start(),2,Budget.defaults())).isInstanceOf(Unavailable.class);
        Source oversize=(s,b,n) -> new Batch(List.of(k(3),k(2)),true);
        assertThatThrownBy(() -> merger.read(Map.of("A",oversize),VISIBLE,start(),2,budget(1,2,2))).isInstanceOf(Unavailable.class);
    }
    @Test void authorityFailureDiscardsRequestAndCannotCommitUnprocessedPositions() {
        Authority fail=c -> {if(c.postId()==8)throw new IllegalStateException("authority unavailable");return new Decision(c,true);};
        assertThatThrownBy(() -> merger.read(Map.of("A",ids(9,8,7)),fail,start(),3,Budget.defaults())).isInstanceOf(Unavailable.class);
        assertThat(merger.read(Map.of("A",ids(9,8,7)),VISIBLE,start(),3,Budget.defaults()).items()).containsExactly(k(9),k(8),k(7));
    }
    @Test void absentAndFollowEpochRejectedCandidatesAreFilteredWithoutLosingFollowingItems() {
        var p=merger.read(Map.of("A",ids(4,3,2,1)),c -> c.postId()==4?new Decision(null,false):new Decision(c,c.postId()!=3),start(),2,Budget.defaults());
        assertThat(p.items()).containsExactly(k(2),k(1));assertThat(p.scanned()).isEqualTo(4);
    }
    @Test void randomizedFixedSourcesMatchIndependentNumericOrderAcrossRequests() {
        for(int seed=0;seed<12;seed++) {
            Random r=new Random(seed);var a=new ArrayList<FeedCandidate>();var b=new ArrayList<FeedCandidate>();
            for(long i=1;i<=90;i++) {var c=new FeedCandidate(998+r.nextInt(3),i);a.add(c);if(r.nextBoolean())b.add(c);}
            Collections.shuffle(a,r);Collections.shuffle(b,r);
            var expected=a.stream().filter(c -> c.postId()%4!=0).sorted(ORDER.reversed()).toList();var actual=new ArrayList<FeedCandidate>();
            Continuation next=start();int requests=0;
            do {var p=merger.read(Map.of("A",source(a),"B",source(b)),c -> new Decision(c,c.postId()%4!=0),next,1+seed%7,budget(1+seed%5,3+seed%8,64));
                assertThat(p.scanned()).isLessThanOrEqualTo(3+seed%8);actual.addAll(p.items());next=p.next();assertThat(++requests).isLessThan(100);
            }while(next!=null);
            assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        }
    }
    @Test void extremeTuplesAndInputSharedBoundariesAreRespected() {
        var max=new FeedCandidate(FeedMemberCodec.MAX_TIME_MS,Long.MAX_VALUE);var min=new FeedCandidate(0,1);
        var sources=Map.of("A",source(List.of(max,min)));
        assertThat(merger.read(sources,VISIBLE,new Continuation(FeedMemberCodec.MAX_TIME_MS,null,null),2,Budget.defaults()).items()).containsExactly(max,min);
        assertThat(merger.read(Map.of("A",ids(10,9,8,7)),VISIBLE,new Continuation(1000,k(10),k(8)),2,Budget.defaults()).items()).containsExactly(k(7));
    }
}
