package be.uantwerpen.fti.ei.namingserver;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileTransfer {

    private static final Logger logger = Logger.getLogger(FileTransfer.class.getName());

    public static void transferFile(String path, String IP, int port, String potentialMessage)
    {
        try {
            if (potentialMessage==null) {potentialMessage="";}
            System.out.println("received IP:" + IP+"/nWith Message: "+potentialMessage);
            InetAddress address = InetAddress.getByName(IP);
            Socket socket = new Socket(address, port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            File file = new File(path);
            String name = file.getName();

            // send over file name first
            out.writeUTF(name);
            out.flush();

            // send the file length
            out.writeLong(file.length());
            out.flush();

            // send the file data
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length() + 10];
            int bytes = 0;
            while ((bytes = fis.read(buffer)) != -1)
            {
                if(bytes != -1){
                    out.write(buffer,0,bytes);
                    out.flush();}
            }
            logger.log(Level.INFO, "File sent successfully: " + name);
            fis.close();
            out.close();

            out.writeUTF(potentialMessage+":"+IP);
            out.flush();
            socket.close();



        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to send file", e);
        }
    }


    public static void transferFile2(String IP, String filename, int port) {
        File fileToSend = new File("/root/localFiles/" + filename);

        if (!fileToSend.exists()) {
            System.out.println("File not found: " + filename);
            return;
        }

        try (Socket clientSocket = new Socket(IP, port);
             ObjectOutputStream outputStream = new ObjectOutputStream(clientSocket.getOutputStream());
             FileInputStream fileInputStream = new FileInputStream(fileToSend)) {

            System.out.println("Sending file: " + filename);

            // Send the file name
            outputStream.writeUTF(filename);
            outputStream.flush();

            // Send the file length
            outputStream.writeLong(fileToSend.length());
            outputStream.flush();

            // Buffer to store chunks of file data
            byte[] buffer = new byte[1024];
            int bytesRead;

            // Read the file data and send it to the server
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            // Ensure all data is sent immediately
            outputStream.flush();

            //Sending extra UTF because receiveFile method was changed and this is now required
            outputStream.writeUTF("");
            outputStream.flush();

            System.out.println("File sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to send file", e);
        }
    }

    public static void receiveFile(int port, String directory) {
        String message = null;
        try {
            ServerSocket sSocket = new ServerSocket(port);
            Socket cSocket = sSocket.accept();
            ObjectInputStream in = new ObjectInputStream(cSocket.getInputStream());

            // Create directory if it does not exist
            File dir = new File(directory);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            while(true){
                // read file name
                String fileName = in.readUTF();
                File file = new File(directory, fileName);

                // read file length
                long length = in.readLong();

                // read file data
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buf = new byte[(int) length + 10];
                    int bytes = 0;
                    while (length > 0 && (bytes = in.read(buf, 0, (int) Math.min(buf.length, length))) != -1) {
                        fos.write(buf, 0, bytes);
                        length -= bytes;
                    }
                    logger.log(Level.INFO, "File received successfully: " + fileName);
                    fos.close();
                    in.close();
                    cSocket.close();
                }
                message = in.readUTF();
                String[] parts = message.split(":");
                if(parts.length==3)
                    {
                        updateLogFile(parts[0],parts[1],parts[2]);
                    }
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "ERROR receiving file", e);
        }

    }

    private static void updateLogFile(String localOwnerIP, String filename, String IP) {
        File fileLog = new File("/root/logs/fileLog.json");
        try {
            // Ensure the directory exists
            File directory = fileLog.getParentFile();
            if (directory != null && !directory.exists()) {
                directory.mkdirs();
            }

            JSONObject root;
            if (fileLog.exists()) {
                String content = new String(Files.readAllBytes(fileLog.toPath()));
                root = new JSONObject(content);
            } else {
                root = new JSONObject();
            }
            JSONObject fileInfo = new JSONObject();
            fileInfo.put("localOwnerIP", localOwnerIP);
            fileInfo.put("replicatedOwnerIP", IP);
            root.put(filename, fileInfo);

            try (FileWriter writer = new FileWriter(fileLog)) {
                writer.write(root.toString());
            }
            logger.log(Level.INFO, "File log updated successfully");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error updating file log", e);

        } catch (JSONException e) {
            logger.log(Level.WARNING, "Error creating JSON object", e);
        }

    }

}
