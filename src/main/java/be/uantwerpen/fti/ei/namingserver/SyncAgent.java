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
    private final Map<String, Boolean> nodeLocalFiles;
    private final Node node;
    private volatile boolean running = false;
    private String nextNodeIP;

    public SyncAgent(Node node) {
        this.nodeLocalFiles = getNodeLocalFiles();
        this.nodeFileMap = getNodeOwnedFiles();
        filesMap = Collections.synchronizedMap(new HashMap<>());
        this.node = node;
        updateNextNodeIP();
    }

    public synchronized void updateNextNodeIP() {
        String nextnodeIP = node.getNextNodeIP();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "Error while sleeping", e);
        }
        this.nextNodeIP = nextnodeIP;
        if (nextNodeIP != null) {
            logger.log(Level.INFO, "Next node IP updated to: " + nextNodeIP);
        } else {
            logger.log(Level.WARNING, "Next node IP is null, cannot update next node IP");
        }
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

    public synchronized Map<String, Boolean> getNodeLocalFiles(){
        return helpMethods.getFilesWithLockStatus("/root/localFiles");
    }

    public synchronized void listFiles(Map<String, Boolean> fileMap){
        for(String filename : fileMap.keySet()){
            System.out.println("File: " + filename);
        }
    }

    public synchronized Map<String, Boolean> getNodeOwnedFiles() {
        return helpMethods.getFilesWithLockStatus("/root/replicatedFiles");
    }


    public synchronized void stop() {
        running = false;
    }

    public synchronized void start() {
        //updateNextNodeIP();
        running = true;
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
        if (nextNodeIP != null) {
            String purpose = "Requesting File Map";
            logger.log(Level.INFO, "Node: " + node.getIP() + "Requesting file map from next node with IP: " + nextNodeIP);
            helpMethods.sendUnicast(purpose, nextNodeIP, "REQUEST_FILE_MAP" + ":" + node.getIP(), Ports.reqPort);
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
        if (nextNodeIP != null) {
            helpMethods.sendUnicast("Notify next node to synchronize", node.getnextID(), "SYNC_REQUEST", Ports.syncPort);
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
            if (node.getNumOfNodes() >= 1) {
                if (nextNodeIP == null) {
                    logger.log(Level.WARNING, "Next node IP is null, cannot synchronize");
                    stop();
                }
                //logger.log(Level.INFO, "Node files:");
                //listFiles(nodeFileMap);
                // update list (filesMap) with local files from the node (nodeFileMap) that it has replicated
                filesMap.putAll(getNodeOwnedFiles());
                // also add the local files that have not yet been replicated.
                for (String filename : nodeLocalFiles.keySet()) {
                    if (!filesMap.containsKey(filename)) {
                        filesMap.put(filename, false);
                    }
                }
                // Retrieve the next node's file map
                // the synchronizeWithNextNode method is called in the Node class when the file map is received
                getNextNodeFileMap();
                // Update the node's file list based on the agent's list
                nodeFileMap.putAll(filesMap);
                // notify the next node to synchronize
                notifyNextNode();
                logger.log(Level.INFO, "Next Sync agent notified to synchronize");
                stop();

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

            } else {
                logger.log(Level.WARNING, "Only one node in the network, no need to sync");
                // also add the local files that have not yet been replicated.
                for (String filename : nodeLocalFiles.keySet()) {
                    if (!filesMap.containsKey(filename)) {
                        filesMap.put(filename, false);
                    }
                }
                // Sleep until the numofnodes is more than 1
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Error while sleeping", e);
                }
            }
        }

    public boolean isRunning() {
        return running;
    }
}

