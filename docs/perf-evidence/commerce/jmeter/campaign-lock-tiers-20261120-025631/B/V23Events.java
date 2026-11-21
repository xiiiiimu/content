import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.io.*;
/** Streaming, whitelisted diagnostic export. No environment, credentials or SQL parameters. */
public class V23Events {
  public static void main(String[] args) throws Exception {
    try(var file=new RecordingFile(Path.of(args[0]));var out=Files.newBufferedWriter(Path.of(args[1]));var batches=Files.newBufferedWriter(Path.of(args[1]+".batches.tsv"));var dispatch=Files.newBufferedWriter(Path.of(args[1]+".dispatch.tsv"))) {
      dispatch.write("startMs\tendMs\trequested\tidleSenders\tclaimed\tsent\tfailed\tbudgetSkipped\tconfirmMissed\tretryMissed\tclaimMillis\tsendMillis\toutcomeMillis\toutcomePersisted\treservationIds\n");
      batches.write("traceId\treadCount\tnormalCount\tnewCount\tnewReservationIds\tmetadataAtMs\treservationIds\n");
      out.write("event\tkind\ttraceId\tclientKey\treservationId\tcampaignId\tstartMs\tendMs\tphase\tstatus\tthreadId\n");
      while(file.hasMoreEvents()) {
        var e=file.readEvent();var name=e.getEventType().getName();
        if(name.equals("content.SaleBridgeBatch")) {
          batches.write(String.join("\t",safe(e.getString("traceId")),Integer.toString(e.getInt("readCount")),Integer.toString(e.getInt("normalCount")),Integer.toString(e.getInt("newCount")),safe(e.getString("newReservationIds")),Long.toString(e.getStartTime().toEpochMilli()),safe(e.getString("reservationIds")))+"\n");continue;
        }
        if(name.equals("content.SaleDispatchBatch")) {
          dispatch.write(String.join("\t",Long.toString(e.getStartTime().toEpochMilli()),Long.toString(e.getEndTime().toEpochMilli()),
            Integer.toString(e.getInt("requested")),Integer.toString(e.getInt("idleSenders")),Integer.toString(e.getInt("claimed")),Integer.toString(e.getInt("sent")),
            Integer.toString(e.getInt("failed")),Integer.toString(e.getInt("budgetSkipped")),Integer.toString(e.getInt("confirmMissed")),Integer.toString(e.getInt("retryMissed")),
            Long.toString(e.getLong("claimMillis")),Long.toString(e.getLong("sendMillis")),Long.toString(e.getLong("outcomeMillis")),Boolean.toString(e.getBoolean("outcomePersisted")),safe(e.getString("reservationIds")))+"\n");
          continue;
        }
        if(name.equals("jdk.DataLoss")) {out.write("DATA_LOSS\t\t\t\t\t0\t0\t0\t\t0\n");continue;}
        if(name.equals("jdk.GCPhasePause")) {
          out.write("gc\t\t\t\t\t0\t"+e.getStartTime().toEpochMilli()+"\t"+e.getEndTime().toEpochMilli()+"\t\t0\n");continue;
        }
        if(!name.equals("content.SalePhase")&&!name.equals("content.SaleRequest")) continue;
        boolean root=name.equals("content.SaleRequest");
        out.write(String.join("\t",root?"request":"phase",safe(e.getString("kind")),safe(e.getString("traceId")),
          root?safe(e.getString("clientKey")):"",root?safe(e.getString("reservationId")):"",
          Long.toString(e.getLong("campaignId")),Long.toString(e.getStartTime().toEpochMilli()),
          Long.toString(e.getEndTime().toEpochMilli()),root?"":safe(e.getString("phase")),
          root?Integer.toString(e.getInt("status")):"0",Long.toString(e.getThread().getJavaThreadId()))+"\n");
      }
    }
  }
  static String safe(String s){return s==null?"":s.replace('\t','_').replace('\n','_').replace('\r','_');}
}
