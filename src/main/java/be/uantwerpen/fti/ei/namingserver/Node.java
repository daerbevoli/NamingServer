package be.uantwerpen.fti.ei.namingserver;


import java.io.IOException;
import java.net.*;
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

        runFunctionsOnThreads();

    }

    // Thread executor
    public void runFunctionsOnThreads() {
        ExecutorService executor = Executors.newFixedThreadPool(3);

        executor.submit(this::sendMulticast);

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

    // Send a multicast message to the multicast address of 224.0.0.1 to port 3000
    private void sendMulticast(){
        try (MulticastSocket socket = new MulticastSocket()){
            InetAddress group = InetAddress.getByName("224.0.0.1"); // Multicast group address
            int port = 3000; // Multicast group port

            String message = hostName + ":" + IP;
            byte[] buffer = message.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, port);

            // Send the packet
            socket.send(packet);

            System.out.println("Hostname and IP sent successfully.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to open Multicast socket at the node", e);
        }

    }

    // Listen on port 3000 for incoming multicast messages, update the arrangement in the topology accordingly
    private void listenMulticast(){
        try (MulticastSocket socket = new MulticastSocket(3000)){

            System.out.println("connected to multicast network : Node 1");

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

            int hash = hash(parts[1]);

            updateHash(numOfNodes, hash);


        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }



    // Receive the map size from the name server
    private void receiveUnicast() {
        try (DatagramSocket socket = new DatagramSocket(8000)) {

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
        //Node node = new Node("Steve", "12.12.12.12");
        //System.out.println(node.previousID + node.currentID + node.nextID);

        Node node2 = new Node("John", "8.8.8.8");
        System.out.println(node2.previousID + node2.currentID + node2.nextID);
    }
}
