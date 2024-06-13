package be.uantwerpen.fti.ei.namingserver;

import java.io.File;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


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

    // Map to store filename and lock status
    private final Map<String, Boolean> filesMap;

    private final Map<String, Boolean> nodeFileMap;

    public SyncAgent(Map<String, Boolean> nodeFileMap) {
        this.nodeFileMap = nodeFileMap;
        filesMap = Collections.synchronizedMap(new HashMap<>());
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

    private synchronized void listFiles(Map<String, Boolean> fileMap){
        for(String filename : fileMap.keySet()){
            System.out.println("File: " + filename);
        }
    }


    private void synchronizeWithNextNode(SyncAgent nextAgent) {
        Map<String, Boolean> nextAgentFiles = nextAgent.getFilesMap();

        synchronized (nextAgentFiles) {
            for (Map.Entry<String, Boolean> entry : nextAgentFiles.entrySet()) {
                filesMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, Boolean> getNodeFileMap(){
        return nodeFileMap;
    }

    @Override
    public String toString() {
        return "SyncAgent{" +
                "filesMap=" + filesMap +
                '}';
    }

    @Override
    public void run() {
        // list all the files that the node owns
        listFiles(nodeFileMap);

        // update list with local files
        for (Map.Entry<String, Boolean> file : getNodeFileMap().entrySet()){
            this.addFile(file.getKey());
        }

        // Assume there's a way to get the next node's SyncAgent (e.g., through the network or a shared service)
        //SyncAgent nextAgent = getNextNodeAgent(); // Pseudocode
        //synchronizeWithNextNode(nextAgent); // Uncomment and implement this in a real scenario

        // Update the node's list based on the agent's list
        getNodeFileMap().putAll(filesMap);

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
    }
}
