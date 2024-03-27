package be.uantwerpen.fti.ei.namingserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/NS") // NS = Naming Server
public class Server {

    // Logger to log details in a try block for the file modification functions
    private static final Logger logger = Logger.getLogger(Server.class.getName());
    // Map to save the hash corresponding to the node's IP
    private final ConcurrentHashMap<Integer, InetAddress> map = new ConcurrentHashMap<>();

    // File to store and read from
    private final File jsonFile = new File("src/main/java/be/uantwerpen/fti/ei/namingserver/ips.json");

    // Constructor to read the starting data from the JSON file
    public Server(){
        readJSONIntoMap();
    }
    public int hash(String name){
        int min = -2147483647;
        int max = 2147483647;
        //return (name.hashCode() + max) * (32768/max + Math.abs(min)); // this does not work

        // The range is [-2147483647, 2147483647], we want to map it to [0, 32768)
        // mappedValue = (value - minValue) * (newRange / oldRange)
        // Here, minValue = -2147483647, maxValue = 2147483647, newRange = 32768, oldRange = maxValue - minValue
        // L suffix stands for long int
        double h = (name.hashCode() + 2147483647L) * (32768.0 / 4294967294L);
        return (int)h;


    }


    /* Implementation of following algorithm :
    Suppose N is the collection of nodes with a hash smaller than the hash of the
    filename. Then the node with the smallest difference between its hash and the file
    hash is the owner of the file. If N is empty, the node with the biggest hash stores
    the requested file.
     */
    private int nodeOfFile(int fileHash){
        ConcurrentHashMap<Integer, InetAddress> N = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, InetAddress> entry : map.entrySet()){
            if (entry.getKey() < fileHash){
                N.put(entry.getKey(), entry.getValue());
            }
        }
        if (N.isEmpty()){
            return map.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
        } else {
            return map.keySet().stream().min(Comparator.comparingInt(key -> Math.abs(key - fileHash))).get();
        }

    }

    // Add a node by giving the hostname as parameters
    // First read from the JSON file to get the map
    // modify the map and save it to the JSON file
    @PostMapping("/add/{ip}")
    public ResponseEntity<String> addNode(@PathVariable String ip){
        readJSONIntoMap();
        try {
            int id = hash(ip);
            // For some reason hostnames 192.168.0.10-99 have the same hash
            if (map.containsKey(id)){
                return ResponseEntity.ok(ip + " already in the network\n");
            } else {
                map.put(id, InetAddress.getByName(ip));
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
            if (map.containsKey(id)){
                map.remove(id);
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

    @GetMapping("/get/{filename}")
    public ResponseEntity<String> getHostname(@PathVariable String filename){

        // get the hash of the filename
        int fileHash = hash(filename);


        // calculate node ID
        int nodeID = nodeOfFile(fileHash);


        // return hostname
        return ResponseEntity.ok("The hashcode of the file is " + fileHash + "\nThe nodeID is " + nodeID +
                "\nThe hostname is " + map.get(nodeID).getHostAddress());

    }

    public void readJSONIntoMap(){
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> stringMap = mapper.readValue(jsonFile, HashMap.class);

            for (HashMap.Entry<String, String> entry : stringMap.entrySet()) {
                Integer key = Integer.parseInt(entry.getKey());
                InetAddress value = InetAddress.getByName(entry.getValue());
                map.put(key, value);
            }

            } catch (Exception e){
            logger.log(Level.WARNING, "An error occurred when reading from JSON file", e);
        }
    }

    public void saveMapToJSON(){
        try {
            ObjectMapper mapper = new ObjectMapper();

            Map<String, String> stringMap = new HashMap<>();

            for (Map.Entry<Integer, InetAddress> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                String value = entry.getValue().getHostAddress();
                stringMap.put(key, value);
            }

            mapper.writeValue(jsonFile, stringMap);
        } catch (IOException e){
            logger.log(Level.WARNING, "An error occurred when writing to JSON file", e);

        }
    }
}
