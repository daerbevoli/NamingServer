package be.uantwerpen.fti.ei.namingserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
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
    private final Logger logger = Logger.getLogger(Server.class.getName());

    // Map to save the hash corresponding to the node's ip
    private final ConcurrentHashMap<Integer, InetAddress> nodesMap = new ConcurrentHashMap<>();

    // File to write to and read from
    private final File jsonFile = new File("src/main/java/be/uantwerpen/fti/ei/namingserver/nodes.json");

    private ExecutorService executor;

    private Map<String, Map<String, Map<String, String>>> receivedFiles;

    // Constructor to read the starting data from the JSON file
    public Server(){
        this.IP = helpMethods.findLocalIP();
        logger.log(Level.INFO, "Server IP: " + IP);

        clearMap(); // clear the map when server starts up

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

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    }

    private void clearMap() {
        nodesMap.clear();
        saveMapToJSON();
        logger.log(Level.INFO, "Map cleared");
    }

    private void shutdown(){
        clearMap();
        executor.shutdown();
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
    private int nodeOfFile(int fileHash, String sameIP) {

        // Find all nodes with a hash smaller than or equal to the file hash but make sure it's not your own hash
        List<Integer> nodeKeys = nodesMap.keySet().stream()
                .filter(key -> key < fileHash && key != hash(sameIP))
                .toList();

        if (nodeKeys.isEmpty()) {

            // If no such nodes exist, return the node with the largest hash
            int largestNodeID = nodesMap.keySet().stream().mapToInt(Integer::intValue).max()
                    .orElseThrow(NoSuchElementException::new);

            int smallestNodeID = nodesMap.keySet().stream().mapToInt(Integer::intValue).min()
                    .orElseThrow(NoSuchElementException::new);

            if (hash(sameIP) == largestNodeID){
                return smallestNodeID;
            } else {
                return largestNodeID;
            }
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
            int nodeID = nodeOfFile(fileHash, IP);
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
                receiveFileLog();
                removeNode(nodeIP);
                logger.log(Level.INFO, "Node with IP: " + nodeIP + " has shut down and been removed from the network");
                break;
            case "REPORT":
                int fileHash = Integer.parseInt(parts[2]);
                String filename = parts[3];
                if (parts[4].equals("X")){
                    nodeIP = receivedFiles.get(filename).get("localOwnerIP").toString();
                }
                processFileReport(nodeIP, fileHash, filename);
                break;
        }
    }

    private void receiveFileLog(){
        try {
            // Create a server socket bound to the specified port
            ServerSocket serverSocket = new ServerSocket(450);

            System.out.println("Server waiting for connections...");

            // Accept incoming connections in a loop
            while (true) {
                // Accept the connection from the client
                Socket socket = serverSocket.accept();

                System.out.println("Connection established with " + socket.getInetAddress());

                // Create an input stream to read data from the socket
                InputStream inputStream = socket.getInputStream();

                // Read the file name from the client
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String fileName = reader.readLine();

                // Read data from the socket and store it in a byte array
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }

                // Convert received byte array to JSON string
                String jsonContent = new String(byteArrayOutputStream.toByteArray());

                // Parse JSON string using Jackson ObjectMapper
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Map<String, String>> fileData = mapper.readValue(jsonContent, Map.class);

                // Store the received file data in the map
                receivedFiles.put(fileName, fileData);

                // Close the streams and socket
                byteArrayOutputStream.close();
                inputStream.close();
                socket.close();

                System.out.println("File received successfully");
            }
        } catch (IOException e) {
            e.printStackTrace();
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

    public static void main(String[] args){
        Server server = new Server();
        server.run();
    }
}
