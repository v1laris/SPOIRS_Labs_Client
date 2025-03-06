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
//                    handleUpload(command, socket, writer, reader);
                    handleUploadWithResume(command, socket, writer, reader);
                } else if (command.startsWith("DOWNLOAD ")) {
                    writer.println(command);
                    String fileName = command.substring(9);
                    handlerDownload(socket, fileName, reader);
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

            sendFile(socket.getOutputStream(), file);
            String confirmation = reader.readLine(); // Читаем подтверждение
            if ("DONE".equals(confirmation)) {
                System.out.println("Файл успешно загружен: " + file.getName());
            } else {
                System.out.println("Ошибка: сервер не отправил подтверждение.");
            }
        } catch (IOException e) {
            System.out.println("Ошибка при отправке файла: " + e.getMessage());
        }
    }

    private static void handleUploadWithResume(String command, Socket socket, PrintWriter writer, BufferedReader reader) {
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
            // Получаем ответ от сервера
            String response = reader.readLine();
            if (!"READY".equals(response)) {
                System.out.println("Сервер отказал в приеме файла: " + response);
                return;
            }

            long fileSize = file.length();
            writer.println(fileSize);
            writer.flush();  // Убедимся, что размер файла отправлен немедленно

            // Чтение состояния докачки
            long totalBytesSent = getTotalBytesSent(newFileName); // Получаем прогресс предыдущей передачи

            // Отправка файла с сохранением прогресса
            long startTime = System.currentTimeMillis(); // Время начала передачи
            long bytesSent = totalBytesSent; // Начинаем с того места, где остановились

            try (FileInputStream fileInputStream = new FileInputStream(file)) {
                // Пропустить уже отправленные байты
                fileInputStream.skip(totalBytesSent);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    socket.getOutputStream().write(buffer, 0, bytesRead);
                    bytesSent += bytesRead;

                    // Каждые 500 миллисекунд обновляем битрейт
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    if (elapsedTime > 500) { // Обновляем каждые 500 мс
                        long bitRate = (bytesSent * 8) / elapsedTime; // Битрейт в битах в секунду
                        System.out.printf("Битрейт: %.2f Kbps\n", bitRate / 1000.0); // Выводим битрейт в Kbps
                        startTime = System.currentTimeMillis(); // Сбрасываем время для следующего расчета
                    }

                    // Сохраняем прогресс
                    saveProgress(newFileName, bytesSent); // Сохраняем прогресс в файл
                }
            }

            // Подтверждение завершения передачи
            String confirmation = reader.readLine(); // Читаем подтверждение
            if ("DONE".equals(confirmation)) {
                System.out.println("Файл успешно загружен: " + file.getName());
            } else {
                System.out.println("Ошибка: сервер не отправил подтверждение.");
            }
        } catch (IOException e) {
            System.out.println("Ошибка при отправке файла: " + e.getMessage());
        }
    }

    // Метод для получения прогресса передачи
    private static long getTotalBytesSent(String fileName) {
        File progressFile = new File(fileName + ".progress");
        if (progressFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(progressFile))) {
                String progress = reader.readLine();
                return Long.parseLong(progress);
            } catch (IOException | NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    // Метод для сохранения прогресса передачи
    private static void saveProgress(String fileName, long bytesSent) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName + ".progress"))) {
            writer.write(Long.toString(bytesSent));
        } catch (IOException e) {
            System.out.println("Ошибка при сохранении прогресса: " + e.getMessage());
        }
    }


    private static void sendFile(OutputStream outputStream, File file) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesSent = 0;
            long startTime = System.currentTimeMillis(); // Время начала передачи данных

            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;

            }
            long elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime > 0) {
                long bitRate = (totalBytesSent * 8) / elapsedTime;
                System.out.printf("Битрейт: %.2f Kbps\n", bitRate / 1000.0); // выводим битрейт в Kbps
            }
            outputStream.flush();
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

    private static void handlerDownload(Socket socket, String fileName, BufferedReader reader) {
        InputStream inputStream = null;
        try {
            String response = reader.readLine();
            if (!"READY".equals(response)) {
                System.out.println("Ошибка: сервер отказал в передаче файла - " + response);
                return;
            }

            long fileSize = Long.parseLong(reader.readLine());
            File saveFile = new File(fileName);

            inputStream = socket.getInputStream();

            try (FileOutputStream fileOutputStream = new FileOutputStream(saveFile)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = 0;

                long startTime = System.currentTimeMillis(); // Время начала получения данных

                while (totalRead < fileSize) {
                    // Читаем только столько байт, сколько осталось для получения
                    int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
                    bytesRead = inputStream.read(buffer, 0, bytesToRead);
                    if (bytesRead == -1) {
                        break;
                    }
                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
                long elapsedTime = System.currentTimeMillis() - startTime;
                long bitRate = (totalRead * 8) / elapsedTime; // Битрейт в битах в секунду
                System.out.printf("Битрейт: %.2f Kbps\n", bitRate / 1000.0); // Выводим битрейт в Kbps

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
        } catch (IOException |
                 NumberFormatException e) {
            System.out.println("Ошибка при получении файла: " + e.getMessage());
        }
    }

}
