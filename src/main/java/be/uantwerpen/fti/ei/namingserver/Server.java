package be.uantwerpen.fti.ei.namingserver;

import ch.qos.logback.core.joran.sanity.Pair;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    // Map to save the hash corresponding to the node's hostname
    private final ConcurrentHashMap<Integer, InetAddress> nodesMap = new ConcurrentHashMap<>();

    // File to write to and read from
    private final File jsonFile = new File("src/main/java/be/uantwerpen/fti/ei/namingserver/nodes.json");

    // Constructor to read the starting data from the JSON file
    public Server(){
        //readJSONIntoMap();
    }

    // Hash function provided by the teachers
    /*
        A problem that this hash function brings is that, because of the limiting of the range,
        a lot of hostnames produce the same hash code.
        E.g. the 192.168.0.1 to 192.168.0.8 produce the hash code 16810
        How to resolve? -> ask TA after Easter break
     */
    public int hash(String name){
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (name.hashCode() + max) * (32768/(max + Math.abs(min)));
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

    // Add a node by giving the hostname as parameter
    // First read from the JSON file to get the map
    // Modify the map and save it to the JSON file
    @PostMapping("/add/{ip}")
    public ResponseEntity<String> addNode(@PathVariable String ip){
        readJSONIntoMap();
        try {
            int id = hash(ip);
            // For some reason hostnames 192.168.0.10-99 have the same hash
            if (nodesMap.containsKey(id)){
                return ResponseEntity.ok(ip + " already in the network\n");
            } else {
                nodesMap.put(id, InetAddress.getByName(ip));
                return ResponseEntity.ok(ip + " successfully added to the network\n");
            }
        } catch (UnknownHostException e) {
            return ResponseEntity.ok("Error occurred while adding entry: " + e.getMessage());
        } finally {
            saveMapToJSON();
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

        // calculate node ID
        int nodeID = nodeOfFile(fileHash);

        // return hostname
        return ResponseEntity.ok("The hashcode of the file is " + fileHash + "\nThe nodeID is " + nodeID +
                "\nThe hostname is " + nodesMap.get(nodeID).getHostAddress());

    }

    // Endpoint to trigger discovery message
    @GetMapping("/discover")
    public ResponseEntity<String> triggerDiscovery() {
        Discovery discovery = new Discovery();
        discovery.sendDiscoveryMessage();
        return ResponseEntity.ok("Discovery message sent.");
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

    private String listenMulticast(){
        try {
            // Create a multicast socket
            MulticastSocket socket = new MulticastSocket(3000);

            // Join the multicast group
            InetAddress group = InetAddress.getByName("224.0.0.1");
            socket.joinGroup(group);

            // Create buffer for incoming data
            byte[] buffer = new byte[1024];

            // Receive file data and write to file
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String message = new String(packet.getData(), 0, packet.getLength());
            String[] parts = message.split(":");

            System.out.println("Received message: " + message);

            return parts[1];



        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open socket", e);
        }
        return null;
    }

    public static void main(String[] args){
        Server serv = new Server();
        String node = serv.listenMulticast();
        System.out.println("Node: " + node);
        serv.addNode(node);
    }


}
