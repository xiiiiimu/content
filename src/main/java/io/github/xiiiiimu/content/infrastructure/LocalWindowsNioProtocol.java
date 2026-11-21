package io.github.xiiiiimu.content.infrastructure;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.NioEndpoint;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
/** Same Tomcat NIO connector, with only its loopback bind adapted for Winsock. */
public final class LocalWindowsNioProtocol extends Http11NioProtocol {
 public LocalWindowsNioProtocol(){
  try{
   var field=AbstractProtocol.class.getDeclaredField("endpoint");field.setAccessible(true);
   var original=(NioEndpoint)field.get(this);var endpoint=new LocalEndpoint();
   endpoint.setConnectionTimeout(original.getConnectionTimeout());
   endpoint.setTcpNoDelay(original.getTcpNoDelay());
   endpoint.setHandler(original.getHandler());field.set(this,endpoint);
  }catch(ReflectiveOperationException e){throw new IllegalStateException("Unsupported Tomcat endpoint layout",e);}
 }
 private static final class LocalEndpoint extends NioEndpoint {
  @Override protected SocketChannel serverSocketAccept()throws Exception {var socket=super.serverSocketAccept();SaleLatency.accepted(socket);return socket;}
  @Override protected void initServerSocket()throws Exception {
   var socket=ServerSocketChannel.open();
   try{
    getSocketProperties().setProperties(socket.socket());
    WindowsListenBacklog.bind(socket,new InetSocketAddress(getAddress(),getPortWithOffset()),getAcceptCount());
    var field=NioEndpoint.class.getDeclaredField("serverSock");field.setAccessible(true);field.set(this,socket);
    socket.configureBlocking(true);
    org.slf4j.LoggerFactory.getLogger(LocalWindowsNioProtocol.class).info("Local Windows first-listen backlog hint applied: address={} target={}",socket.getLocalAddress(),getAcceptCount());
   }catch(Exception e){socket.close();throw e;}
  }
 }
}
