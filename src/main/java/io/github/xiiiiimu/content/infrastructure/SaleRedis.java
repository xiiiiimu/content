package io.github.xiiiiimu.content.infrastructure;

/** Compatibility facade; active writes use only the fenced, cross-campaign v2 ingress. */
public final class SaleRedis {
 private final SaleEntryRedis entry;
 public SaleRedis(FeedRedis redis){this.entry=new SaleEntryRedis(redis);}
 public SaleEntryRedis entry(){return entry;}
 public void pauseGateway(boolean paused){entry.manualPause(paused);}
 public boolean gatewayPaused(){return entry.paused();}
 public Long remainingStock(long campaignId){return entry.remaining(campaignId);}
}
