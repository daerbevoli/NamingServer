package be.uantwerpen.fti.ei.namingserver;


import java.io.IOException;
import java.net.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the class that represents a node in the system. It has the property hostname, ip address,
 * previous, next and currentID and numOfNodes (needs to find a better way).
 * The node first sends a multicast message in the form of hostname:IP to the network,
 * It then listens for incoming multicast messages from other nodes and a unicast message from the name server
 * With these messages, the node arranges itself correctly in the system.
 */
public class Node2 {

    private final String hostName;
    private final String IP;

    private int previousID, nextID, currentID;

    private int numOfNodes;

    private static final Logger logger = Logger.getLogger(Node.class.getName());

    public Node2(String name, String IP) {
        this.hostName = name;
        this.IP = IP;

        currentID = hash(IP);
        previousID = currentID;
        nextID = currentID; // Initially the node is the only node in the network

        runFunctionsOnThreads();

    }

    // Thread executor to run the functions on different threads
    public void runFunctionsOnThreads() {
        ExecutorService executor = Executors.newFixedThreadPool(3);

        executor.submit(this::sendBootstrap);

        executor.submit(this::listenMulticast);

        executor.submit(this::receiveUnicast);

        // Shutdown the executor once tasks are completed
        executor.shutdown();
    }


    // Hash function
    public int hash(String name){
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (name.hashCode() + max) * (32768/(max + Math.abs(min)));
        return (int) hashValue;

    }

    // Send a multicast message during bootstrap to the multicast address of 224.0.0.1 to port 3000
    private void sendMulticast(String message){
        try (MulticastSocket socket = new MulticastSocket()){
            InetAddress group = InetAddress.getByName("224.0.0.1"); // Multicast group address
            int port = 3000; // Multicast group port

            byte[] buffer = message.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, port);
            // Send the packet to the multicast group
            socket.send(packet);

            System.out.println("Multicast message sent successfully.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to send multicast message", e);
        }

    }

    // Send a multicast message during bootstrap with name and IP address
    private void sendBootstrap() {
        String message = "BOOTSTRAP:" + hostName + ":" + IP;
        sendMulticast(message);
    }

    // Listen on port 3000 for incoming multicast messages, update the arrangement in the topology accordingly
    private void listenMulticast(){
        try (MulticastSocket socket = new MulticastSocket(3000)){

            System.out.println("connected to multicast network");

            // Join the multicast group
            InetAddress group = InetAddress.getByName("224.0.0.1");
            socket.joinGroup(group);

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            // Receive file data and write to file
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String message = new String(packet.getData(), 0, packet.getLength());
            String[] parts = message.split(":");

            System.out.println("Received message: " + message);

            if (message.startsWith("BOOTSTRAP")){
                // Update the hash
                int hash = hash(parts[2]);
                updateHash(numOfNodes, hash);
            }


        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }



    // Receive the map size from the name server
    private void receiveUnicast() {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true); // tells the OS that it's okay to bind to a port that is still in the TIME_WAIT state (which can occur after the socket is closed).
            socket.bind(new InetSocketAddress(8000));

            System.out.println("Connected to receive unicast");

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            // Receive file data and write to file
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            numOfNodes = Integer.parseInt(new String(packet.getData(), 0, packet.getLength()));

            System.out.println("Nodes in the network: " + numOfNodes);


        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
    }

    // Update the hash
    public void updateHash(int numOfNodes, int hash){
        if (hash == currentID) { // Received info is about itself
            return;
        }
        if (numOfNodes < 1){
            previousID = currentID;
            nextID = currentID;
        } else {
            if (currentID < hash && hash < nextID){
                nextID = hash;
            }
            if (previousID < hash  && hash < currentID){
                previousID = hash;
            }
        }
    }

    public void shutDown()
    {

    }


    public static void main(String[] args)  {
        Node2 node2 = new Node2("John", "8.8.8.8");
        System.out.println(node2.previousID + node2.currentID + node2.nextID);
    }
}
