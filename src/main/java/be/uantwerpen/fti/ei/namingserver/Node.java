package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.*;
import java.util.Enumeration;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
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

    private final String IP;
    private int previousID, nextID, currentID;
    private int numOfNodes;

    private ServerSocket serverSocket;

    private String serverIP;
    private static final Logger logger = Logger.getLogger(Node.class.getName());
    private ConcurrentHashMap<String, String> localFiles = new ConcurrentHashMap<>();

    public Node() {
        this.IP = findLocalIP();
        System.out.println("node IP: " + IP);
        numOfNodes=0;
        currentID = hash(IP);
        nextID=currentID;
        previousID=currentID;
        try {
            this.serverSocket = new ServerSocket(5231);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        runFunctionsOnThreads();

    }

    // Add a local file to the node
    public void addLocalFile(String filename) {
        String directoryPath = "src/main/java/be/uantwerpen/fti/ei/namingserver/Files"; // Directory path
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory if it does not exist
        }
        File file = new File(directoryPath + "/" + filename);
        try {
            if (file.createNewFile()) {
                localFiles.put(filename, file.getAbsolutePath());
                System.out.println(filename + " created successfully at " + file.getPath());

            } else {
                System.out.println("File already exists at" + file.getPath());
            }
        } catch (IOException e) {
            System.out.println("Error creating the file: " + e.getMessage());
        }
    }

    // Find the local ip of the remote node
    // Find the local hostname of the remote node
    // Used hostname because hash function returned same hash code for IPs in similar range
    private String findLocalIP() {

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // Filters out IPv6 addresses
                    if (addr instanceof Inet4Address) {
                        return addr.getHostName();
                    }
                }
            }
        } catch (SocketException e) {
            logger.log(Level.WARNING, "Unable to find local IP", e);
        }
        return "127.0.0.1"; // Default IP address localhost
    }

    // Thread executor to run the functions on different threads
    public void runFunctionsOnThreads() {
        ExecutorService executor = Executors.newFixedThreadPool(4);

        executor.submit(this::sendBootstrap);

        executor.submit(this::listenNodeMulticast);

        //executor.submit(this::receiveNodeResponse);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownMulticast));

        // Shutdown the executor once tasks are completed
        executor.shutdown();
    }


    // Hash function
    public int hash(String IP) {
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (IP.hashCode() + max) * (32768 / (max + Math.abs(min)));
        return (int) hashValue;

    }

    private void sendMulticast(String purpose, String message, int port) {
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName("224.0.0.1"); // Multicast group address
            System.out.println("connected to multicast server for purpose: " + purpose);

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

    private void sendUnicast(String purpose, InetAddress targetIP, String message, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {

            System.out.println("Connected to UDP socket for purpose: " + purpose);

            byte[] buffer = message.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, targetIP, port);

            // Send the packet
            socket.send(packet);

            System.out.println("Unicast message with purpose: " + purpose + "sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server socket", e);
        }
    }

    // Send a multicast message during bootstrap with name and IP address
    // Send a multicast message during bootstrap to the multicast address of 224.0.0.1 to port 3000
    private void sendBootstrap() {
        String message = "BOOTSTRAP" + ":" + IP + ":" + currentID;
        sendMulticast("send bootstrap", message, 3000);
        receiveUnicast("Receive number of nodes", 8000);
    }

    // Listen on port 3000 for incoming multicast messages, update the arrangement in the topology accordingly
    private void listenNodeMulticast() {
        try (MulticastSocket socket = new MulticastSocket(3000)) {

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

                if (message.startsWith("BOOTSTRAP")) {
                    processBootstrap(message);
                }
                if (message.startsWith("SHUTDOWN")) {
                    processShutdown(message);
                }

            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }

    private void processShutdown(String message) {
        String[] parts = message.split(":");
        //String IP = parts[1];
        int prevId = Integer.parseInt(parts[2]);
        int nxtID = Integer.parseInt(parts[3]);
        updateHashShutdown(prevId, nxtID);
    }

    // Process the message received from the multicast
    private void processBootstrap(String message) throws IOException {
        String[] parts = message.split(":");
        //String command = parts[0];
        String IP = parts[1];

        int receivedHash = hash(IP);

        logger.log(Level.INFO, "CurrentID:"+currentID+" receivedID:"+receivedHash);
        // Update current node's network parameters based on the received node's hash
        if (receivedHash == currentID) { // Received info is about itself
            logger.log(Level.INFO,"Received own bootstrap, my ID: "+currentID);
            if(numOfNodes >1)
            {
                logger.log(Level.INFO,"Condition met to start TCP connection");
                receiveNodeResponse();
            }
            return;
        }
        numOfNodes++;
        System.out.println("test");
        try {
            updateHash(receivedHash,IP);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logger.log(Level.INFO, "Post bootstrap process: " + IP + ":" + previousID + ":" + nextID + ":" + numOfNodes);
    }

    private void updateHashShutdown(int prevID, int nxtID) {
        numOfNodes--;
        if (currentID == prevID) {
            nextID = nxtID;
        }
        if (currentID == nxtID) {
            previousID = prevID;
        }
    }

    private DatagramPacket receiveUnicast(String purpose, int port) {
        DatagramPacket packet;
        try (DatagramSocket socket = new DatagramSocket(null)) {
            System.out.println("Connected to datagram socket for purpose: " + purpose);

            // tells the OS that it's okay to bind to a port that is still in the TIME_WAIT state
            // (which can occur after the socket is closed).
            socket.setReuseAddress(true);

            socket.bind(new InetSocketAddress(port));
            System.out.println("Connected to receive unicast");

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            // Receive file data and write to file
            packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            serverIP = packet.getAddress().getHostAddress();  // Get IP of the server by getting source address

            numOfNodes = Integer.parseInt(new String(packet.getData(), 0, packet.getLength()).trim());

            System.out.println("Nodes in the network: " + numOfNodes);

            return packet;

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
        return null;

    }


    // Receive the map size from the name server

    // Update the hash
    public void updateHash(int receivedHash, String IP) throws IOException {


        /*
        if the new hash is smaller than the current next hash and bigger than this node's hash,
        or if the next hash is set to this node's hash
        we replace the next hash with the new received hash and notify it by sending the old one
        */
        if ((currentID < receivedHash && receivedHash < nextID) || currentID==nextID|| (nextID<currentID && (receivedHash>currentID || receivedHash<nextID) )){
            int oldNext= nextID;
            nextID = receivedHash;
            sendNodeResponse(true, IP, oldNext);

        }

        /*
        if the new hash is bigger than the current previous hash and smaller than this node's hash,
        or if the previous hash is set to this node's hash
        we replace the previous hash with the new received hash and notify it by sending the old one
        */
        if ((previousID < receivedHash  && receivedHash < currentID) || currentID==previousID|| (previousID>currentID && (receivedHash<currentID|| receivedHash>previousID))){
            int oldPrevious =previousID;
            previousID = receivedHash;
            sendNodeResponse(false, IP, oldPrevious);

        }
    }

    /*
     * The shutdown method is used when closing a node. It is also used in exception for failure.
     * The method sends a multicast message with the indication of shutdown along with its IP,
     * previous and next node. The name server receives this message and removes the node from its map.
     * The nodes receive this message and update their previous and next IDs
     */
    public void shutdownMulticast(){
        try (MulticastSocket socket = new MulticastSocket(11000)){

            System.out.println("Connected to UDP socket for shutdown");

            InetAddress group = InetAddress.getByName("224.0.0.1");

            String str = "SHUTDOWN" + ":" + IP + ":" + previousID + ":" + nextID;
            byte[] buffer = str.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 3000);

            socket.send(packet);
            logger.log(Level.INFO, "shutdown packet sent");

        } catch (IOException e){
            logger.log(Level.WARNING, "Unable to connect to server for shutdown", e);
        }
    }

    private void replicate(String filename){}

    public void sendNodeResponse(Boolean replacedNext, String nodeIP, int replacedHash) throws IOException {
        int port = 5231;
        try (Socket cSocket = new Socket(nodeIP, port);
             DataOutputStream out = new DataOutputStream(cSocket.getOutputStream())) {

            String msg = replacedNext ? "NEXT:" + replacedHash + ":" + currentID : "PREV:" + replacedHash + ":" + currentID;
            out.writeUTF(msg);
            out.flush();
            logger.log(Level.INFO, "Sending package");
        }
    }

    public void receiveNodeResponse() throws IOException {
        try (Socket cSocket = serverSocket.accept();
             DataInputStream in = new DataInputStream(cSocket.getInputStream())) {
            String msg = in.readUTF();
            System.out.println("Received message: " + msg);
            String[] parts = msg.split(":");
            if (parts[0].equalsIgnoreCase("next")) {
                nextID = Integer.parseInt(parts[1]);
                previousID = Integer.parseInt(parts[2]);
                System.out.println("Next and previous ID were updated because of the response of another node, previousID:"+previousID+"Next:"+ nextID);
            } else if (parts[0].equalsIgnoreCase("prev")) {
                nextID = Integer.parseInt(parts[2]);
                previousID = Integer.parseInt(parts[1]);
                System.out.println("Next and previous ID were updated because of the response of another node, previousID:"+previousID+"Next:"+ nextID);
            }
        }
    }
    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("Enter command: ");
            String command = scanner.nextLine();
            if (command.startsWith("addFile ")) {
                String filename = command.substring(8);
                addLocalFile(filename);
                System.out.println(filename + " added.");
            } else if (command.equals("shutdown")) {
                shutdownMulticast();
                System.exit(0);
                System.out.println("Shutting down");
            } else {
                System.out.println("Invalid command.");
            }
        }
    }

    // ping method to check whether a connection with a node can be made
    public void ping(InetAddress address){
        try (Socket socket = new Socket(address, 0)){

            logger.log(Level.INFO, "Connected to the node");

        } catch (IOException e){
            logger.log(Level.SEVERE, "Failed to connect to node", e);
        }
    }

    public static void main(String[] args) {
        Node node = new Node();
        node.run();
    }
}
