package be.uantwerpen.fti.ei.namingserver;


import java.io.IOException;
import java.net.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Node {

    private String name = "steve";
    private String addr = "8.8.8.8";

    private static final Logger logger = Logger.getLogger(Node.class.getName());

    public Node() {
    }

    private void sendMulticast(){
        try {
            MulticastSocket socket = new MulticastSocket();
            InetAddress group = InetAddress.getByName("224.0.0.1"); // Multicast group address
            int port = 3000; // Multicast group port

            String message = name + ":" + addr;
            byte[] buffer = message.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, port);

            // Send the packet
            socket.send(packet);

            System.out.println("Message sent successfully.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to open Multicast socket at the node", e);
        }

    }

    public static void main(String[] args)  {
        new Node().sendMulticast();
    }
}
