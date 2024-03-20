package be.uantwerpen.fti.ei.namingserver;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

public class Server {

    // Map to save the hash corresponding to its IP
    private final ConcurrentHashMap<Integer, InetAddress> map = new ConcurrentHashMap<>();

    // unsure whether a constructor should be used
    public Server(){

        // function to read in a json file yet to implement

        try {

            // example values
            map.put(1, InetAddress.getByName("192.168.1.1"));
            map.put(2, InetAddress.getByName("192.168.1.2"));
            map.put(3, InetAddress.getByName("192.168.1.3"));

        } catch (UnknownHostException e){
            e.printStackTrace();
        }

    }

    private int hash(String name){
        int min = -2147483647;
        int max = 2147483647;
        return (name.hashCode() + max) * (32768/max + Math.abs(min)); // why is this warning given?
    }

    /* Implementation of following algo comes here :
    Suppose N is the collection of nodes with a hash smaller than the hash of the
    filename. Then the node with the smallest difference between its hash and the file
    hash is the owner of the file. If N is empty, the node with the biggest hash stores
    the requested file.
     */
    private int nodeOfFile(int fileHash){
        return 0;
    }

    private void get(){
        System.out.println("hello");
    }

    @GetMapping("/get/{filename}")
    public String getIp(@PathVariable String filename){

        // get the hash of the filename
        int fileHash = hash(filename);

        // calculate node ID
        int nodeID = nodeOfFile(fileHash);

        // return node ID ip
        return map.get(nodeID).getHostAddress(); // returns ip address as a name


    }





}
