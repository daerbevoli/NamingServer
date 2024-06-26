package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileTransfer {

    private static final Logger logger = Logger.getLogger(FileTransfer.class.getName());

    private final int port;

    private ServerSocket sSocket;

    private  boolean listening = true;

    public FileTransfer(int port) throws IOException {
           this.port = port;
    }

    public void transferFile(String IP, String filename, String potentialMessage) {
        File fileToSend;
        if(potentialMessage == null) {
            potentialMessage = "";
            fileToSend = new File("/root/localFiles/" + filename);
        } else {
            potentialMessage = potentialMessage+ ":" + IP;
            fileToSend = new File("/root/replicatedFiles/" + filename);
        }

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

            // Send the file length
            outputStream.writeLong(fileToSend.length());

            // Buffer to store chunks of file data
            byte[] buffer = new byte[1024];
            int bytesRead;

            // Read the file data and send it to the server
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }



            outputStream.writeUTF(potentialMessage);
            // Ensure all data is sent immediately
            outputStream.flush();

            logger.log(Level.INFO, "File sent successfully");

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to send file", e);
        }

    }

    public void receiveFiles(String directory) {
        listening = true;

        try {
            sSocket = new ServerSocket(port);
            while (listening) {
                Socket cSocket = sSocket.accept();
                System.out.println("accepted socket");
                handleFileTransfer(cSocket, directory);

            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "ERROR receiving file", e);
        }
    }

    private void handleFileTransfer(Socket cSocket, String directory) {
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
                //System.out.println("Starting to read file");
                byte[] buf = new byte[8192];
                int bytes;
                while (length > 0 && (bytes = in.read(buf, 0, (int) Math.min(buf.length, length))) != -1) {
                    fos.write(buf, 0, bytes);
                    length -= bytes;
                }
                logger.log(Level.INFO, "File received successfully: " + fileName);
            }

            String msg = in.readUTF();
            if(!msg.isEmpty())
            {
                String[] parts = msg.split(":");
                Node.updateLogFile(parts[0], parts[1], fileName);
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "ERROR receiving file", e);
        } finally {
            try {
                cSocket.close();
               // System.out.println("closed csocket");
            } catch (IOException e) {
                logger.log(Level.WARNING, "ERROR closing socket", e);
            }
        }
    }



    public  void stopListening()
    {
        listening = false;

        if (sSocket != null && !sSocket.isClosed()) {
            try {
                sSocket.close();
                System.out.println("listening stopped.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }



}
