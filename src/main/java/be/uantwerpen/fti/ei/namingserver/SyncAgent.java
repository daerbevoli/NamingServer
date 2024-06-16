package be.uantwerpen.fti.ei.namingserver;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;



/**
 * Sync agent to sync the replicated files to the correct owner. It uses the fileList data structure
 * hold the filenames and locked status.
 */
public class SyncAgent implements Runnable, Serializable {
    /**
     * The 'Synchronize' keyword is used to create a method that can be accessed by only one thread at a time.
     * In the context of the class, the methods are synchronized to prevent multiple threads executing them
     * concurrently, f.e. when a thread tries locking and another thread tries unlocking at the same time which
     * could cause race conditions.
     */

    private static final Logger logger = Logger.getLogger(SyncAgent.class.getName());

    private final Map<String, Boolean> filesMap; // Map to store filename and lock status
    private final Map<String, Boolean> nodeFileMap;
    private final Node node;
    private volatile boolean running = true;


    public SyncAgent(Node node) {
        this.nodeFileMap = getNodeOwnedFiles();
        filesMap = Collections.synchronizedMap(new HashMap<>());
        this.node = node;
    }

    public synchronized void addFile(String filename) {
        filesMap.put(filename, false);
    }

    public synchronized void removeFile(String filename) {
        filesMap.remove(filename);
    }

    public synchronized void lockFile(String filename) {
        filesMap.put(filename, true);
    }

    public synchronized void unlockFile(String filename) {
        filesMap.put(filename, false);
    }

    public synchronized boolean isLocked(String filename) {
        return filesMap.getOrDefault(filename, false);
    }

    public synchronized Map<String, Boolean> getFilesMap(){
        return filesMap;
    }

    public synchronized void listFiles(Map<String, Boolean> fileMap){
        for(String filename : fileMap.keySet()){
            System.out.println("File: " + filename);
        }
    }

    public synchronized Map<String, Boolean> getNodeOwnedFiles() {
        return helpMethods.getFilesWithLockStatus("/root/replicatedFiles");
    }

    public void stop() {
        running = false;
    }

    public void synchronizeWithNextNode(Map<String, Boolean> nextNodeFileMap) {
        if (nextNodeFileMap == null) {
            return;
        }
        synchronized (nextNodeFileMap) {
            for (Map.Entry<String, Boolean> entry : nextNodeFileMap.entrySet()) {
                String filename = entry.getKey();
                boolean isLocked = entry.getValue();

                if (!filesMap.containsKey(filename)) {
                    filesMap.put(filename, isLocked);
                } else if (isLocked) {
                    filesMap.put(filename, true); // If the file is locked in the next node, lock it here as well
                }
            }
            logger.log(Level.INFO, "Synchronized with next node");
        }
    }

    private Map<String, Boolean> getNodeFileMap(){
        return nodeFileMap;
    }

    /*
    Method to communicate with the next node and retrieve it's fileMap
     */
    private void getNextNodeFileMap() {

        String nextNodeIP = node.getNextNodeIP();
        if (nextNodeIP != null) {
            String purpose = "Requesting File Map";
            logger.log(Level.INFO, "Requesting file map from next node with IP: " + nextNodeIP);
            helpMethods.sendUnicast(purpose, nextNodeIP, "REQUEST_FILE_MAP:" + node.getIP(), Ports.reqPort);
        } else {
            logger.log(Level.WARNING, "Next node IP is null, cannot request file map");
        }
    }

    // Method to process the received file map response
    public void processFileMapResponse(Map<String, Boolean> fileMap) {
        synchronizeWithNextNode(fileMap);
    }


    // Method to notify the next node to synchronize
    public void notifyNextNode() {
        String nextNodeIP = node.getNextNodeIP();
        if (nextNodeIP != null) {
            helpMethods.sendUnicast("Notify next node to synchronize", nextNodeIP, "SYNC_REQUEST", Ports.syncPort);
        }
    }

    @Override
    public String toString() {
        return "SyncAgent{" +
                "filesMap=" + filesMap +
                '}';
    }

    @Override
    public void run() {
            // list all the files that the node owns if it's empty print that the node has no files
            if (nodeFileMap.isEmpty()) {
                System.out.println("Node owns no files");
            } else {
                System.out.println("Node files:");
                listFiles(nodeFileMap);
            }
            // update list (filesMap) with local files from the node (nodeFileMap)
            filesMap.putAll(getNodeOwnedFiles());

            // Retrieve the next node's file map
            // the synchronizeWithNextNode method is called in the Node class when the file map is received
            getNextNodeFileMap();

            // Update the node's file list based on the agent's list
            nodeFileMap.putAll(filesMap);



        /*// Example of handling a lock request (this should be integrated with actual lock handling logic)
        String fileToLock = "example.txt"; // Example file name, replace with actual logic
        if (Node.hasLockRequest(fileToLock) && !isLocked(fileToLock)) {
            lockFile(fileToLock);
            Node.lockFile(fileToLock);
        }

        // Example of removing a lock (this should be integrated with actual lock handling logic)
        if (!Node.hasLockRequest(fileToLock) && isLocked(fileToLock)) {
            unlockFile(fileToLock);
            Node.unlockFile(fileToLock);
        }*/

            // Notify the next node to synchronize
            //notifyNextNode();

            // Sleep before next synchronization
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Level.WARNING, "Sync agent interrupted", e);
            }
        }

}
