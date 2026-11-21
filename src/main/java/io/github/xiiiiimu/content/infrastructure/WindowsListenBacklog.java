package io.github.xiiiiimu.content.infrastructure;
import java.io.FileDescriptor;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
/** Opt-in local Windows transport tuning; does not change purchase or authentication rules. */
public final class WindowsListenBacklog {
 private WindowsListenBacklog(){}
 public static void bind(ServerSocketChannel socket,InetSocketAddress address,int backlog)throws Exception {
  if(backlog<200 || backlog>65535)throw new IllegalArgumentException("Windows backlog hint must be 200..65535");
  if(!System.getProperty("os.name","").startsWith("Windows"))throw new IllegalStateException("Windows-only local tuning");
  if(address.getAddress()==null || !address.getAddress().isLoopbackAddress())throw new IllegalArgumentException("Loopback listener required");
  if(socket.getLocalAddress()!=null)throw new IllegalArgumentException("Hint must be used on the first listen, not after bind");
  var fdField=socket.getClass().getDeclaredField("fd");fdField.setAccessible(true);var fd=(FileDescriptor)fdField.get(socket);
  var net=Class.forName("sun.nio.ch.Net");
  var bind=net.getDeclaredMethod("bind",FileDescriptor.class,InetAddress.class,int.class);bind.setAccessible(true);
  var listen=net.getDeclaredMethod("listen",FileDescriptor.class,int.class);listen.setAccessible(true);
  var local=net.getDeclaredMethod("localAddress",FileDescriptor.class);local.setAccessible(true);
  // SSC.bind normalizes negative backlog. Use its own native operations, with the
  // Winsock hint on the FIRST listen; relistening cannot enlarge an existing queue.
  bind.invoke(null,fd,address.getAddress(),address.getPort());listen.invoke(null,fd,-backlog);
  var localField=socket.getClass().getDeclaredField("localAddress");localField.setAccessible(true);
  localField.set(socket,local.invoke(null,fd));
 }
}
