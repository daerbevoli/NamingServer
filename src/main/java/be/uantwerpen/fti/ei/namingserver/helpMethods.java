package be.uantwerpen.fti.ei.namingserver;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class helpMethods {

    private static final Logger logger = Logger.getLogger(helpMethods.class.getName());


    // Find the local ip of the remote node
    // Find the local hostname of the remote node
    // Used hostname because hash function returned same hash code for IPs in similar range
    public static String findLocalIP() {

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
            //logger.log(Level.WARNING, "Unable to find local IP", e);
        }
        return "127.0.0.1"; // Default IP address localhost
    }

    public static void sendMulticast(String purpose, String message, int port) {
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName("224.0.0.1"); // Multicast group address
            logger.log(Level.INFO,"connected to multicast send socket: " + purpose);

            byte[] buffer = message.getBytes();

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, port);

            // Send the packet to the multicast group
            socket.send(packet);

            logger.log(Level.INFO, "Multicast message: " + purpose + ", sent successfully.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to connect to multicast send socket: " + purpose, e);
        }
    }

    public static void sendUnicast(String purpose, String targetIP, String message, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {

            logger.log(Level.INFO,"Connected to unicast send socket: " + purpose);

            byte[] buffer = message.getBytes();

            InetAddress targetIp = InetAddress.getByName(targetIP); // Uses the hostname of the target node (getByName)

            // Create a DatagramPacket
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, targetIp, port);

            // Send the packet
            socket.send(packet);

            logger.log(Level.INFO,"Unicast message: " + purpose + ", sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "unable to open unicast send socket: " + purpose, e);
        }
    }

    // Add a local file to the node
    public static void addFile(String filename, String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory if it does not exist
        }
        File file = new File (directoryPath + "/" + filename);
        try {
            if (file.createNewFile()) {
                logger.log(Level.INFO, filename + " created successfully at " + file.getPath());

            } else {
                logger.log(Level.INFO, "File already exists at" + file.getPath());
            }
        } catch (IOException e) {
            logger.log(Level.INFO, "Error creating the file: " + e.getMessage());
        }
    }

    public static void getFiles(String path){
        File dir = new File(path);

        File[] filesList = dir.listFiles();

        if (filesList != null) {
            for (File file : filesList) {
                System.out.println("File: " + file.getName());
            }
        } else {
            System.out.println("The specified directory does not exist or is not a directory.");
        }
    }

    public static void displayLogContents(String filePath) {
        try {
            // Read content from JSON file
            String content = new String(Files.readAllBytes(Paths.get(filePath)));

            // Parse JSON content into JSONObject
            JSONObject json = new JSONObject(content);

            // Get an iterator over the keys
            Iterator<String> keysIterator = json.keys();
            while (keysIterator.hasNext()) {
                String key = keysIterator.next();
                Object value = json.get(key);
                System.out.println("Key: " + key + ", Value: " + value);
            }

        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error parsing JSON: " + e.getMessage());
        }
    }



    // ping method to check whether a connection with a node can be made
    public static void ping(InetAddress address){
        try (Socket socket = new Socket(address, 0)){

            logger.log(Level.INFO, "Connected to the node");

        } catch (IOException e){
            logger.log(Level.SEVERE, "Failed to connect to node", e);
        }
    }



}
