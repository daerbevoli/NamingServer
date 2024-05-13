package be.uantwerpen.fti.ei.namingserver;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class FileTransfer {

    public static void transferFile(String path, String IP, int port)
    {
        try {
            System.out.println("received IP:" + IP);
            Socket socket = new Socket(IP, port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            File file = new File(path);
            String name = file.getName();

            //send over file name first
            out.writeUTF(name);
            out.flush();

            //send the file
            FileInputStream fis = new FileInputStream(file);
            out.writeLong(file.length());
            byte[] buffer = new byte[(int) file.length() + 10];
            int bytes = 0;
            while (bytes != -1)
            {
                bytes = fis.read(buffer);
                if(bytes != -1){
                    out.write(buffer,0,bytes);
                    out.flush();}
            }
            System.out.println("FileSend");
            fis.close();
            out.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void receiveFile(int port, String directory)
    {
        try {
            ServerSocket sSocket = new ServerSocket(port);
            Socket cSocket = sSocket.accept();
            ObjectInputStream in = new ObjectInputStream(cSocket.getInputStream());

            //read file name
            String fileName = in.readUTF();
            File file = new File(directory, fileName);


            FileOutputStream fos = new FileOutputStream(file);
            long length = in.readLong();
            byte[] buf = new byte[(int) length + 10];
            int bytes = 0;
            while (length > 0 && bytes != -1) {
                bytes = in.read(buf, 0, (int) length);
                fos.write(buf, 0, bytes);
                length = length - bytes;
            }
            System.out.println("File received");
            fos.close();
            in.close();
            cSocket.close();;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
