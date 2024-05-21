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
@RestController
@RequestMapping("/NS") // NS = Naming Server
public class Server {

    private final String IP;

    // Logger to log details in a try block for the file modification methods
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    // Map to save the hash corresponding to the node's ip
    private final ConcurrentHashMap<Integer, InetAddress> nodesMap = new ConcurrentHashMap<>();

    // File to write to and read from
    private final File jsonFile = new File("src/main/java/be/uantwerpen/fti/ei/namingserver/nodes.json");

    // nodes flag to check whether a new node has entered the topology
    boolean nodeAdded = false;

    // list to have all report messages
    List<String> reportList = new ArrayList<>();

    // Constructor to read the starting data from the JSON file
    public Server(){
        this.IP = helpMethods.findLocalIP();
        logger.log(Level.INFO, "Server IP: " + IP);
        clearMap(); // clear the map when server starts up

        runFunctionsOnThreads(); // A possible way to use threads but needs to improve
    }

    // Thread executor
    public void runFunctionsOnThreads() {
        ExecutorService executor = Executors.newFixedThreadPool(3);

        // Listen to multicast messages from nodes
        executor.submit(this::listenForNodesMulticast);

        // Listen to unicast messages from nodes
        executor.submit(this::receiveUnicast);

        Runtime.getRuntime().addShutdownHook(new Thread(this::clearMap));

        // Shutdown the executor once tasks are completed
        executor.shutdown();
    }

    private void clearMap() {
        nodesMap.clear();
        saveMapToJSON();
        logger.log(Level.INFO, "Map cleared");
    }

    @PostMapping("/clearMap")
    public ResponseEntity<String> clearMapREST() {
        clearMap();
        return ResponseEntity.ok("Map cleared successfully");
    }

    // Hash function provided by the teachers
    public int hash(String IP){
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (IP.hashCode() + max) * (32768/(max + Math.abs(min)));
        return (int) hashValue;

    }

    /* Implementation of following algorithm :
    Suppose N is the collection of nodes with a hash smaller than the hash of the
    filename. Then the node with the smallest difference between its hash and the file
    hash is the owner of the file. If N is empty, the node with the biggest hash stores
    the requested file.
     */
    private int nodeOfFile(int fileHash){

        ConcurrentHashMap<Integer, InetAddress> N = new ConcurrentHashMap<>();

        for (Map.Entry<Integer, InetAddress> entry : nodesMap.entrySet()){
            if (entry.getKey() <= fileHash){
                N.put(entry.getKey(), entry.getValue());
            }
        }

        if (N.isEmpty()){
            return nodesMap.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
        } else {
            return N.keySet().stream().min(Comparator.comparingInt(key -> Math.abs(key - fileHash))).get();
        }

    }

    private int nodeOfFile2(int fileHash) {
        // Find all nodes with a hash smaller than or equal to the file hash
        List<Integer> nodeKeys = nodesMap.keySet().stream()
                .filter(key -> key < fileHash)
                .toList();

        if (nodeKeys.isEmpty()) {
            // If no such nodes exist, return the node with the largest hash
            return nodesMap.keySet().stream().mapToInt(Integer::intValue).max()
                    .orElseThrow(NoSuchElementException::new);
        } else {
            // Find the node with the smallest difference between its hash and the file hash
            return nodeKeys.stream().min(Comparator.comparingInt(key -> Math.abs(key - fileHash)))
                    .orElseThrow(NoSuchElementException::new);
        }
    }


    // Add a node by giving the ip as parameter
    // First read from the JSON file to get the map
    // Modify the map and save it to the JSON file
    @PostMapping("/add/{ip}")
    public ResponseEntity<String> addNode(@PathVariable String ip){
        logger.log(Level.INFO, "Attempting to add node with IP: " + ip);
        readJSONIntoMap();
        int id = hash(ip);
        if (nodesMap.containsKey(id)) {
            logger.log(Level.INFO, ip + "already in the network");
            return ResponseEntity.ok(ip + " already in the network\n");
        } else {
            try {
                nodesMap.put(id, InetAddress.getByName(ip));
                saveMapToJSON();  // Save every time a new node is added
                nodeAdded = true;
                logger.log(Level.INFO, ip + " successfully added to the network");
                return ResponseEntity.ok(ip + " successfully added to the network\n");
            } catch (UnknownHostException e) {
                logger.log(Level.WARNING, "Error occurred while adding entry", e);
                return ResponseEntity.ok("Error occurred while adding entry: " + e.getMessage());
            }
        }
    }


    // Delete a node from the map
    @DeleteMapping("/remove/{ip}")
    public ResponseEntity<String> removeNode(@PathVariable String ip){
        readJSONIntoMap();
        try {
            int id = hash(ip);
            // For some reason hostnames between certain intervals have the same hashcode
            if (nodesMap.containsKey(id)){
                nodesMap.remove(id);
                return ResponseEntity.ok(ip + " successfully removed from the network\n");
            } else {
                return ResponseEntity.ok(ip + " not in the network\n" + id);
            }
        } catch (Exception e) {
            return ResponseEntity.ok("Error occurred while removing entry: " + e.getMessage());
        } finally {
            saveMapToJSON();
        }
    }

    // Get the hostname of the node that hosts the file
    @GetMapping("/get/{filename}")
    public ResponseEntity<String> getHostname(@PathVariable String filename){

        // get the hash of the filename
        int fileHash = hash(filename);
        try {
            // calculate node ID
            int nodeID = nodeOfFile2(fileHash);
            // return hostname

            return ResponseEntity.ok("The hashcode of the file is " + fileHash + "\nThe nodeID is " + nodeID +
                    "\nThe hostname is " + nodesMap.get(nodeID).getHostName());

        } catch (NoSuchElementException e) {
            return ResponseEntity.ok("Could not find a suitable node for the file: " + filename);
        }
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
        try (DatagramSocket socket = new DatagramSocket(8000)) {
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
                helpMethods.sendUnicast("send number of nodes", nodeIP, "NUMNODES" +":"+ nodesMap.size(), 8300);
                break;
            case "SHUTDOWN":
                removeNode(nodeIP);
                logger.log(Level.INFO, "Node with IP: " + nodeIP + " has shut down and been removed from the network");
                break;
            case "REPORT":
                reportList.add(message);
                if (nodesMap.size() == 3) {
                    for (String reportMessage : reportList){
                        String reportNodeIP = reportMessage.split(":")[1];
                        int fileHash = Integer.parseInt(reportMessage.split(":")[2]);
                        String filename = reportMessage.split(":")[3];
                        processFileReport(reportNodeIP, fileHash, filename);
                    }
                }
                break;
        }
    }

    // Process the file report sent by the node
    private void processFileReport(String nodeIP, int fileHash, String filename) {
        int replicatedNodeID = nodeOfFile2(fileHash);
        InetAddress replicatedNodeIP = nodesMap.get(replicatedNodeID);

        String replicateMessage = "REPLICATE" + ":" +
                replicatedNodeIP.getHostName() + ":" + filename + ":" + fileHash;

        String logMessage = "LOG" + ":" + nodeIP + ":" + filename + ":" + fileHash;

        helpMethods.sendUnicast("file replication", nodeIP, replicateMessage, 8100);
        // Log the ownership of the file
        logger.log(Level.INFO, "Replication Node: " + replicatedNodeIP.getHostName() + " " +
                "now owns file with filename: " + filename + " and hash: " + fileHash);


        // Notify the replicated node that it should create a file log
        helpMethods.sendUnicast("file log", replicatedNodeIP.getHostName(), logMessage, 8700);
    }




    // Run the server
    public void run() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("Enter command: ");
            String command = scanner.nextLine();
            String[] parts = command.split(" ");
            if (command.startsWith("removeNode")) {
                String ipRemove = parts[1];
                removeNode(ipRemove);
            } else if (command.startsWith("addNode")) {
                String ipAdd = parts[1];
                addNode(ipAdd);
            } else if (command.startsWith("getFile")) {
                String filename = parts[1];
                ResponseEntity<String> response = getHostname(filename);
                System.out.println(response.getBody()); // Print the response
            } else if (command.startsWith("clearMap")) {
                clearMap();
            } else {
                System.out.println("Invalid command");
            }
            }
        }

        public void sendIPOfNewReplicatedNode(int hashOfShutdownNode, boolean prevNodeOwner)
        {
            ArrayList<Integer> hashes =new ArrayList<>(nodesMap.keySet());
            Collections.sort(hashes);
            int index= hashes.indexOf(hashOfShutdownNode);
            String ipOfFileReceiver;
            int indexShutdownReceiver;
            indexShutdownReceiver = prevNodeOwner? Math.abs((index-2)%hashes.size()) : Math.abs((index-1)%hashes.size());
            ipOfFileReceiver= nodesMap.get(hashes.get(indexShutdownReceiver)).getHostAddress();
            helpMethods.sendUnicast("Send IP for new replicated owner after shutdown", nodesMap.get(hashOfShutdownNode).getHostAddress(), "ReceiverIpShutdown:"+ipOfFileReceiver, 9020 );



        }
    public static void main(String[] args){
        Server server = new Server();
        server.run();
    }
}
