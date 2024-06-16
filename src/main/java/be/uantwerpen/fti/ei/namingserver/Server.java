package be.uantwerpen.fti.ei.namingserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class represents a Naming Server (NS) for managing nodes and file distribution in a distributed system.
 * It provides functionality to add and remove nodes, as well as to retrieve the hostname associated with a given filename.

 * The NS utilizes a hash-based algorithm to determine the node responsible for storing a file based on its hashcode.
 * Additionally, it employs a JSON file to persist node information for consistency across sessions.
 */

public class Server {

    // Server IP
    private final String IP;

    // Logger to log details in a try block
    private final Logger logger = Logger.getLogger(Server.class.getName());

    // Map to save the hash corresponding to the node's ip
    private final ConcurrentHashMap<Integer, InetAddress> nodesMap = new ConcurrentHashMap<>();

    // File to write to and read from
    private final File jsonFile = new File("src/main/java/be/uantwerpen/fti/ei/namingserver/nodes.json");

    // Executor to execute tasks on separate threads
    private final ExecutorService executor;

    // Constructor to read the starting data from the JSON file
    public Server(){
        this.IP = helpMethods.findLocalIP();
        logger.log(Level.INFO, "Server IP: " + IP);

        nodesMap.clear(); // clear the map when server starts up

        // Executor to run tasks on different threads
        executor = Executors.newFixedThreadPool(3);
        runFunctionsOnThreads();

    }

    // Thread executor
    public void runFunctionsOnThreads() {

        // Listen to multicast messages from nodes
        executor.submit(this::listenForNodesMulticast);

        // Listen to unicast messages from nodes
        executor.submit(this::receiveUnicast);
        executor.submit(this::receiveRequest);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    }

    private void shutdown(){
        nodesMap.clear();
        executor.shutdown();
    }


    /* Implementation of following algorithm :
    Suppose N is the collection of nodes with a hash smaller than the hash of the
    filename. Then the node with the smallest difference between its hash and the file
    hash is the owner of the file. If N is empty, the node with the biggest hash stores
    the requested file.
     */
    private int nodeOfFile(int fileHash, String sameIP) {

        // Find all nodes with a hash smaller than or equal to the file hash but make sure it's not your own hash
        List<Integer> nodeKeys = nodesMap.keySet().stream()
                .filter(key -> key < fileHash)
                .toList();

        int replNodeId;
        if (nodeKeys.isEmpty()) {

            // If no such nodes exist, return the node with the largest hash
            replNodeId = nodesMap.keySet().stream().mapToInt(Integer::intValue).max()
                    .orElseThrow(NoSuchElementException::new);

        } else {

            // Find the node with the smallest difference between its hash and the file hash
            replNodeId =  nodeKeys.stream().min(Comparator.comparingInt(key -> Math.abs(key - fileHash)))
                    .orElseThrow(NoSuchElementException::new);
        }

        // If host is target, return previous id
        if (helpMethods.hash(sameIP) == replNodeId){
            return getPreviousID(nodesMap.get(replNodeId).getHostName());
        } else {
            return replNodeId;
        }
    }

    // Add a node by giving the ip as parameter
    // First read from the JSON file to get the map
    // Modify the map and save it to the JSON file
    public boolean addNode(String ip){
        boolean nodeAdded = false;
        logger.log(Level.INFO, "Attempting to add node with IP: " + ip);
        readJSONIntoMap();
        int id = helpMethods.hash(ip);
        if (nodesMap.containsKey(id)) {
            logger.log(Level.INFO, ip + "already in the network");
        } else {
            try {
                nodesMap.put(id, InetAddress.getByName(ip));
                saveMapToJSON();  // Save every time a new node is added
                logger.log(Level.INFO, ip + " successfully added to the network");
                nodeAdded = true;
            } catch (UnknownHostException e) {
                logger.log(Level.WARNING, "Error occurred while adding entry", e);
            }
        }
        return nodeAdded;
    }


    // Delete a node from the map
    public boolean removeNode(String ip){
        boolean nodeRemoved  = false;
        readJSONIntoMap();
        int id = helpMethods.hash(ip);
        if (nodesMap.containsKey(id)) {
            nodesMap.remove(id);
            nodeRemoved = true;
        }
        saveMapToJSON();
        return nodeRemoved;
    }

    // Get the hostname of the node that hosts the file
    public String getFileHost(@PathVariable String filename){
        String host = "";
        // get the hash of the filename
        int fileHash = helpMethods.hash(filename);
        try {
            // calculate node ID
            int nodeID = nodeOfFile(fileHash, IP);
            // return hostname

            host = nodesMap.get(nodeID).getHostName();

        } catch (NoSuchElementException e) {
            logger.log(Level.WARNING, "Unable to find host for file");
        }
        return host;
    }

    /*
        The File consists of key-value pairs of type String, so when reading in the file, we get String objects.
        To resolve this, we read the pairs into a String map after which we take these pairs, convert them
        and put them into the nodesMap.
     */
    public void readJSONIntoMap(){
        if (jsonFile.length() == 0){
            return;
        }
        try {

            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> stringMap = mapper.readValue(jsonFile, HashMap.class);

            for (HashMap.Entry<String, String> entry : stringMap.entrySet()) {
                Integer key = Integer.parseInt(entry.getKey());
                InetAddress value = InetAddress.getByName(entry.getValue());
                nodesMap.put(key, value);
            }

            } catch (Exception e){
            logger.log(Level.WARNING, "An error occurred when reading from JSON file", e);
        }
    }

    // We do the inverse when writing to the JSON file.
    public void saveMapToJSON(){
        try {

            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> stringMap = new HashMap<>();

            for (Map.Entry<Integer, InetAddress> entry : nodesMap.entrySet()) {
                String key = entry.getKey().toString();
                String value = entry.getValue().getHostAddress();
                stringMap.put(key, value);
            }

            mapper.writeValue(jsonFile, stringMap);

        } catch (IOException e){
            logger.log(Level.WARNING, "An error occurred when writing to JSON file", e);

        }
    }

    // This method listen to port 3000 for messages in the form COMMAND:hostname
    // It then processes the received message
    private void listenForNodesMulticast(){
        try (MulticastSocket socket = new MulticastSocket(3000)){
            logger.log(Level.INFO, "connected to multicast network");

            // Join the multicast group
            InetAddress group = InetAddress.getByName("224.0.0.1");
            socket.joinGroup(group);

            // Create buffer for incoming data
            byte[] buffer = new byte[512];

            while (true) {  // Keep listening indefinitely
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                logger.log(Level.INFO, "Received multicast message: " + message);
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }

    // Receive unicast message from a node
    // It then processes the message
    public void receiveUnicast() {
        try (DatagramSocket socket = new DatagramSocket(Ports.unicastPort)) {
            logger.log(Level.INFO, "Connected to unicast receive socket");

            byte[] buffer = new byte[512];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                logger.log(Level.INFO, "Received unicast message: " + message);
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server unicast socket", e);
        }
    }

    // Receive unicast message from a node
    // It then processes the message
    public void receiveRequest() {
        try (DatagramSocket socket = new DatagramSocket(Ports.nextNodeIPPort)) {
            logger.log(Level.INFO, "Connected to unicast receive socket");

            byte[] buffer = new byte[512];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                logger.log(Level.INFO, "Received unicast message: " + message);
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server unicast socket", e);
        }
    }



    private void processReceivedMessage(String message) {
        String[] parts = message.split(":");
        String command = parts[0];
        String nodeIP = parts[1];
        switch (command) {
            case "BOOTSTRAP":
                addNode(nodeIP);
                helpMethods.sendUnicast("send number of nodes", nodeIP,
                        "NUMNODES" + ":" + nodesMap.size(), Ports.nnPort);
                break;
            case "SHUTDOWN":
                removeNode(nodeIP);
                logger.log(Level.INFO, "Node with IP: " + nodeIP + " has shut down and been removed from the network");
                break;
            case "REPORT":
                int fileHash = Integer.parseInt(parts[2]);
                String filename = parts[3];
                processFileReport(nodeIP, fileHash, filename);
                break;
            case "AIP":
                String indication = parts[2];
                sendIPOfPrevNodes(nodeIP, indication);
                break;
            case "GET_IP_FROM_ID":
                int nextID = Integer.parseInt(parts[2]);
                String nextIP = getIPFromID(nextID);
                helpMethods.sendUnicast("IP_FROM_ID", nodeIP, "IP_FROM_ID" + ":" + nextIP, Ports.unicastPort);
                break;
        }
    }

    // Process the file report sent by the node
    private void processFileReport(String nodeIP, int fileHash, String filename) {
        if (nodesMap.size() <= 1){
            return;
        }
        int replicatedNodeID = nodeOfFile(fileHash, nodeIP);
        InetAddress replicatedNodeIP = nodesMap.get(replicatedNodeID);

        String replicateMessage = "REPLICATE" + ":" +
                replicatedNodeIP.getHostName() + ":" + filename + ":" + fileHash;

        String logMessage = "LOG" + ":" + nodeIP + ":" + filename + ":" + fileHash;

        helpMethods.sendUnicast("file replication", nodeIP, replicateMessage, Ports.replPort);

        // Log the ownership of the file
        logger.log(Level.INFO, "Replication Node: " + replicatedNodeIP.getHostName() + " " +
                "now owns file with filename: " + filename + " and hash: " + fileHash);

        // Notify the replicated node that it should create a file log
        helpMethods.sendUnicast("file log", replicatedNodeIP.getHostName(), logMessage, Ports.logPort);
    }

    private int getPreviousID(String IP){
        ArrayList<Integer> hashes = new ArrayList<>(nodesMap.keySet());
        Collections.sort(hashes);

        int index = hashes.indexOf(helpMethods.hash(IP));
        int indexPrevNode= (index-1) >= 0 ? (index-1) : hashes.size() + (index-1);

        return hashes.get(indexPrevNode);
    }


    public void sendIPOfPrevNodes(String ip, String indication) {
        String ipOfPrev = nodesMap.get(getPreviousID(ip)).getHostName();
        String ipOf2Prev = nodesMap.get(getPreviousID(ipOfPrev)).getHostName();
        helpMethods.sendUnicast("Send IP of previous node and its previous node", ip,
                "RIP:" + ipOfPrev + ":" + ipOf2Prev + ":" + indication, 9020);

    }

    public String getIPFromID(int id) {
        InetAddress address = nodesMap.get(id);
        return address != null ? address.getHostAddress() : null;
    }

    // Method to get the IP of the next node
    public String getNextNodeIP(int currentID) {
        ArrayList<Integer> hashes = new ArrayList<>(nodesMap.keySet());
        Collections.sort(hashes);

        int currentIndex = hashes.indexOf(currentID);
        if (currentIndex == -1 || hashes.size() <= 1) {
            logger.log(Level.WARNING, "Current node not found or insufficient nodes to determine next node");
            return null;
        }
        int nextIndex = (currentIndex + 1) % hashes.size();
        int nextID = hashes.get(nextIndex);
        return nodesMap.get(nextID).getHostAddress();
    }

    // Run the server and handle user commands
    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("Enter command: ");
            String command = scanner.nextLine();
            String[] parts = command.split(" ");

            // Check if the command is empty
            if (parts.length == 0) {
                System.out.println("Invalid command");
                continue;
            }

            // Process the command based on the first word
            String action = parts[0];

            switch (action) {
                case "removeNode":
                    if (parts.length < 2) {
                        System.out.println("Usage: removeNode <IP>");
                        break;
                    }
                    String ipToRemove = parts[1];
                    removeNode(ipToRemove);
                    break;

                case "addNode":
                    if (parts.length < 2) {
                        System.out.println("Usage: addNode <IP>");
                        break;
                    }
                    String ipToAdd = parts[1];
                    addNode(ipToAdd);
                    break;

                case "getFile":
                    if (parts.length < 2) {
                        System.out.println("Usage: getFile <filename>");
                        break;
                    }
                    String filename = parts[1];
                    System.out.println(getFileHost(filename)); // Print the response
                    break;

                case "clear":
                    nodesMap.clear();
                    break;

                default:
                    System.out.println("Invalid command");
                    break;
            }
        }
    }


    public static void main(String[] args){
        Server server = new Server();
        server.run();
    }
}
