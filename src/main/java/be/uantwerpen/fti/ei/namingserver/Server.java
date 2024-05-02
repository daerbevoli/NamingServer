package be.uantwerpen.fti.ei.namingserver;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
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

    // Logger to log details in a try block for the file modification methods
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    // Map to save the hash corresponding to the node's ip
    private final ConcurrentHashMap<Integer, InetAddress> nodesMap = new ConcurrentHashMap<>();

    // Map to store filenames


    // File to write to and read from
    private final File jsonFile = new File("src/main/java/be/uantwerpen/fti/ei/namingserver/nodes.json");

    // Constructor to read the starting data from the JSON file
    public Server(){
        readJSONIntoMap();

        runFunctionsOnThreads(); // A possible way to use threads but needs to improve
    }

    // Thread executor
    public void runFunctionsOnThreads() {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Listen to multicast messages from nodes
        executor.submit(this::listenForNodesMulticast);

        // Listen to unicast messages from nodes
        executor.submit(this::listenNodeUnicast);

        // Shutdown the executor once tasks are completed
        executor.shutdown();
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
            int nodeID = nodeOfFile(fileHash);
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
    // It then returns the IP
    private void listenForNodesMulticast(){
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

                System.out.println("Received multicast message: " + message);
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
    }

    // This method listens for unicast messages from nodes
    // It then processes the message
    private void listenNodeUnicast(){
        try (DatagramSocket socket = new DatagramSocket(8000)){
            System.out.println("Connected to UDP socket");

            byte[] buffer = new byte[512];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                System.out.println("Received unicast message: " + message);
                processReceivedMessage(message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open server socket", e);
        }
    }

    private void processReceivedMessage(String message) {
        String[] parts = message.split(":");
        String command = parts[0];
        String nodeIP = parts[1];
        switch (command) {
            case "BOOTSTRAP":
                addNode(nodeIP);
                sendNumNodesUnicast(nodeIP);
                break;
            case "SHUTDOWN":
                removeNode(nodeIP);
                break;
            case "REPORT":
                int fileHash = Integer.parseInt(parts[2]);
                processFileReport(nodeIP, fileHash);
                break;
        }
    }

    // Process the file report sent by the node
    private void processFileReport(String nodeIP, int fileHash) {
        int replicatedNodeID = nodeOfFile(fileHash);
        InetAddress replicatedNodeIP = nodesMap.get(replicatedNodeID);
        if (replicatedNodeID < fileHash) { // Condition for replication
            try {
                // Create log with file references
                logger.log(Level.INFO,"Replication Node: " + replicatedNodeIP.getHostAddress() + " now owns file with fileHash: " + fileHash);
                System.out.println(logger.getLevel());
                // Notify the original node that it should handle the file replication
                InetAddress nodeAddress = InetAddress.getByName(nodeIP);
                sendUnicast(nodeAddress, "REPLICATE:" + replicatedNodeIP + fileHash);
            } catch (UnknownHostException e) {
                logger.log(Level.WARNING, "Unable to send unicast message", e);
            }
        }
    }

    // Send unicast message to a node
    public void sendUnicast(InetAddress targetIP, String message) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            System.out.println("Connected to UDP socket");

            byte[] buffer = message.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, targetIP, 8000);

            // Send the packet
            socket.send(packet);

            System.out.println("Message sent to the node");

        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server socket", e);
        }
    }

    // Receive unicast message from a node
    public void receiveUnicast() {
        try (DatagramSocket socket = new DatagramSocket(8000)) {
            System.out.println("Connected to UDP socket");

            byte[] buffer = new byte[512];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                System.out.println("Received unicast message: " + message);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server socket", e);
        }
    }

    // This method sends map size through port 8001 to port 8000 via localhost
    public void sendNumNodesUnicast(String targetIP){
        try(DatagramSocket socket = new DatagramSocket(null)){

            System.out.println("Connected to UDP socket");

            int mapSize = nodesMap.size();

            InetAddress group = InetAddress.getByName(targetIP);

            String size = String.valueOf(mapSize);
            byte[] buffer = size.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 8000);

            // Send the packet
            socket.send(packet);

            System.out.println("Number of nodes sent to the node");


        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open server socket", e);
        }
    }

    // Run the server
    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("Enter command: ");
            String command = scanner.nextLine();
            if (command.startsWith("removeNode")) {
                String[] parts = command.split(" ");
                String ip = parts[1];
                removeNode(ip);
            } else if (command.startsWith("addNode")) {
                String[] parts = command.split( " ");
                String ip = parts[1];
                addNode(ip);
            } else if (command.startsWith("getFile")) {
                String[] parts = command.split(" ");
                String filename = parts[1];
                ResponseEntity<String> response = getHostname(filename);
                System.out.println(response.getBody()); // Print the response
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
