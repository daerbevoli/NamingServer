package be.uantwerpen.fti.ei.namingserver;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
public class Discovery {
    private final String multicastAddress = "224.0.0.0";
    private final int port = 12345;

    public void sendDiscoveryMessage() {
        try (MulticastSocket socket = new MulticastSocket()) { // Try-with-resources to ensure socket closure
            socket.setTimeToLive(2);
            InetAddress group = InetAddress.getByName(multicastAddress);
            String message = "DISCOVER";
            byte[] buf = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, group, port);
            socket.send(packet);
            System.out.println("Discovery message sent.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
