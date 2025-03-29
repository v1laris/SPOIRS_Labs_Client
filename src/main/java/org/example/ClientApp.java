package org.example;

import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.tongfei.progressbar.ProgressBar;

public class ClientApp {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 12345;
    private static Socket socket;
    private static PrintWriter writer;
    private static BufferedReader reader;
    private static Scanner scanner;
    private static boolean connected = false;
    private static long lastUploadBytesSent = 0;
    private static long lastDownloadBytesReceived = 0;
    private static String lastUploadCommand = null;
    private static String lastDownloadCommand = null;
    public static void main(String[] args) {
        if (!connect()) {
            System.out.println("Не удалось подключиться к серверу при запуске.");
            System.out.println("Попробуйте команду CONNECT, чтобы переподключиться вручную.");
        }
        scanner = new Scanner(System.in);
        System.out.println("Введите команду (ECHO, TIME, CLOSE, UPLOAD, DOWNLOAD):");
        while (true) {
            System.out.print("> ");
            String command = scanner.nextLine();
            if (!connected && !command.equalsIgnoreCase("CONNECT")) {
                System.out.println("Нет соединения. Используйте команду CONNECT, чтобы переподключиться.");
                continue;
            }

            if (command.equalsIgnoreCase("CLOSE")) {
                closeConnection();
                break;
            }
            else if (command.equalsIgnoreCase("CONNECT")) {
                if (connect()) {
                    System.out.println("Соединение восстановлено вручную!");
                } else {
                    System.out.println("Не удалось подключиться.");
                }
            }
            else if (command.startsWith("UPLOAD ")) {
                try {
                    lastUploadCommand = command;
                    lastUploadBytesSent = 0;

                    handleUpload(command);
                } catch (IOException e) {
                    System.out.println("Ошибка при загрузке: " + e.getMessage());
                    // Пытаемся восстановить соединение в течение 1 минуты
                    if (attemptReconnection(60_000)) {
                        System.out.println("Соединение восстановлено автоматически! Продолжаем загрузку...");
                        // Нужно доработать логику продолжения upload (см. ниже)
                        resumeUpload();
                    } else {
                        System.out.println("Не удалось восстановить соединение за 1 минуту.");
                    }
                }
            }
            else if (command.startsWith("DOWNLOAD ")) {
                try {
                    lastDownloadCommand = command;
                    lastDownloadBytesReceived = 0;
                    handleDownload(command);
                } catch (IOException e) {
                    System.out.println("Ошибка при скачивании: " + e.getMessage());
                    if (attemptReconnection(60_000)) {
                        System.out.println("Соединение восстановлено автоматически! Продолжаем скачивание...");
                        resumeDownload();
                    } else {
                        System.out.println("Не удалось восстановить соединение за 1 минуту.");
                    }
                }
            }
            else if (command.startsWith("ECHO ")) {
                writer.println(command);
                try {
                    System.out.println("Ответ сервера: " + reader.readLine());
                } catch (IOException e) {
                    handleConnectionLost(e);
                }
            }
            else if (command.equalsIgnoreCase("TIME")) {
                writer.println(command);
                try {
                    System.out.println("Ответ сервера: " + reader.readLine());
                } catch (IOException e) {
                    handleConnectionLost(e);
                }
            }
            else {
                System.out.println("Неизвестная команда. Доступны: CONNECT, CLOSE, UPLOAD, DOWNLOAD, ECHO, TIME");
            }
        }
    }

    private static boolean connect() {
        try {
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            socket.setSoTimeout(30_000); // например, 30 секунд таймаут
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connected = true;
            System.out.println("Успешное подключение к серверу: " + SERVER_ADDRESS + ":" + SERVER_PORT);
            return true;
        } catch (IOException e) {
            connected = false;
            return false;
        }
    }

    private static boolean attemptReconnection(long maxWaitMillis) {
        System.out.println("Пытаемся переподключиться в течение " + (maxWaitMillis/1000) + " сек...");
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < maxWaitMillis) {
            if (connect()) {
                return true;
            }
            // Ждём 3 сек перед следующей попыткой
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void closeConnection() {
        try {
            if (connected) {
                writer.println("CLOSE");
            }
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        connected = false;
        System.out.println("Соединение закрыто.");
    }

    private static void handleConnectionLost(IOException e) {
        System.out.println("Соединение потеряно: " + e.getMessage());
        connected = false;
    }

    private static void handleUpload(
            String command
    ) throws IOException {
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

        String response = reader.readLine();
        if (response == null) {
            throw new IOException("Сервер закрыл соединение");
        }
        if (!"READY".equals(response)) {
            System.out.println("Сервер отказал в приеме файла: " + response);
            return;
        }

        long fileSize = file.length();
        String fileHash;
        try {
            fileHash = computeFileHash(file);
        } catch (Exception e) {
            System.out.println("Ошибка вычисления хэша: " + e.getMessage());
            return;
        }
        System.out.println("Хеш-сумма файла: " + fileHash);

        writer.println(fileSize);
        writer.println(fileHash);
        writer.flush();

        System.out.println("Ждём ответа сервера");
        String serverResponse = reader.readLine();
        System.out.println(serverResponse);

        if ("FILE_EXISTS".equals(serverResponse)) {
            System.out.println("Файл с таким содержимым уже существует.");
            System.out.print("Загружать его снова? (y/n): ");
            String answer = scanner.nextLine();
            writer.println(answer.equalsIgnoreCase("y") ? "REUPLOAD" : "SKIP");

            if (answer.equalsIgnoreCase("y")) {
                String newNameFromServer = reader.readLine();
                System.out.println("Новый файл будет сохранён как: " + newNameFromServer);
            } else {
                System.out.println("Отправка файла отменена.");
                return;
            }
        } else if ("FILE_CONFLICT".equals(serverResponse)) {
//            System.out.print("Введите новое имя (или оставьте пустым для копии): ");
//            String newName = scanner.nextLine();
//            writer.println(newName);
        }

        long totalBytesSent = Long.parseLong(reader.readLine());
        System.out.printf("Начинаем загрузку с: %d байт\n", totalBytesSent);

        lastUploadBytesSent = totalBytesSent;

        try (FileInputStream fileInputStream = new FileInputStream(file);
            ProgressBar progressBar = new ProgressBar("Загрузка", fileSize)) {

            progressBar.stepTo(totalBytesSent);

            fileInputStream.skip(totalBytesSent);
            byte[] buffer = new byte[4096];
            int bytesRead;
            long startTime = System.currentTimeMillis();
            long bytesSent = totalBytesSent;
            OutputStream os = socket.getOutputStream();

            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                bytesSent += bytesRead;
                progressBar.stepBy(bytesRead);
                lastUploadBytesSent = bytesSent;
                //Thread.sleep(1);
            }
            System.out.println();

            long elapsedTime = System.currentTimeMillis() - startTime;
            double bitRate = (double) (bytesSent - totalBytesSent) * 8 / elapsedTime / 1000.0;
            System.out.printf("Битрейт: %.2f Mbps\n", bitRate);
        }

//        String confirmation = reader.readLine();
//        if ("DONE".equals(confirmation)) {
//            System.out.println("Файл успешно загружен: " + file.getName());
//        } else {
//            System.out.println("Ошибка: сервер не отправил подтверждение.");
//        }
    }

    private static void resumeUpload() {
        if (lastUploadCommand == null) {
            System.out.println("Нет информации о последней загрузке (UPLOAD).");
            return;
        }
        try {
            System.out.printf("Возобновляем загрузку, уже отправлено: %d байт\n", lastUploadBytesSent);
            handleUpload(lastUploadCommand);
        } catch (IOException e) {
            System.out.println("Снова ошибка при возобновлении загрузки: " + e.getMessage());
        }
    }

    private static String computeFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String[] extractPathAndFileName(String command) {
        Pattern pattern = Pattern.compile("UPLOAD\\s+'(.*?)'\\s+(\\S+)");
        Matcher matcher = pattern.matcher(command);

        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    private static void handleDownload(String command) throws IOException {
        if (!connected) throw new IOException("Нет соединения для DOWNLOAD.");

        String fileName = command.substring(9).trim();
        writer.println(command);
        writer.flush(); // Убедимся, что команда отправлена

        String response = reader.readLine();
        if (!"READY".equals(response)) {
            System.out.println("Ошибка: сервер отказал в передаче файла - " + response);
            return;
        }
        //System.out.println("Сервер - READY");
        long serverFileSize = Long.parseLong(reader.readLine());
        File saveFile = new File(fileName);
        long existingFileSize = 0;

        if (saveFile.exists()) {
            existingFileSize = saveFile.length();
            if (existingFileSize < serverFileSize) {
                System.out.println("Файл найден, продолжаем с " + existingFileSize + " байт.");
                writer.println("RESUME " + existingFileSize);
            } else if (existingFileSize == serverFileSize) {
                System.out.print("Файл уже полностью скачан. Перекачать заново? (y/n): ");
                String answer = new Scanner(System.in).nextLine();
                if ("y".equalsIgnoreCase(answer)) {
                    writer.println("START");
                    saveFile.delete();
                    existingFileSize = 0;
                } else {
                    writer.println("ABORT");
                    System.out.println("Скачивание отменено.");
                    return;
                }
            } else { // если локальный файл больше
                System.out.println("Локальный файл больше серверного. Перекачиваем заново.");
                writer.println("START");
                saveFile.delete();
                existingFileSize = 0;
            }
        } else {
            writer.println("START");
        }
        writer.flush();

        try (FileOutputStream fos = new FileOutputStream(saveFile, true);
             ProgressBar progressBar = new ProgressBar("Загрузка", serverFileSize)) {
            progressBar.stepTo(existingFileSize);
            InputStream inputStream = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalRead = existingFileSize;
            long startTime = System.currentTimeMillis();
            //System.out.println("Server file size: " + serverFileSize);

            while (totalRead < serverFileSize) {
                //System.out.println("Total read: " + totalRead);
                int bytesToRead = (int) Math.min(buffer.length, serverFileSize - totalRead);
                bytesRead = inputStream.read(buffer, 0, bytesToRead);
                //System.out.println("Total read: " + totalRead);
                if (bytesRead <= 0) break;
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                progressBar.stepBy(bytesRead);
            }
            System.out.println();
            long elapsedTime = System.currentTimeMillis() - startTime;
            long bitRate = (totalRead * 8) / (elapsedTime + 1);
            System.out.printf("Битрейт: %.2f Kbps\n", bitRate / 1000.0);
            if (totalRead == serverFileSize) {
                System.out.println("Файл успешно загружен: " + fileName);
            } else {
                System.out.println("Ошибка: не все данные были получены.");
            }
        }
    }


    private static void resumeDownload() {
        if (lastDownloadCommand == null) {
            System.out.println("Нет информации о последней операции DOWNLOAD.");
            return;
        }
        try {
            System.out.printf("Возобновляем скачивание, уже получено: %d байт\n", lastDownloadBytesReceived);
            handleDownload(lastDownloadCommand);
        } catch (IOException e) {
            System.out.println("Снова ошибка при возобновлении скачивания: " + e.getMessage());
        }
    }
}
