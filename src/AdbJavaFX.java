import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.collections.ObservableList;
import java.util.Optional;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.HashMap;
import java.util.Map;
import javafx.stage.DirectoryChooser; //Added in order for user to choose directory for file to be pulled



public class AdbJavaFX extends Application {

    private String selectedDevice = null;
    private ListView<String> fileList;
    private FilteredList<String> filteredItems;
    private TextField searchField;

    private TextArea logcatOutput;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Process logcatProcess;

    private final String[][] adbCommands = {
            {"List Screen Recording", "adb shell ls /sdcard/movies/*"},
            {"List Screenshots", "adb shell ls /sdcard/Pictures/Screenshots/*"},
            {"List Camera Photos with jpg", "adb shell ls /storage/emulated/0/DCIM/Camera/*.jpg"},
            {"Last Camera Videos with mp4", "adb shell ls /storage/emulated/0/DCIM/Camera/*.mp4"},
            {"List DCIM Camera Photos/Videos", "adb shell ls 'sdcard/DCIM/Camera/*'"},
            {"List Wifi Logs", "adb shell ls /storage/emulated/0/Android/data/com.android.pixellogger/files/logs/wifi_sniffer/*"},
            {"List Audio Logs", "adb shell ls /storage/emulated/0/Android/data/com.android.pixellogger/files/logs/audio_logs/*"},
            {"List Modem Logs", "adb shell ls /storage/emulated/0/Android/data/com.android.pixellogger/files/logs/logs/*"},
            {"Downloaded Cloud Media", "adb shell ls /storage/emulated/0/DCIM/Restored/*"},
            {"Maestro Logs", "adb ls /storage/emulated/0/Android/data/com.google.android.apps.wearables.maestro.companion/files/"},
            {"Generate Bug Report", "adb bugreport"},
            {"Start Logcat", "adb logcat"},
            {"Pull RAMDUMP", "adb pull /data/vendor/ramdump"},
            {"List DCIM Content", "adb shell ls /storage/emulated/0/DCIM"}
    };

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(10);
        root.setPadding(new javafx.geometry.Insets(10));

        // Buttons
        VBox buttonBox = new VBox(5);
        for (String[] cmd : adbCommands) {
            Button btn = new Button(cmd[0]);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setOnAction(e -> handleCommand(cmd[1]));
            buttonBox.getChildren().add(btn);
        }

        // Pull and Exit
        Button pullBtn = new Button("Pull Selected Files");
        pullBtn.setOnAction(e -> pullSelectedFiles());
        buttonBox.getChildren().add(pullBtn);

        Button exitBtn = new Button("Exit");
        exitBtn.setOnAction(e -> Platform.exit());
        buttonBox.getChildren().add(exitBtn);

        // Search bar
        searchField = new TextField();
        searchField.setPromptText("Search files...");

        // File list and filter setup
        fileList = new ListView<>();
        filteredItems = new FilteredList<>(FXCollections.observableArrayList(), s -> true);
        fileList.setItems(filteredItems);

        // Live filter on search
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filteredItems.setPredicate(item -> item.toLowerCase().contains(newVal.toLowerCase()));
        });

        // Double-click to copy path
        fileList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = fileList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(selected);
                    Clipboard.getSystemClipboard().setContent(content);
                    showAlert("File path copied to clipboard.");
                }
            }
        });

        // Right-click context menu
        ContextMenu contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("Copy Path");
        MenuItem pullItem = new MenuItem("Pull File");
        copyItem.setOnAction(e -> {
            String selected = fileList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(selected);
                Clipboard.getSystemClipboard().setContent(content);
                showAlert("Copied to clipboard.");
            }
        });
        pullItem.setOnAction(e -> {
            String selected = fileList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                runAdbCommand("adb pull " + selected);
                showAlert("File pulled.");
            }
        });
        contextMenu.getItems().addAll(copyItem, pullItem);
        fileList.setContextMenu(contextMenu);

        logcatOutput = new TextArea();
        logcatOutput.setPrefRowCount(10);

        progressBar = new ProgressBar(0);
        progressLabel = new Label("0%");
        progressBar.setVisible(false);
        progressLabel.setVisible(false);

        Button stopLogcatBtn = new Button("Stop Logcat");
        stopLogcatBtn.setOnAction(e -> stopLogcat());
        stopLogcatBtn.setVisible(false);

        root.getChildren().addAll(buttonBox, searchField, fileList, logcatOutput, stopLogcatBtn, progressBar, progressLabel);

        Scene scene = new Scene(root, 800, 700);
        stage.setScene(scene);
        stage.setTitle("ADB Command Executor - JavaFX");
        stage.show();
        root.setOnDragOver(event -> {
            if (event.getGestureSource() != root &&
                    event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        root.setOnDragDropped(event -> {
            var db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                success = true;
                pushFilesToDevice(db.getFiles());
            }
            event.setDropCompleted(success);
            event.consume();
        });

    }

    private void handleCommand(String command) {
        if (command.equals("adb bugreport")) {
            runBugreport();
        } else if (command.equals("adb logcat")) {
            startLogcat();
        } else {
            List<String> result = runAdbCommand(command);
            Platform.runLater(() -> {
                if (result.isEmpty()) {
                    showAlert("No files found in this folder.");
                } else {
                    ObservableList<String> backingList = (ObservableList<String>) filteredItems.getSource();
                    backingList.setAll(result);
                }
            });
        }
    }
//Updated for pushing to Android devices
private List<String> runAdbCommand(List<String> commandParts) {
    List<String> output = new ArrayList<>();
    try {
        Process process = new ProcessBuilder(commandParts).start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            output.add(line.trim());
        }
        process.waitFor();
    } catch (IOException | InterruptedException e) {
        output.add("Error: " + e.getMessage());
    }
    return output;
}


    private List<String> runAdbCommand(String command) {
        List<String> output = new ArrayList<>();

        if (command.startsWith("adb ") && !command.contains("-s")) {
            if (selectedDevice == null && !selectDevice()) {
                output.add("No device selected.");
                return output;
            }
            command = "adb -s " + selectedDevice + " " + command.substring(4);
        }

        try {
            Process process = new ProcessBuilder(command.split(" ")).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.toLowerCase().contains("no such file")) {
                    output.add(line);
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            output.add("Error: " + e.getMessage());
        }
        return output;
    }


    private void startLogcat() {
        logcatOutput.clear();
        new Thread(() -> {
            try {
                logcatProcess = new ProcessBuilder("adb", "logcat").start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    Platform.runLater(() -> logcatOutput.appendText(finalLine + "\n"));
                }
            } catch (IOException e) {
                Platform.runLater(() -> logcatOutput.appendText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void stopLogcat() {
        if (logcatProcess != null) {
            logcatProcess.destroy();
        }
    }

    private void runBugreport() {
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        new Thread(() -> {
            for (int i = 1; i <= 100; i++) {
                int finalI = i;
                Platform.runLater(() -> {
                    progressBar.setProgress(finalI / 100.0);
                    progressLabel.setText(finalI + "%");
                });
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
            }

            List<String> result = runAdbCommand("adb bugreport");
            Platform.runLater(() -> {
                ObservableList<String> backingList = (ObservableList<String>) filteredItems.getSource();
                backingList.setAll(result);
                progressBar.setVisible(false);
                progressLabel.setVisible(false);
            });
        }).start();
    }


    private void pullSelectedFiles() {
    List<String> selected = fileList.getSelectionModel().getSelectedItems();
    if (selected.isEmpty()) {
        showAlert("No files selected to pull.");
        return;
    }

    // Ask the user to pick a local folder
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("Select Destination Folder");
    File destinationDir = chooser.showDialog(null);

    if (destinationDir == null || !destinationDir.isDirectory()) {
        showAlert("Invalid destination folder.");
        return;
    }

    new Thread(() -> {
        int total = selected.size();
        Platform.runLater(() -> {
            progressBar.setVisible(true);
            progressLabel.setVisible(true);
            progressBar.setProgress(0);
        });

        for (int i = 0; i < total; i++) {
            String remotePath = selected.get(i);
            String fileName = new File(remotePath).getName();
            File localFile = new File(destinationDir, fileName);

            List<String> command = List.of(
                "adb", "-s", selectedDevice,
                "pull", remotePath, localFile.getAbsolutePath()
            );

            List<String> result = runAdbCommand(command);

            int percent = (int) ((i + 1) / (double) total * 100);
            int finalI = i;
            Platform.runLater(() -> {
                progressBar.setProgress((finalI + 1) / (double) total);
                progressLabel.setText(percent + "%");
                showAlert("Pulled to: " + localFile.getAbsolutePath());
            });
        }

        Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
        });
    }).start();
}


    private boolean selectDevice() {
        List<String> devices = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("adb", "devices").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith("\tdevice") && !line.startsWith("List")) {
                    devices.add(line.split("\t")[0]);
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            showAlert("Error checking devices: " + e.getMessage());
            return false;
        }

        if (devices.isEmpty()) {
            showAlert("No devices connected.");
            return false;
        } else if (devices.size() == 1) {
            selectedDevice = devices.get(0);
            return true;
        } else {
            ChoiceDialog<String> dialog = new ChoiceDialog<>(devices.get(0), devices);
            dialog.setTitle("Select ADB Device");
            dialog.setHeaderText("Multiple devices detected.");
            dialog.setContentText("Choose a device:");
            Optional<String> result = dialog.showAndWait();
            result.ifPresent(device -> selectedDevice = device);
            return result.isPresent();
        }
    }

    private void pushFilesToDevice(List<File> files) {
    if (files == null || files.isEmpty()) {
        showAlert("No valid files dropped.");
        return;
    }

    if (selectedDevice == null && !selectDevice()) {
        showAlert("No device selected.");
        return;
    }

    List<String> folders = List.of(
            "/sdcard/Download/",
            "/sdcard/Documents/",
            "/sdcard/Music/",
            "/sdcard/Pictures/",
            "/sdcard/DCIM/",
            "/sdcard/Movies/"
    );

    Map<File, String> fileToFolder = new HashMap<>();
    String selectedFolder = null;

    if (files.size() > 1) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Push to Same Folder?");
        confirm.setHeaderText("Multiple files detected.");
        confirm.setContentText("Do you want to push all files to the same folder?");
        ButtonType yes = new ButtonType("Yes");
        ButtonType no = new ButtonType("No");
        confirm.getButtonTypes().setAll(yes, no);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isPresent() && choice.get() == yes) {
            ChoiceDialog<String> folderDialog = new ChoiceDialog<>(folders.get(0), folders);
            folderDialog.setTitle("Select Folder");
            folderDialog.setHeaderText("Choose where to push all files:");
            folderDialog.setContentText("Target folder:");
            Optional<String> result = folderDialog.showAndWait();
            if (result.isEmpty()) return;
            selectedFolder = result.get();
            for (File file : files) {
                fileToFolder.put(file, selectedFolder);
            }
        } else {
            for (File file : files) {
                final String[] selectedPath = new String[1];
                CountDownLatch latch = new CountDownLatch(1);

                Platform.runLater(() -> {
                    ChoiceDialog<String> folderDialog = new ChoiceDialog<>(folders.get(0), folders);
                    folderDialog.setTitle("Select Folder");
                    folderDialog.setHeaderText("Choose folder for: " + file.getName());
                    folderDialog.setContentText("Target folder:");
                    Optional<String> result = folderDialog.showAndWait();
                    selectedPath[0] = result.orElse(null);
                    latch.countDown();
                });

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (selectedPath[0] != null) {
                    fileToFolder.put(file, selectedPath[0]);
                }
            }
        }
    } else {
        File file = files.get(0);
        ChoiceDialog<String> folderDialog = new ChoiceDialog<>(folders.get(0), folders);
        folderDialog.setTitle("Select Folder");
        folderDialog.setHeaderText("Choose folder for: " + file.getName());
        folderDialog.setContentText("Target folder:");
        Optional<String> result = folderDialog.showAndWait();
        if (result.isEmpty()) return;
        fileToFolder.put(file, result.get());
    }

    new Thread(() -> {
        int total = fileToFolder.size();
        int i = 0;
        for (Map.Entry<File, String> entry : fileToFolder.entrySet()) {
            File file = entry.getKey();
            String folder = entry.getValue();
            String targetPath = folder + file.getName();
            updateProgress(++i, total);
            System.out.println("Pushing: " + file.getAbsolutePath());

            List<String> cmd = List.of(
                    "adb", "-s", selectedDevice,
                    "push", file.getAbsolutePath(), targetPath
            );
            List<String> result = runAdbCommand(cmd);
            result.forEach(System.out::println);
        }
        hideProgress();
        Platform.runLater(() -> showAlert("Files pushed successfully."));
    }).start();
}


    private void updateProgress(int current, int total) {
        if (total <= 0) return;
        double progress = (double) current / total;
        Platform.runLater(() -> {
            progressBar.setVisible(true);
            progressLabel.setVisible(true);
            progressBar.setProgress(progress);
            progressLabel.setText((int)(progress * 100) + "%");
        });
    }

    private void hideProgress() {
        Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
        });
    }




    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}