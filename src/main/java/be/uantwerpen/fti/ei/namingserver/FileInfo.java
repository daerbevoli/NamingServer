package be.uantwerpen.fti.ei.namingserver;

import java.util.concurrent.ConcurrentHashMap;

public class FileInfo {
    String localOwnerIP;
    String replicatedOwnerIP;

    public FileInfo(String localOwnerIP, String replicatedOwnerIP) {
        this.localOwnerIP = localOwnerIP;
        this.replicatedOwnerIP = replicatedOwnerIP;
    }

    private ConcurrentHashMap<String, FileInfo> fileOwnershipMap = new ConcurrentHashMap<>();
}
