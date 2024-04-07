package be.uantwerpen.fti.ei.namingserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;

public class MulticastListener implements Runnable {
    private final String multicastAddress = "224.0.0.0";
    private final int port = 12345;

    @Override
    public void run() {
        byte[] buf = new byte[256];
        try (MulticastSocket socket = new MulticastSocket(port)) {
            InetAddress address = InetAddress.getByName(multicastAddress);
            socket.joinGroup(address);
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String received = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Message received: " + received);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
