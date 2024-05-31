package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileTransfer {

    private static final Logger logger = Logger.getLogger(FileTransfer.class.getName());
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void transferFile(String IP, String filename, int port) {
        File fileToSend = new File("/root/localFiles/" + filename);

        if (!fileToSend.exists()) {
            logger.log(Level.WARNING, "File not found: " + filename);
            return;
        }

        try (Socket clientSocket = new Socket(IP, port);
             ObjectOutputStream outputStream = new ObjectOutputStream(clientSocket.getOutputStream());
             FileInputStream fileInputStream = new FileInputStream(fileToSend)) {

            logger.log(Level.INFO, "Sending file: " + filename);

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

            logger.log(Level.INFO, "File sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to send file", e);
        }
    }

    // NOT SURE IF THREAD NEEDED
    public static void receiveFiles(int port, String directory) {
        try (ServerSocket sSocket = new ServerSocket(port)){
            while (true) {
                Socket cSocket = sSocket.accept();
                executor.submit(() -> handleFileTransfer(cSocket, directory));
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "ERROR receiving file", e);
        }
    }

    private static void handleFileTransfer(Socket cSocket, String directory) {
        try (ObjectInputStream in = new ObjectInputStream(cSocket.getInputStream())) {
            // Create directory if it does not exist
            File dir = new File(directory);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // Read file name
            String fileName = in.readUTF();
            File file = new File(directory, fileName);

            // Read file length
            long length = in.readLong();

            // Read file data
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buf = new byte[4096];
                int bytes;
                while (length > 0 && (bytes = in.read(buf, 0, (int) Math.min(buf.length, length))) != -1) {
                    fos.write(buf, 0, bytes);
                    length -= bytes;
                }
                logger.log(Level.INFO, "File received successfully: " + fileName);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "ERROR receiving file", e);
        } finally {
            try {
                cSocket.close();
            } catch (IOException e) {
                logger.log(Level.WARNING, "ERROR closing socket", e);
            }
        }
    }
}
