package org.example;

import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.tongfei.progressbar.ProgressBar;

public class ClientApp {
    private static final String SERVER_ADDRESS = "127.0.0.1";
    private static final int SERVER_PORT = 12345;
//    private static Socket socket;
//    private static PrintWriter writer;
//    private static BufferedReader reader;
//    private static boolean connected = false;
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
                    handleUploadWithResume(command, socket, writer, reader, scanner);
                } else if (command.startsWith("DOWNLOAD ")) {
                    writer.println(command);
                    String fileName = command.substring(9);
                    handlerDownload(socket, fileName, reader, writer);
                } else if (command.startsWith("ECHO ")) {
                    writer.println(command);
                    System.out.println("Ответ сервера: " + reader.readLine());
                }  else if (command.startsWith("TIME")) {
                    writer.println(command);
                    System.out.println("Ответ сервера: " + reader.readLine());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleUploadWithResume(
            String command,
            Socket socket,
            PrintWriter writer,
            BufferedReader reader,
            Scanner scanner
    ) {
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
            String fileHash = computeFileHash(file);
            System.out.println("Хеш-сумма файла: " + fileHash);

            writer.println(fileSize);
            writer.println(fileHash);
            writer.flush();

            String serverResponse = reader.readLine();
            System.out.println(serverResponse);

            if ("FILE_EXISTS".equals(serverResponse)) {
                System.out.println("Файл с таким содержимым уже существует.");
                System.out.print("Загружать его снова? (y/n): ");
                String answer = scanner.nextLine();
                if (answer.equalsIgnoreCase("y")) {
                    writer.println("REUPLOAD");
                    String newNameFromServer = reader.readLine();
                    System.out.println("Новый файл будет сохранен как: " + newNameFromServer);
                } else {
                    writer.println("SKIP");
                    return;
                }
            } else if ("FILE_CONFLICT".equals(serverResponse)) {
                System.out.print("Введите новое имя (или оставьте пустым для копии): ");
                String newName = scanner.nextLine();
                writer.println(newName);
            }

            long totalBytesSent = Long.parseLong(reader.readLine());
            System.out.printf("Начинаем загрузку с: %d байт\n", totalBytesSent);

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
                    //Thread.sleep(1);
                }
                System.out.println();

                long elapsedTime = System.currentTimeMillis() - startTime;
                double bitRate = (double) (bytesSent - totalBytesSent) * 8 / elapsedTime / 1000.0;
                System.out.printf("Битрейт: %.2f Mbps\n", bitRate);
            }

            String confirmation = reader.readLine();
            if ("DONE".equals(confirmation)) {
                System.out.println("Файл успешно загружен: " + file.getName());
            } else {
                System.out.println("Ошибка: сервер не отправил подтверждение.");
            }
        } catch (Exception e) {
            System.out.println("Ошибка при отправке файла: " + e.getMessage());
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

    private static void handlerDownload(Socket socket, String fileName, BufferedReader reader, PrintWriter writer) {
        try {
            String response = reader.readLine();
            if (!"READY".equals(response)) {
                System.out.println("Ошибка: сервер отказал в передаче файла - " + response);
                return;
            }
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
                    Scanner scanner = new Scanner(System.in);
                    String answer = scanner.nextLine();
                    if ("y".equalsIgnoreCase(answer)) {
                        writer.println("START");
                        saveFile.delete();
                        existingFileSize = 0;
                    } else {
                        writer.println("ABORT");
                        System.out.println("Скачивание отменено.");
                        return;
                    }
                } else {
                    System.out.println("Локальный файл больше серверного. Перекачиваем заново.");
                    writer.println("START");
                    saveFile.delete();
                    existingFileSize = 0;
                }
            } else {
                writer.println("START");
            }
            InputStream inputStream = socket.getInputStream();
            try (FileOutputStream fileOutputStream = new FileOutputStream(saveFile, true)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = existingFileSize;
                long startTime = System.currentTimeMillis();
                while (totalRead < serverFileSize) {
                    int bytesToRead = (int) Math.min(buffer.length, serverFileSize - totalRead);
                    bytesRead = inputStream.read(buffer, 0, bytesToRead);
                    if (bytesRead <= 0) break;
                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
                long elapsedTime = System.currentTimeMillis() - startTime;
                long bitRate = (totalRead * 8) / elapsedTime;
                System.out.printf("Битрейт: %.2f Kbps\n", bitRate / 1000.0);
                if (totalRead == serverFileSize) {
                    String confirmation = reader.readLine();
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
