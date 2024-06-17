package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * This is the class that represents a node in the system. It has the property hostname, ip address,
 * previous, next and currentID and numOfNodes (needs to find a better way).
 * The node first sends a multicast message in the form of hostname:IP to the network,
 * It then listens for incoming multicast messages from other nodes and an unicast message from the name server
 * With these messages, the node arranges itself correctly in the system.
 */

public class Node {

    private final String IP;
    private int previousID, nextID;
    private final int currentID;
    private int numOfNodes;

    private final FileTransfer ft;
    private final ServerSocket serverSocket;
    private String serverIP;
    private boolean finishSending;
    private static final Logger logger = Logger.getLogger(Node.class.getName());
    private static final File fileLog = new File("/root/logs/fileLog.json");

    private volatile String nextNodeIP;

    // ExecutorService to run multiple methods on different threads
    private final ExecutorService executor;

    // Sync agent to sync the files
    private final SyncAgent syncAgent;

    // file list with the filename and whether there is a lock on it -> use?
    private final Map<String, Boolean> filesMap = new HashMap<>();

    private Map<String, Boolean> nextFileMap = new HashMap<>();

    private static volatile boolean replicationComplete = false;

    public Node() {
        this.IP = helpMethods.findLocalIP();
        logger.log(Level.INFO, "node IP: " + IP);

        try {
            ft = new FileTransfer(Ports.ftPort);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        numOfNodes = 0;
        finishSending = false;

        currentID = hash(IP);
        nextID = currentID;
        previousID = currentID;

        try {
            serverSocket = new ServerSocket(5231);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Initialize the sync agent
        syncAgent = new SyncAgent(this);
        new Thread(syncAgent).start();

        // Initialize the files map with the files in the replicated folder
        //filesMap.putAll(helpMethods.getFilesWithLockStatus("/root/replicatedFiles"));


        // Initialization of the executor with a pool of 10 threads
        executor = Executors.newFixedThreadPool(16);
        runFunctionsOnThreads();

    }

    // Thread executor method to run the functions on different threads
    public void runFunctionsOnThreads() {

        executor.submit(this::Bootstrap);
        executor.submit(this::listenNodeMulticast);
        executor.submit(this::receiveNumOfNodes);
        // optimization for later
        // This optimization is to use the general receive function and may be errorless
        // executor.submit(() -> receiveUnicast("Receiving number of nodes", 8300));
        executor.submit(() -> receiveUnicast("Replication purpose", Ports.replPort));
        executor.submit(() -> receiveUnicast("Create log purpose", Ports.logPort));
        executor.submit(() -> receiveUnicast("Sync purpose", Ports.syncPort));
        executor.submit(this::watchFolder);
        executor.submit(() -> ft.receiveFiles( "/root/replicatedFiles"));
        executor.submit(() -> receiveUnicast("File Map request purpose", Ports.reqPort));
        executor.submit(() -> receiveUnicast("Next Node IP purpose", Ports.nextNodeIPPort));
        executor.submit(this::periodicSync);
        executor.submit(() -> receiveFileMap());


        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    }

    public void periodicSync() {
        while (true) {
            if (numOfNodes > 1) {
                runSyncAgent(syncAgent);
            }
            try {
                Thread.sleep(5000); // Periodic sync every 5 seconds
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "Periodic sync interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /*
    These three methods could be used to get the agent and its fileMap in the SyncAgent class.
    This requires however for the methods and properties to be static and this is not proper way
    to work.

    public void sendAgent(){
        helpMethods.sendUnicast("retrieve next host", serverIP, "AIP:" + IP + ":A", Ports.unicastPort);
        executor.submit(() -> receiveUnicast("Get Previous IPs", 9020));
    }
*/
    public SyncAgent getSyncAgent(){
        return syncAgent;
    }

    public Map<String, Boolean> getNextFileMap(){
        return nextFileMap;
    }

    public Map<String, Boolean> getFileMap(){
        return filesMap;
    }

    public void runSyncAgent(SyncAgent syncAgent) {
        new Thread(syncAgent).start();
        syncAgent.start();
        syncAgent.run();
    }

    // Method to get the replicated (owned) files of this node
    public void getOwnedFiles() {
        helpMethods.getFiles("/root/replicatedFiles");
    }

    /*
    Method to get the next node's IP
     */
    // Method to get the next node's IP address from the network

    // Method to get the next node's IP address from the network
    public String getNextNodeIP() {
        getNextIPFromID();
        return nextNodeIP;
    }


    private String getNextIPFromID() {
        try {
            helpMethods.sendUnicast("Requesting IP from ID", serverIP, "GET_IP_FROM_ID" + ":" + IP , Ports.nextNodeIPPort);
            return nextNodeIP;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to get IP from ID", e);
            return null;
        }
    }



    // Send a multicast message during bootstrap with name and IP address
    // Send a multicast message during bootstrap to the multicast address of 224.0.0.1 to port 3000
    private void Bootstrap() {

        String message = "BOOTSTRAP" + ":" + IP + ":" + currentID;
        helpMethods.sendMulticast("send bootstrap", message, 3000);

        logger.log(Level.INFO, "Received own bootstrap, my ID: " + currentID + "\nMy number of nodes=" + numOfNodes);
        int i=0;
        while (numOfNodes == 0) {                                       // delay until receiving numofnodes from the server
            i=(i+1)%300000;
            if(i==1){
                System.out.println("Waiting for numofnodes > 0");}
        }
        if (numOfNodes > 1) {
            logger.log(Level.INFO, "Condition met to start TCP connection");
            try {
                receiveNodeResponse();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // Ensure that replication has been completed before starting the syncagent
        waitForReplicationComplete();
        // Sync agent created during system launch/bootstrap and then run
        runSyncAgent(syncAgent);
        logger.log(Level.INFO, "Sync agent started");
    }

    private void waitForReplicationComplete() {
        while (!replicationComplete) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "Error waiting for replication to complete", e);
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
        if(fileLog.exists() && numOfNodes > 2)
        {
            executor.submit(() -> receiveUnicast("Get Previous IPs", 9020));
            helpMethods.sendUnicast("Acquiring IP of copied node", serverIP, "AIP:" + IP + ":X", Ports.unicastPort);
            while (!finishSending)
            {
            }
        }
        helpMethods.sendMulticast("Shutdown", message, 3000);
        helpMethods.clearFolder("/root/replicatedFiles");
        helpMethods.clearFolder("/root/logs");

        // handle Failure and start Failure agent
        handleFailure(this);
        // stop the syncagent
        syncAgent.stop();
        executor.shutdown();
    }
    // FAILURE can be handled with a "heartbeat" mechanism

    // Hash function
    public int hash(String IP){
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (IP.hashCode() + max) * (32768/(max + Math.abs(min)));
        return (int) hashValue;
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

        helpMethods.sendUnicast(purpose, serverIP, message, Ports.unicastPort);
    }

    private void watchFolder() {
        try {
            // Specify the directory which supposed to be watched
            Path directoryPath = Paths.get("/root/localFiles");

            // Create a WatchService
            WatchService watchService = FileSystems.getDefault().newWatchService();

            // Register the directory for specific events
            directoryPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            // Infinite loop to continuously watch for events
            while (true) {
                WatchKey key = watchService.take();

                // Optimization for later
                // NOT SURE IF THE FOR LOOP IS NECESSARY, TRY A TEST WITHOUT
                for (WatchEvent<?> event : key.pollEvents()) {

                    // Handle the addition event, report file
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        logger.log(Level.INFO, "File added: " + event.context());
                        reportFileHashToServer(hash(String.valueOf(event.context())), String.valueOf(event.context()));
                    }
                }
                // Reset the key to receive further events
                key.reset();
            }

        } catch (IOException | InterruptedException e) {
            logger.log(Level.WARNING, "Unable to watch folder", e);
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


    // General receive unicast function
    private void receiveUnicast(String purpose, int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            logger.log(Level.INFO, "Connected to unicast receive socket: " + purpose);

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            // Receive file data and write to file
            while (true) {  // Keep listening indefinitely
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                logger.log(Level.INFO, "Unicast message received successfully: " + message);
                processReceivedMessage(message);
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server", e);
        }
    }

    private void receiveNumOfNodes() {
        try (DatagramSocket socket = new DatagramSocket(Ports.nnPort)) {
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
        else if (message.startsWith("LOG")) {
            processCreateLog(message);
        }
        else if (message.startsWith("RIP")) {
            String[] parts = message.split(":");
            if (parts[2].equals("A")){
                processAgent(message);
            }
            sendReplicatedFilesShutdown(message);
        }
        else if (message.startsWith("REQUEST_FILE_MAP")) { // File map of the next node being requested
            processRequestFileMap(message);
        }
        else if (message.startsWith("SYNC_REQUEST")) {
            processSyncRequest();
        } else if (message.startsWith("IP_FROM_ID")) {
            processNextNodeIPResponse(message);
        }
    }

    private void processNextNodeIPResponse(String message) {
        String[] parts = message.split(":");
        this.nextNodeIP = parts[1];
    }

    /**
     * Possible way to receive previous agents fileMap. When the indication is 'A', the method is called and
     * the previous hostname is extracted. We serialize the agent, send it as serialized data to the previous node,
     * the previous node receives this and deserializes it and gets its fileMap.
     * Unable to test but doubt that it works.
     * @param message Message with the two previous hosts.
     * @throws IOException
     */

    private void processAgent(String message) throws IOException {
        SyncAgent receivedAgent;
        String previousIP = message.split(":")[1];
        try {
            // Serialize object to byte array
            byte[] serializedData = helpMethods.serializeObject(syncAgent);

            // Here you can send 'serializedData' over the network or save it to a file
            helpMethods.sendUnicast("Send agent", previousIP, Arrays.toString(serializedData), 8600);
            executor.submit(() -> receiveUnicast("receive agent", 8700));

            // Deserialize byte array back to object
            byte[] receivedData = message.getBytes();
            receivedAgent = (SyncAgent) helpMethods.deserializeObject(receivedData);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        nextFileMap =  receivedAgent.getFilesMap();
    }


    private void processRequestFileMap(String message) {
        String requesterIP = message.split(":")[1];
        logger.log(Level.INFO, "Request for file map received from: " + requesterIP);
        Map<String, Boolean> fileMap = syncAgent.getFilesMap();
        try {
            // Serialize the file map o a byte array
            byte[] serializedData = helpMethods.serializeObject(fileMap);
            // Send the serialized file map
            helpMethods.sendFileMap("Sending filemap from 'next node's SA' to requester SA", requesterIP, serializedData, Ports.fmPort);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error sending file map to requester", e);
        }
    }

    private void receiveFileMap() {
        try (DatagramSocket socket = new DatagramSocket(Ports.fmPort)) {
            logger.log(Level.INFO, "Connected to unicast receive socket: File Map purpose");

            // Create buffer for incoming data
            byte[] buffer = new byte[4096];

            // Receive file data and write to file
            while (true) {  // Keep listening indefinitely
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] receivedData = Arrays.copyOf(packet.getData(), packet.getLength());

                logger.log(Level.INFO, "File map received successfully");
                processFileMapMessage(receivedData);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to connect to server for file map", e);
        }
    }

    private Map<String, Boolean> processFileMapMessage(byte[] receivedData) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> receivedFileMap = (Map<String, Boolean>) helpMethods.deserializeObject(receivedData);
            // Sync the received file map with the next node's file map
            syncAgent.synchronizeWithNextNode(receivedFileMap);
            logger.log(Level.INFO, "File map synchronization started");
            return receivedFileMap;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void processSyncRequest() {
        new Thread(syncAgent).start();
        syncAgent.start();
        syncAgent.run();
    }

    public int getNumOfNodes() {
        return numOfNodes;
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
            logger.log(Level.INFO, "Post bootstrap process: " + IP + "previousID:" + previousID +
                    "nextID:" + nextID + "numOfNodes:" + numOfNodes);
        }

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

    private void processReplicate(String message){
        String[] parts = message.split(":");
        String nodeToReplicateTo = parts[1];
        String filename = parts[2];

        ft.transferFile(nodeToReplicateTo, filename,null);
    }

    private void processCreateLog(String message) {
        String[] parts = message.split(":");
        String localOwnerIP = parts[1];
        String filename = parts[2];

        updateLogFile(localOwnerIP, IP, filename);
    }

    // Create/Update a log file with file references when replicating a file
    public static void updateLogFile(String localOwnerIP, String replicatedOwnerIP, String filename) {
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

            // Optimization for later
            // The second of the file log seems redundant
            JSONObject fileInfo = new JSONObject();
            fileInfo.put("localOwnerIP", localOwnerIP);
            fileInfo.put("replicatedOwnerIP", replicatedOwnerIP);
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

        replicationComplete = true;
    }

    private void updateHashShutdown(int prevID, int nxtID) {
        if (currentID == prevID) {
            nextID = nxtID;
            syncAgent.updateNextNodeIP();
        }
        if (currentID == nxtID){
            previousID = prevID;
        }
    }


    // Update the hash
    public void updateHash(int receivedHash, String IP) throws IOException {
        /*
        if the new hash is smaller than the current next hash and bigger than this node's hash,
        or if the next hash is set to this node's hash
        we replace the next hash with the new received hash and notify it by sending the old one
        */

        if (receivedHash == nextID) {
            return;
        }

        if ((currentID < receivedHash && receivedHash < nextID) || currentID==nextID|| (nextID<currentID && (receivedHash>currentID || receivedHash<nextID) )){
            int oldNext= nextID;
            nextID = receivedHash;
            syncAgent.updateNextNodeIP();
            sendNodeResponse(true, IP, oldNext);
            logger.log(Level.INFO, "Next ID updated to: " + nextID + "And next IP updated to: " + nextNodeIP);
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
            logger.log(Level.INFO, "Previous ID updated to: " + previousID);
        }
    }

    public void sendNodeResponse(Boolean replacedNext, String nodeIP, int replacedHash) throws IOException {
        int port = 5231;
        try (Socket cSocket = new Socket(nodeIP, port);
             DataOutputStream out = new DataOutputStream(cSocket.getOutputStream())) {
            String msg = replacedNext ? "NEXT:" + replacedHash + ":" + currentID : "PREV:" + replacedHash + ":" + currentID;
            out.writeUTF(msg);
            out.flush();
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
                logger.log(Level.INFO, "Next and previous ID updated, previousID: "+ previousID + " Next: " + nextID);
            } else if (parts[0].equalsIgnoreCase("prev")) {
                nextID = Integer.parseInt(parts[2]);
                previousID = Integer.parseInt(parts[1]);
                logger.log(Level.INFO, "Next and previous ID updated, previousID: "+ previousID + " Next: " + nextID);
            }
        }
    }

    private void sendReplicatedFilesShutdown(String msg) {
        String[] parts = msg.split(":");
        String prevHost = parts[1];
        String prev2Host = parts[2];

        try {
            ft.stopListening();

            String fileString = new String(Files.readAllBytes(fileLog.toPath()));
            JSONObject jsonLog = new JSONObject(fileString);

            Iterator<String> keys = jsonLog.keys();
            while(keys.hasNext()) {
                String fileName = keys.next();
                JSONObject jsonEntry = jsonLog.getJSONObject(fileName);
                boolean prevNodeOwner;

                if(jsonEntry.getString("replicatedOwnerIP").equals(IP)) {
                    prevNodeOwner = (hash(jsonEntry.getString("localOwnerIP")) == previousID);

                    if (prevNodeOwner) {
                        logger.log(Level.INFO, "send to: "+ prev2Host +" ; file: " + fileName + " ; The local owner: "
                                +jsonEntry.getString("localOwnerIP"));
                        //send to previous node of previous node
                        ft.transferFile(prev2Host, fileName, jsonEntry.getString("localOwnerIP"));

                    } else {
                        logger.log(Level.INFO, "send to: "+ prevHost +" ; file: " + fileName +" ; The local owner: "
                                + jsonEntry.getString("localOwnerIP"));
                        //send to previous node , if previous is not the owner
                        ft.transferFile(prevHost, fileName, jsonEntry.getString("localOwnerIP"));
                    }
                    //Thread.sleep(1000);
                }
            }

            finishSending = true;

        } catch (IOException | JSONException e) {
            // Handle failure and start Failure agent
            handleFailure(this);
            throw new RuntimeException(e);
        }
    }

    public void receiveFailureAgent(Runnable agent) {
        Thread agentThread = new Thread(agent);
        agentThread.start();
        try {
            agentThread.join();
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "Error receiving failure agent", e);
        }
    }
    private void handleFailure(Node failedNode) {
        System.out.println("Handling failure of node " + failedNode.currentID);
        FailureAgent agent = new FailureAgent(failedNode, this, failedNode.currentID);
        receiveFailureAgent(agent);
    }


    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("Enter command: ");
            String command = scanner.nextLine();

            switch (command) {
                case "shutdown":
                    System.out.println("Shutting down");
                    shutdown();
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
                case "log":
                    helpMethods.getFiles("/root/logs");
                    helpMethods.displayLogContents("/root/logs/fileLog.json");
                case "sa":
                    System.out.println("Files in sync agent: " + syncAgent.getFilesMap());
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

    public String getIP() {
        return IP;
    }
}
