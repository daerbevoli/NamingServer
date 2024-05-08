package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.Scanner;
import java.util.concurrent.*;
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

    private final ServerSocket serverSocket;

    private String serverIP;
    private static final Logger logger = Logger.getLogger(Node.class.getName());

    public Node() {
        this.IP = findLocalIP();
        System.out.println("node IP: " + IP);

        numOfNodes = 0;

        currentID = hash(IP);
        nextID = currentID;
        previousID = currentID;

        try {
            this.serverSocket = new ServerSocket(5231);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        runFunctionsOnThreads();

    }

    // Thread executor to run the functions on different threads
    public void runFunctionsOnThreads() {
        //ScheduledExecutorService executor = (ScheduledExecutorService) Executors.newFixedThreadPool(4);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

        executor.submit(this::Bootstrap);

        executor.submit(this::listenNodeMulticast);

        executor.scheduleAtFixedRate(this::watchFolder, 0, 2, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        // Shutdown the executor once tasks are completed
        executor.shutdown();
    }


    // Add a local file to the node
    public void addFile(String filename, String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory if it does not exist
        }
        File file = new File (directoryPath + "/" + filename);
        try {
            if (file.createNewFile()) {
                System.out.println(filename + " created successfully at " + file.getPath());

            } else {
                System.out.println("File already exists at" + file.getPath());
            }
        } catch (IOException e) {
            System.out.println("Error creating the file: " + e.getMessage());
        }
    }

    // Node verifies local files and report to the naming server
    private void verifyAndReportLocalFiles() {
        File directory = new File("/root/localFiles");
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String filename = file.getName();
                    int fileHash = hash(filename);
                    reportFileHashToServer(fileHash, filename);
                }
            }
        }
    }

    private void reportFileHashToServer(int fileHash, String filename) {
        if (serverIP == null) {
            System.out.println("Server IP is not available, cannot report file hash");
            return;
        }
        String message = "REPORT" + ":" + IP + ":" + fileHash + filename;
        String purpose = "Reportigg file hashes to server";

        sendUnicast(purpose, serverIP, message, 8000);
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


    // Yet to try and make complete
    private void watchFolder() {
        Path folderToWatch = Paths.get("/root/localFiles");

        // Create a file system watcher
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            folderToWatch.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            // Wait for events
            WatchKey key = watchService.poll();
            if (key != null) {
                System.out.println("Changes detected!");
                processReplicate();
                folderToWatch.getFileName();
            } else {
                System.out.println("No changes detected.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    private void sendUnicast(String purpose, String targetIP, String message, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {

            System.out.println("Connected to UDP socket for purpose: " + purpose);

            byte[] buffer = message.getBytes();

            InetAddress targetIp = InetAddress.getByName(targetIP); // Uses the hostname of the target node (getByName)

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, targetIp, port);

            // Send the packet
            socket.send(packet);

            System.out.println("Unicast message with purpose: " + purpose + "sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server socket", e);
        }
    }

    // Send a multicast message during bootstrap with name and IP address
    // Send a multicast message during bootstrap to the multicast address of 224.0.0.1 to port 3000
    private void Bootstrap() {
        verifyAndReportLocalFiles();
        String message = "BOOTSTRAP" + ":" + IP + ":" + currentID;
        sendMulticast("send bootstrap", message, 3000);
        receiveUnicast("Receive number of nodes", 8000);
        verifyAndReportLocalFiles();

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
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }


    private void receiveUnicast(String purpose, int port) {
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
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            serverIP = packet.getAddress().getHostAddress();  // Get IP of the server by getting source address

            String message = new String(packet.getData(), 0, packet.getLength());

            processReceivedMessage(message);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
    }

    /*
     * The shutdown method is used when closing a node. It is also used in exception for failure.
     * The method sends a multicast message with the indication of shutdown along with its IP,
     * previous and next node. The name server receives this message and removes the node from its map.
     * The nodes receive this message and update their previous and next IDs
     */
    public void shutdown() {
        String message = "SHUTDOWN" + ":" + IP + ":" + previousID + ":" + nextID;
        sendMulticast("Shutdown", message, 3000);
    }

    // FAILURE can be handled with a "heartbeat" mechanism


    private void processReceivedMessage(String message) throws IOException {
        logger.log(Level.INFO,"message to process: "+message);
        if (message.startsWith("BOOTSTRAP")){
            processBootstrap(message);
        }
        if (message.startsWith("SHUTDOWN")){
            processShutdown(message);
        }
        if (message.startsWith("NUMNODES")){
            processNumNodes(message);
        }
        if (message.startsWith("REPLICATE")){
            processReplicate();
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

        try {
            updateHash(receivedHash,IP);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logger.log(Level.INFO, "Post bootstrap process: " + IP + "previousID:" + previousID + "nextID:" + nextID + "numOfNodes:" + numOfNodes);
    }

    private void processNumNodes(String message){
        String[] parts = message.split(":");
        numOfNodes = Integer.parseInt(parts[1]);
        System.out.println("Number of nodes: " + numOfNodes);
        //logger.log(Level.INFO, "number of nodes is "+numOfNodes);
        // yet to complete
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


    // Receive the map size from the name server

    // Update the hash
    public void updateHash(int receivedHash, String IP) throws IOException {
        /*
        if the new hash is smaller than the current next hash and bigger than this node's hash,
        or if the next hash is set to this node's hash
        we replace the next hash with the new received hash and notify it by sending the old one
        */

        if (receivedHash == nextID) {
            System.out.println("Received own bootstrap, my ID: "+currentID);
            return;
        }

        if ((currentID < receivedHash && receivedHash < nextID) || currentID==nextID|| (nextID<currentID && (receivedHash>currentID || receivedHash<nextID) )){
            int oldNext= nextID;
            nextID = receivedHash;
            sendNodeResponse(true, IP, oldNext);
            logger.log(Level.INFO, "Next ID updated to: "+nextID);
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
            logger.log(Level.INFO, "Previous ID updated to: "+previousID);
        }
    }



    private void processReplicate(){
        String message = "REPORT" + ":" + IP;
        String purpose = "Reporting file hashes to server";
        sendUnicast(purpose, serverIP, message, 8000);

        receiveUnicast("Receive replicated file", 8100);
        // continuation with file transfer protocol
    }

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
                addFile(filename, "/root/localFiles");
                System.out.println(filename + " added.");
            } else if (command.equals("shutdown")) {
                System.out.println("Shutting down");

                shutdown();
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

    public void sendFile()
    {
        FileTransfer ft1= new FileTransfer();
        ft1.tranferFile("/root/localFiles/b.txt" ,serverIP,5678);

    }

    public static void main(String[] args) {
        Node node = new Node();
        node.run();
    }
}
