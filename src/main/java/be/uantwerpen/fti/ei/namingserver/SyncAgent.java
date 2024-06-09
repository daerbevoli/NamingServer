package be.uantwerpen.fti.ei.namingserver;

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

    public SyncAgent() {
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


    @Override
    public void run() {

    }
}
