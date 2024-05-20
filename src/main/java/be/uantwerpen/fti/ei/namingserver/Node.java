package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;


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
    private final File fileLog = new File("root/logs/fileLog.json");

    public Node() {
        this.IP = helpMethods.findLocalIP();
        logger.log(Level.INFO, "node IP: " + IP);

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
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(6);

        executor.submit(this::listenNodeMulticast);

        executor.submit(this::Bootstrap);

        executor.submit(this::receiveNumOfNodes);

        executor.submit(() -> receiveUnicast("Replication purpose", 8100));
        executor.submit(() -> receiveUnicast("Create log purpose", 8700));

        executor.submit(() -> FileTransfer.receiveFile(8500, "root/replicatedFiles"));

        executor.scheduleAtFixedRate(this::watchFolder, 0, 1, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        // Shutdown the executor once tasks are completed
        executor.shutdown();
    }


    // Send a multicast message during bootstrap with name and IP address
    // Send a multicast message during bootstrap to the multicast address of 224.0.0.1 to port 3000
    private void Bootstrap() {

        String message = "BOOTSTRAP" + ":" + IP + ":" + currentID;
        helpMethods.sendMulticast("send bootstrap", message, 3000);

        logger.log(Level.INFO, "Received own bootstrap, my ID: " + currentID + "\nMy number of nodes=" + numOfNodes);
        /*int i=0;
        while (numOfNodes == 0) {                                       //delay until receiving numofnodes from the server
            i=(i+1)%300000;
            if(i==1){
                System.out.println("Waiting for numofnodes > 0");}
        }*/
        if (numOfNodes > 1) {
            logger.log(Level.INFO, "Condition met to start TCP connection");
            try {
                receiveNodeResponse();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
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
        helpMethods.sendMulticast("Shutdown", message, 3000);
    }
    // FAILURE can be handled with a "heartbeat" mechanism

    // Hash function provided by the teachers
    public int hash(String IP){
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (IP.hashCode() + max) * (32768/(max + Math.abs(min)));
        return (int) hashValue;
    }

    // Create/Update a log file with file references when replicating a file
    private void updateLogFile(String localOwnerIP, String filename) {
        try {
            // Ensure the directory exists
            File directory = fileLog.getParentFile();
            if (directory != null && !directory.exists()) {
                directory.mkdirs();
            }

            JSONObject root;
            if (fileLog.exists()) {
                String content = new String(Files.readAllBytes(fileLog.toPath()));
                root = new JSONObject(content);
            } else {
                root = new JSONObject();
            }
            JSONObject fileInfo = new JSONObject();
            fileInfo.put("localOwnerIP", localOwnerIP);
            fileInfo.put("replicatedOwnerIP", IP);
            root.put(filename, fileInfo);

            try (FileWriter writer = new FileWriter(fileLog)) {
                writer.write(root.toString());
            }
            logger.log(Level.INFO, "File log updated successfully");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error updating file log", e);

        } catch (JSONException e) {
            logger.log(Level.WARNING, "Error creating JSON object", e);
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
            logger.log(Level.INFO, "Server IP is not available, cannot report file hash");
            return;
        }
        String message = "REPORT" + ":" + IP + ":" + fileHash + ":" + filename;
        String purpose = "Reporting file hashes to server";

        helpMethods.sendUnicast(purpose, serverIP, message, 8000);
    }

    private void watchFolder() {
        Path folderToWatch = Paths.get("/root/localFiles");

        // Create a file system watcher
        try {
            logger.log(Level.INFO, "Watching the folder");
            WatchService watchService = FileSystems.getDefault().newWatchService();
            folderToWatch.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            // Wait for events
            WatchKey key = watchService.poll();
            if (key != null) {
                logger.log(Level.INFO, "A file was added to the localFiles dir");
                String filename = String.valueOf(folderToWatch.getFileName());
                reportFileHashToServer(hash(filename), filename);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to watch folder ", e);
        }
    }



    // Listen on port 3000 for incoming multicast messages, update the arrangement in the topology accordingly
    private void listenNodeMulticast() {
        try (MulticastSocket socket = new MulticastSocket(3000)) {

            logger.log(Level.INFO,"connected to multicast receive socket: listen for incoming messages");

            // Join the multicast group
            InetAddress group = InetAddress.getByName("224.0.0.1");
            socket.joinGroup(group);

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            while (true) {  // Keep listening indefinitely
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                logger.log(Level.INFO, "Multicast message received successfully: " + message);
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING,"Unable to open multicast receive socket: listen for incoming messages");
        }
    }


    private void receiveUnicast(String purpose, int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            logger.log(Level.INFO, "Connected to unicast receive socket: " + purpose);

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            // Receive file data and write to file
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String message = new String(packet.getData(), 0, packet.getLength());

            logger.log(Level.INFO, "Unicast message received successfully: " + message);

            processReceivedMessage(message);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
    }

    private void receiveNumOfNodes() {
        try (DatagramSocket socket = new DatagramSocket(8300)) {
            logger. log(Level.INFO, "Connected to unicast socket: receive number of nodes");

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            while (true) {
                // Receive file data and write to file
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());

                serverIP = packet.getAddress().getHostName();

                processReceivedMessage(message);
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
    }


    private void processReceivedMessage(String message) throws IOException {
        logger.log(Level.INFO,"message to process: " + message);
        if (message.startsWith("BOOTSTRAP")){
            processBootstrap(message);
        }
        else if (message.startsWith("SHUTDOWN")){
            processShutdown(message);
        }
        else if (message.startsWith("NUMNODES")){
            processNumNodes(message);
        }
        else if (message.startsWith("REPLICATE")){
            processReplicate(message);
        }
        else if (message.startsWith("CREATE_LOG")) {
            processCreateLog(message);
        }
    }

    private void processCreateLog(String message) {
        String[] parts = message.split(":");
        String localOwnerIP = parts[1];
        String filename = parts[2];
        updateLogFile(localOwnerIP, filename);
    }
    private void processNumNodes(String message){
        String[] parts = message.split(":");
        numOfNodes = Integer.parseInt(parts[1]);
        logger.log(Level.INFO, "Number of nodes: " + numOfNodes);
        verifyAndReportLocalFiles();

    }

    private void processShutdown(String message) {
        numOfNodes--;
        String[] parts = message.split(":");

        int prevId = Integer.parseInt(parts[2]);
        int nxtID = Integer.parseInt(parts[3]);

        updateHashShutdown(prevId, nxtID);
    }

    // Process the message received from the multicast
    private void processBootstrap(String message) {
        String[] parts = message.split(":");
        //String command = parts[0];
        String IP = parts[1];

        int receivedHash = hash(IP);
        logger.log(Level.INFO, "CurrentID:" + currentID + " receivedID:" + receivedHash);
        // Update current node's network parameters based on the received node's hash
        if (receivedHash != currentID) { // Received bootstrap different from its own
            numOfNodes++;


            try {
            updateHash(receivedHash, IP);
            } catch (IOException e) {
            throw new RuntimeException(e);
            }
        logger.log(Level.INFO, "Post bootstrap process: " + IP + "previousID:" + previousID + "nextID:" + nextID + "numOfNodes:" + numOfNodes);
        }

    }

    private void updateHashShutdown(int prevID, int nxtID) {
        if (currentID == prevID) {
            nextID = nxtID;
        }
        if (currentID == nxtID) {
            previousID = prevID;
        }
        logger.log(Level.INFO, "Post shutdown process: " + IP + "previousID:" + previousID + "nextID:" + nextID + "numOfNodes:" + numOfNodes);
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


    private void processReplicate(String message){
        logger.log(Level.INFO, "In the processReplication method");
        String[] parts = message.split(":");
        String nodeToReplicateTo = parts[1];
        String filename = parts[2];
        if (IP.equals(nodeToReplicateTo)){
            logger.log(Level.INFO, "File is origin");
        } else {
            FileTransfer.transferFile2(nodeToReplicateTo, filename, 8500);
        }
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
            logger.log(Level.INFO, "Received message: " + msg);
            String[] parts = msg.split(":");
            if (parts[0].equalsIgnoreCase("next")) {
                nextID = Integer.parseInt(parts[1]);
                previousID = Integer.parseInt(parts[2]);
                logger.log(Level.INFO, "Next and previous ID updated, previousID: "+ previousID + "Next: " + nextID);
            } else if (parts[0].equalsIgnoreCase("prev")) {
                nextID = Integer.parseInt(parts[2]);
                previousID = Integer.parseInt(parts[1]);
                logger.log(Level.INFO, "Next and previous ID updated, previousID: "+ previousID + "Next: " + nextID);
            }
        }
    }

    private void askID()
    {

    }


    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("Enter command: ");
            String command = scanner.nextLine();

            switch (command) {
                case "shutdown":
                    System.out.println("Shutting down");
                    System.exit(0);
                    break;
                case "num":
                    System.out.println("Number of nodes: " + numOfNodes);
                    break;
                case "id":
                    System.out.println("previousID: " + previousID + ", currentID: " + currentID + ", nextID: " + nextID);
                    break;
                case "local":
                    helpMethods.getFiles("/root/localFiles");
                    break;
                case "replicate":
                    helpMethods.getFiles("/root/replicatedFiles");
                    break;
                default:
                    if (command.startsWith("addFile ")) {
                        String filename = command.substring(8);
                        helpMethods.addFile(filename, "/root/localFiles");
                        System.out.println(filename + " added.");
                    } else {
                        System.out.println("Invalid command.");
                    }
                    break;
            }
        }
    }

    public static void main(String[] args) {
        Node node = new Node();
        node.run();
    }
}
