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
public class Node {

    private final String hostName;
    private final String IP;

    private int previousID, nextID, currentID;

    private int numOfNodes;

    private static final Logger logger = Logger.getLogger(Node.class.getName());

    public Node(String name, String IP) {
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
            logger.log(Level.WARNING, "Unable to connect to multicast socket", e);
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

            while (true) {  // Keep listening indefinitely
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Received message: " + message);

                if (message.startsWith("BOOTSTRAP")){
                    processBootstrap(message);
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }

    // Process the message received from the multicast
    private void processBootstrap(String message) {
        String[] parts = message.split(":");
        String name = parts[1];
        String IP = parts[2];
        int receivedHash = hash(IP);
        // Update current node's network parameters based on the received node's hash
        updateHash(receivedHash);
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

            numOfNodes = Integer.parseInt(new String(packet.getData(), 0, packet.getLength()).trim());

            System.out.println("Nodes in the network: " + numOfNodes);

            // Adjust previous and next IDs based on the number of nodes
            adjustNodeLinks(numOfNodes);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
    }

    // Adjust the previous and next IDs based on the number of nodes
    private void adjustNodeLinks(int numOfNodes) {
        if (numOfNodes <= 1){
            previousID = currentID;
            nextID = currentID;
        } else {
            previousID = currentID - 1;
            nextID = currentID + 1;
        }
    }



    // Update the hash
    public void updateHash(int receivedHash){
        if (receivedHash == currentID) { // Received info is about itself
            return;
        }
        if (numOfNodes < 1){
            previousID = currentID;
            nextID = currentID;
        } else {
            if (currentID < receivedHash && receivedHash < nextID){
                nextID = receivedHash;
                System.out.println("Next ID: " + nextID);
            }
            if (previousID < receivedHash  && receivedHash < currentID){
                previousID = receivedHash;
            }
        }
    }

    public void shutDown()
    {

    }


    public static void main(String[] args)  {
        Node node = new Node("Steve", "12.12.12.12");
        System.out.println(node.previousID + node.currentID + node.nextID);

        //Node node2 = new Node("John", "8.8.8.8");
        //System.out.println(node2.previousID + node2.currentID + node2.nextID);
    }
}