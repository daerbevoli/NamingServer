package be.uantwerpen.fti.ei.namingserver;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class helpMethods {

    private static final Logger logger = Logger.getLogger(helpMethods.class.getName());

    // Hash function provided by the teachers
    public static int hash(String IP){
        double max = Integer.MAX_VALUE;
        double min = Integer.MIN_VALUE;

        double hashValue = (IP.hashCode() + max) * (32768/(max + Math.abs(min)));
        return (int) hashValue;

    }


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
                System.out.println("File: " + file.getName() + " : " + hash(file.getName()));
            }
        } else {
            System.out.println("The specified directory does not exist or is not a directory.");
        }
    }
    public static Map<String,Boolean> getFilesWithLockStatus(String path) {
        Map<String, Boolean> filesMap = new HashMap<>();
        File dir = new File(path);
        File[] filesList = dir.listFiles();

        if (filesList != null) {
            for (File file : filesList) {
                filesMap.put(file.getName(), false); // initially all files are unlocked
            }
        } else {
            System.out.println("The specified directory does not exist or is not a directory.");
        }
        return filesMap;
    }

    public static void displayLogContents(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            logger.log(Level.WARNING, "File does not exist");
            return;
        }
        try {
            // Read content from JSON file
            String content = new String(Files.readAllBytes(path));
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
            logger.log(Level.WARNING, "Error reading file", e);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error parsing JSON", e);

        }
    }

    public static void clearFolder(String path){
        File folder = new File(path);
        if (!folder.exists()) {
            logger.log(Level.WARNING, "The folder " + folder.getPath() + " does not exist.");
            return;
        }

        if (!folder.isDirectory()) {
            logger.log(Level.WARNING, "The provided path " + folder.getPath() + " is not a directory.");
            return;
        }

        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
                logger.log(Level.INFO, file.getName() + "Deleted");
            }
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


    // Serialize object to byte array
    public static byte[] serializeObject(Object obj) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(obj);
        objectOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    // Deserialize byte array back to object
    public static Object deserializeObject(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        Object obj = objectInputStream.readObject();
        objectInputStream.close();
        return obj;
    }

    public static void sendFileMap(String purpose, String targetIP, byte[] data, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            logger.log(Level.INFO, "Connected to file map data send socket: " + purpose);

            InetAddress targetIp = InetAddress.getByName(targetIP);

            DatagramPacket packet = new DatagramPacket(data, data.length, targetIp, port);

            socket.send(packet);

            logger.log(Level.INFO, "File map data: " + purpose + ", sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to open file map data send socket: " + purpose, e);
        }
    }

}
