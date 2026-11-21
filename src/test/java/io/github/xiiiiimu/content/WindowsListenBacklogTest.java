package io.github.xiiiiimu.content;
import io.github.xiiiiimu.content.infrastructure.WindowsListenBacklog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WindowsListenBacklogTest {
 @Test @EnabledOnOs(OS.WINDOWS) void holdsThousandConnectionsBeforeAcceptWithoutRefusal()throws Exception {
  var clients=new ArrayList<Socket>();int refused=0;
  try(var server=ServerSocketChannel.open()){
   WindowsListenBacklog.bind(server,new InetSocketAddress(InetAddress.getLoopbackAddress(),0),5000);
   for(int i=0;i<1000;i++){
    var socket=new Socket();
    try{socket.connect(server.getLocalAddress(),1000);clients.add(socket);}
    catch(java.io.IOException e){refused++;socket.close();}
   }
   assertThat(refused).as("Windows backlog must admit all 1000 pending TCP connections").isZero();
   assertThat(clients).hasSize(1000);
  }finally{for(var socket:clients)socket.close();}
 }
 @Test @EnabledOnOs(OS.WINDOWS) void customTomcatConnectorServesRealHttp()throws Exception {
  var tomcat=new org.apache.catalina.startup.Tomcat();
  var base=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"windows-local-tomcat").toAbsolutePath();
  tomcat.setBaseDir(base.toString());
  var connector=new org.apache.catalina.connector.Connector(io.github.xiiiiimu.content.infrastructure.LocalWindowsNioProtocol.class.getName());
  connector.setPort(0);connector.setProperty("address","127.0.0.1");connector.setProperty("acceptCount","5000");
  tomcat.setConnector(connector);
  var context=tomcat.addContext("",base.toString());
  org.apache.catalina.startup.Tomcat.addServlet(context,"probe",new jakarta.servlet.http.HttpServlet(){
   @Override protected void doGet(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException {response.getWriter().write("ok");}
  });context.addServletMappingDecoded("/probe","probe");
  try{
   tomcat.start();
   var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+connector.getLocalPort()+"/probe")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
   assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo("ok");
  }finally{tomcat.stop();tomcat.destroy();}
 }

}
