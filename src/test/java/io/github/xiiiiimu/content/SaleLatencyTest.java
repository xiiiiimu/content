package io.github.xiiiiimu.content;
import io.github.xiiiiimu.content.infrastructure.SaleLatency;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SaleLatencyTest {
 @Test void recordsPhasesAndClearsRequestContext()throws Exception {
  Path file=Files.createTempFile(Path.of("target"),"sale-latency-",".jfr");
  try(var recording=new Recording()){
   recording.enable(SaleLatency.PhaseEvent.class);recording.start();
   try(var request=SaleLatency.begin("jm-c1-u2","http",1,12345)){
    try(var phase=SaleLatency.phase("sql.test")){}
   }
   try(var outside=SaleLatency.phase("must.not.leak")){}
   recording.stop();recording.dump(file);
  }
  var phases=RecordingFile.readAllEvents(file).stream().filter(e->e.getEventType().getName().equals("content.SalePhase")).toList();
  assertThat(phases).hasSize(1);
  assertThat(phases.get(0).getString("traceId")).isEqualTo("jm-c1-u2");
  assertThat(phases.get(0).getString("phase")).isEqualTo("sql.test");
 }
}
