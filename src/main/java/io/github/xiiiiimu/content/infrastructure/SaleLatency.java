package io.github.xiiiiimu.content.infrastructure;
import jdk.jfr.*;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.UUID;
/** Diagnostic JFR events only: no bodies, authorization headers, SQL text or parameters. */
public final class SaleLatency {
 private SaleLatency(){}
 private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
 private static volatile boolean acceptCapture;
 private static final Scope NOOP=new Scope(()->{});
 private static final class Context {
  final String id,kind;final long campaign;final int port;
  String clientKey,reservationId;int status;
  Context(String id,String kind,long campaign,int port){this.id=id;this.kind=kind;this.campaign=campaign;this.port=port;}
 }
 @Name("content.SalePhase") @Label("Sale phase") @StackTrace(false)
 public static class PhaseEvent extends Event { public String traceId,kind,phase;public long campaignId; }
 @Name("content.SaleRequest") @Label("Sale request") @StackTrace(false)
 public static class RequestEvent extends Event { public String traceId,kind,clientKey,reservationId;public long campaignId;public int remotePort,status; }
 @Name("content.SaleAccepted") @Label("Sale TCP accepted") @StackTrace(false)
 public static class AcceptedEvent extends Event { public int remotePort; }
 @Name("content.SaleBridgeBatch") @Label("Committed bridge batch") @StackTrace(false)
 public static class BridgeEvent extends Event {public String traceId,newReservationIds,reservationIds;public int readCount,normalCount,newCount;}
 public static void bridgeBatch(int read,java.util.List<String> normal,java.util.List<String> inserted){var c=CURRENT.get();if(c==null)return;var e=new BridgeEvent();if(!e.isEnabled())return;e.traceId=c.id;e.readCount=read;e.normalCount=normal.size();e.newCount=inserted.size();e.newReservationIds=String.join(",",inserted);e.reservationIds=String.join(",",normal);e.commit();}
 @Name("content.SaleDispatchBatch") @Label("Batch dispatch outcome") @StackTrace(false)
 public static class DispatchEvent extends Event {public String reservationIds;public int requested,idleSenders,claimed,sent,failed,budgetSkipped,confirmMissed,retryMissed;public long claimMillis,sendMillis,outcomeMillis;public boolean outcomePersisted;}
 public static void dispatchBatch(int requested,int idle,java.util.List<String> ids,int sent,int failed,int skipped,int confirmMissed,int retryMissed,long claimMillis,long sendMillis,long outcomeMillis,boolean persisted){
  var e=new DispatchEvent();if(!e.isEnabled())return;
  e.requested=requested;e.idleSenders=idle;e.claimed=ids.size();e.reservationIds=String.join(",",ids);e.sent=sent;e.failed=failed;e.budgetSkipped=skipped;
  e.confirmMissed=confirmMissed;e.retryMissed=retryMissed;e.claimMillis=claimMillis;e.sendMillis=sendMillis;e.outcomeMillis=outcomeMillis;e.outcomePersisted=persisted;e.commit();
 }
 public static final class Scope implements AutoCloseable {
  private final Runnable action;private boolean closed;
  private Scope(Runnable action){this.action=action;}
  @Override public void close(){if(!closed){closed=true;action.run();}}
 }
 public static boolean active(){return CURRENT.get()!=null;}
 public static void enableAcceptCapture(){acceptCapture=true;}
 public static Scope begin(String id,String kind,long campaign,int port){
  if(!new PhaseEvent().isEnabled())return NOOP;
  String safe=id!=null && id.matches("(jm-c[0-9]+-u[0-9]+|settle-[a-f0-9]{32})")?id:"diag-"+UUID.randomUUID().toString().replace("-","");
  Context previous=CURRENT.get(),context=new Context(safe,kind,campaign,port);
  CURRENT.set(context);var event=new RequestEvent();event.begin();
  return new Scope(()->{try{
   event.traceId=context.id;event.kind=context.kind;event.campaignId=context.campaign;
   event.remotePort=context.port;event.status=context.status;event.clientKey=context.clientKey;event.reservationId=context.reservationId;
   event.end();event.commit();
  }finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}});
 }
 public static Scope phase(String name){
  var context=CURRENT.get();if(context==null)return NOOP;
  var event=new PhaseEvent();if(!event.isEnabled())return NOOP;
  event.traceId=context.id;event.kind=context.kind;event.campaignId=context.campaign;
  event.phase=name.length()>256?name.substring(0,256):name;event.begin();
  return new Scope(()->{event.end();event.commit();});
 }
 public static void status(int status){var c=CURRENT.get();if(c!=null)c.status=status;}
 public static void correlate(String clientKey,String reservationId){
  var c=CURRENT.get();if(c==null)return;
  if(clientKey!=null && clientKey.matches("jm-c[0-9]+-u[0-9]+"))c.clientKey=clientKey;
  if(reservationId!=null && reservationId.matches("[a-f0-9]{32}"))c.reservationId=reservationId;
 }
 public static void accepted(SocketChannel socket){
  if(!acceptCapture)return;
  var event=new AcceptedEvent();if(!event.isEnabled())return;
  try{event.remotePort=((InetSocketAddress)socket.getRemoteAddress()).getPort();event.commit();}
  catch(Exception ignored){/* Diagnostics must not change accepted socket behavior. */}
 }
}
