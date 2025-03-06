package org.example;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientApp {
    private static final String SERVER_ADDRESS = "172.26.0.1";
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

                if (command.equalsIgnoreCase("CLOSE")) {
                    writer.println(command);
                    System.out.println("Завершение соединения...");
                    break;
                } else if (command.startsWith("UPLOAD ")) {
                    handleUpload(command, socket, writer, reader);
                } else if (command.startsWith("DOWNLOAD ")) {
                    writer.println(command);
                    String fileName = command.substring(9);
                    receiveFile(socket, fileName, reader);
                } else if (command.startsWith("ECHO ")) {
                    writer.println(command);
                    System.out.println("Ответ сервера: " + reader.readLine());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleUpload(String command, Socket socket, PrintWriter writer, BufferedReader reader) {
        String[] parsed = extractPathAndFileName(command);
        if (parsed == null) {
            System.out.println("Ошибка: формат команды - UPLOAD 'путь_к_файлу' новое_имя");
            return;
        }

        String sourceFilePath = parsed[0];
        String newFileName = parsed[1];
        File file = new File(sourceFilePath);

        if (!file.exists() || !file.isFile()) {
            System.out.println("Ошибка: файл не найден!");
            return;
        }

        writer.println("UPLOAD " + newFileName);

        try {
            String response = reader.readLine();
            if (!"READY".equals(response)) {
                System.out.println("Сервер отказал в приеме файла: " + response);
                return;
            }

            long fileSize = file.length();
            writer.println(fileSize);
            writer.flush();  // Убедимся, что размер файла отправлен немедленно

            sendFile(socket, sourceFilePath);

            socket.shutdownOutput(); // Сообщаем серверу, что передача закончена

            System.out.println("Файл успешно отправлен.");
        } catch (IOException e) {
            System.out.println("Ошибка при отправке файла: " + e.getMessage());
        }
    }

    // UPLOAD '/home/bobr/IdeaProjects/university/sem6/SPOIRS/lab1_server/server_files/tmp2.jpg' qwe.jpg
    private static String[] extractPathAndFileName(String command) {
        Pattern pattern = Pattern.compile("UPLOAD\\s+'(.*?)'\\s+(\\S+)");
        Matcher matcher = pattern.matcher(command);

        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    private static void sendFile(Socket socket, String filePath) throws IOException {
        try (OutputStream outputStream = socket.getOutputStream();
             FileInputStream fileInputStream = new FileInputStream(filePath)) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
    }

    private static void receiveFile(Socket socket, String fileName, BufferedReader reader) {
        try {
            String response = reader.readLine();
            if (!"READY".equals(response)) {
                System.out.println("Ошибка: сервер отказал в передаче файла - " + response);
                return;
            }

            long fileSize = Long.parseLong(reader.readLine());
            File saveFile = new File(fileName);

            try (FileOutputStream fileOutputStream = new FileOutputStream(saveFile);
                 InputStream inputStream = socket.getInputStream()) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = 0;

                while (totalRead < fileSize) {
                    // Читаем только столько байт, сколько осталось для получения
                    int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
                    bytesRead = inputStream.read(buffer, 0, bytesToRead);
                    if (bytesRead == -1) {
                        break;  // Если сервер закрыл соединение, выходим
                    }
                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }

                if (totalRead == fileSize) {
                    String confirmation = reader.readLine(); // Читаем подтверждение
                    if ("DONE".equals(confirmation)) {
                        System.out.println("Файл успешно загружен: " + fileName);
                    } else {
                        System.out.println("Ошибка: сервер не отправил подтверждение.");
                    }
                } else {
                    System.out.println("Ошибка: не все данные были получены.");
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.out.println("Ошибка при получении файла: " + e.getMessage());
        }
    }


}
