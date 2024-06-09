package be.uantwerpen.fti.ei.namingserver;

import java.io.File;
import java.io.Serializable;
import java.util.Map;


/**
 * Sync agent to sync the replicated files to the correct owner. It uses the fileList data structure
 * hold the filenames and locked status.
 */
public class SyncAgent implements Runnable, Serializable {

    private FileList fileList;

    public SyncAgent(){
        this.fileList = new FileList();
        File dir = new File("/root/replicatedFiles");

        File[] filesList = dir.listFiles();
        for (File file : filesList){
            fileList.addFile(file.getName());
        }

    }

    // Method to add a file to the list of files owned by the node
    public synchronized void addOwnedFile(String filename) {
        fileList.addFile(filename);
    }

    // Method to update the list based on the agent’s list
    private synchronized void updateFileList(FileList agentFileList) {

    }

    // Method to handle lock request
    public synchronized void handleLockRequest(String filename) {
        if (!fileList.isLocked(filename)) {
            fileList.lockFile(filename);

        }
    }

    // Method to handle unlocking
    public synchronized void handleUnlockRequest(String filename) {
        fileList.unlockFile(filename);
        // Perform necessary actions for unlocking
    }


    public Map<String, Boolean> getFileList(){
        return fileList.getFilesMap();
    }

    @Override
    public void run() {
        // Implement run method to list files owned by the node,
        // update file list, handle lock requests, etc.
    }



}
