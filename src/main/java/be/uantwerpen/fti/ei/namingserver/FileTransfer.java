package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.spi.InetAddressResolver;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileTransfer {

    private static final Logger logger = Logger.getLogger(FileTransfer.class.getName());

    public static void transferFile(String path, String IP, int port)
    {
        try {
            System.out.println("received IP:" + IP);
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
            socket.close();

        } catch (IOException e) {
            logger.log(Level.WARNING, "Unable to send file", e);
        }
    }

    public static void receiveFile(int port, String directory)
    {
        try {
            ServerSocket sSocket = new ServerSocket(port);
            Socket cSocket = sSocket.accept();
            ObjectInputStream in = new ObjectInputStream(cSocket.getInputStream());

            // Create directory if it does not exist
            File dir = new File(directory);
            if (!dir.exists()) {
                dir.mkdirs();
            }

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
        } catch (IOException e) {
            logger.log(Level.WARNING, "ERROR receiving file", e);
        }
    }
}
