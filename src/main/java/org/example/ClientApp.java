package org.example;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class ClientApp {
    private static final String SERVER_ADDRESS = "172.20.10.2";
    private static final int SERVER_PORT = 12345;

    public static void main(String[] args) {
        try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Подключено к серверу " + SERVER_ADDRESS + ":" + SERVER_PORT);
            System.out.println("Введите команду (ECHO, TIME, CLOSE, UPLOAD, DOWNLOAD):");

            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine();
                writer.println(command);

                if (command.equalsIgnoreCase("CLOSE")) {
                    System.out.println("Завершение соединения...");
                    break;
                } else if (command.startsWith("UPLOAD ")) {
                    String fileName = command.substring(7);
                    sendFile(socket, fileName);
                } else if (command.startsWith("DOWNLOAD ")) {
                    String fileName = command.substring(9);
                    receiveFile(socket, fileName);
                } else {
                    System.out.println("Ответ сервера: " + reader.readLine());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sendFile(Socket socket, String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("Файл не найден!");
            return;
        }

        try (FileInputStream fileInputStream = new FileInputStream(file);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out)) {

            // Отправляем имя файла и его размер
            dataOut.writeUTF(file.getName());
            dataOut.writeLong(file.length());

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = fileInputStream.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

            System.out.println("Файл отправлен на сервер!");
        }
    }

    private static void receiveFile(Socket socket, String fileName) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        DataInputStream dataIn = new DataInputStream(socket.getInputStream());

        String sizeResponse = reader.readLine();
        if (!sizeResponse.startsWith("FILE_SIZE")) {
            System.out.println("Ошибка: сервер не отправил размер файла!");
            return;
        }

        long fileSize = Long.parseLong(sizeResponse.split(" ")[1]);
        System.out.println("Размер файла: " + fileSize + " байт");

        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            byte[] buffer = new byte[4096];
            long bytesReceived = 0;

            while (bytesReceived < fileSize) {
                int bytesRead = dataIn.read(buffer);
                if (bytesRead <= 0 ) break; // Выход из цикла, если поток закончился

                fos.write(buffer, 0, bytesRead);
                bytesReceived += bytesRead;
            }

            System.out.println("Файл " + fileName + " скачан!");
        }
    }

}
