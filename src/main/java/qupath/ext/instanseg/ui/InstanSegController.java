package qupath.ext.instanseg.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.DetectionMeasurer;
import qupath.ext.instanseg.core.InstanSeg;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.ThreadTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.TaskRunnerFX;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.stream.IntStream;

/**
 * Controller for UI pane contained in instanseg_control.fxml
 */
public class InstanSegController extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegController.class);
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.instanseg.ui.strings");

    @FXML
    private CheckComboBox<ChannelSelectItem> comboChannels;
    @FXML
    private TextField tfModelDirectory;
    @FXML
    private SearchableComboBox<InstanSegModel> modelChoiceBox;
    @FXML
    private Button runButton;
    @FXML
    private Label labelMessage;
    @FXML
    private ChoiceBox<String> deviceChoices;
    @FXML
    private ChoiceBox<Integer> tileSizeChoiceBox;
    @FXML
    private Spinner<Integer> threadSpinner;
    @FXML
    private ToggleButton selectAllAnnotationsButton;
    @FXML
    private ToggleButton selectAllTMACoresButton;
    @FXML
    private CheckBox nucleiOnlyCheckBox;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("instanseg", true));
    private final QuPathGUI qupath = QuPathGUI.getInstance();
    private ObjectProperty<FutureTask<?>> pendingTask = new SimpleObjectProperty<>();
    private MessageTextHelper messageTextHelper;

    private final Watcher watcher = new Watcher(modelChoiceBox);
    private ExecutorService executor;

    public static InstanSegController createInstance() throws IOException {
        return new InstanSegController();
    }

    private InstanSegController() throws IOException {
        var url = InstanSegController.class.getResource("instanseg_control.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();

        configureMessageLabel();
        configureTileSizes();
        configureDeviceChoices();
        configureModelChoices();
        configureSelectButtons();
        configureRunning();
        configureThreadSpinner();

        configureChannelPicker();
    }

    private void configureChannelPicker() {
        updateChannelPicker(qupath.getImageData());
        qupath.imageDataProperty().addListener((v, o, n) -> updateChannelPicker(n));
        comboChannels.disableProperty().bind(qupath.imageDataProperty().isNull());
        comboChannels.setTitle(getCheckComboBoxText(comboChannels));
        comboChannels.getItems().addListener((ListChangeListener<ChannelSelectItem>) c -> {
            comboChannels.setTitle(getCheckComboBoxText(comboChannels));
        });
        comboChannels.getCheckModel().getCheckedItems().addListener((ListChangeListener<ChannelSelectItem>) c -> {
            comboChannels.setTitle(getCheckComboBoxText(comboChannels));
        });
        FXUtils.installSelectAllOrNoneMenu(comboChannels);
        addSetFromVisible(comboChannels);
    }

    private void updateChannelPicker(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return;
        }
        comboChannels.getItems().clear();
        comboChannels.getItems().setAll(getAvailableChannels(imageData));
        comboChannels.getCheckModel().checkIndices(IntStream.range(0, imageData.getServer().nChannels()).toArray());
    }

    private static String getCheckComboBoxText(CheckComboBox<ChannelSelectItem> comboBox) {
        int n = comboBox.getCheckModel().getCheckedItems().stream()
                .filter(Objects::nonNull)
                .toList()
                .size();
        if (n == 0)
            return "No channels selected!";
        if (n == 1)
            return "1 channel selected";
        return n + " channels selected";
    }

    private void addSetFromVisible(CheckComboBox<ChannelSelectItem> comboChannels) {
        var mi = new MenuItem();
        mi.setText("Set from visible");
        mi.setOnAction(e -> {
            comboChannels.getCheckModel().clearChecks();
            var activeChannels = qupath.getViewer().getImageDisplay().selectedChannels();
            var channelNames = activeChannels.stream()
                    .map(ChannelDisplayInfo::getName)
                    .toList();
            if (qupath.getImageData() != null && !qupath.getImageData().getServer().isRGB()) {
                channelNames = channelNames.stream()
                        .map(s -> s.replaceAll(" \\(C\\d+\\)$", ""))
                        .toList();
            }
            var comboItems = comboChannels.getItems();
            for (int i = 0; i < comboItems.size(); i++) {
                if (channelNames.contains(comboItems.get(i).getName())) {
                    comboChannels.getCheckModel().check(i);
                }
            }
        });
        qupath.imageDataProperty().addListener((v, o, n) -> {
            if (n == null) {
                return;
            }
            mi.setDisable(n.isBrightfield());
        });
        if (qupath.getImageData() != null) {
            mi.setDisable(qupath.getImageData().isBrightfield());
        }
        comboChannels.getContextMenu().getItems().add(mi);
    }

    private static Collection<ChannelSelectItem> getAvailableChannels(ImageData<?> imageData) {
        List<ChannelSelectItem> list = new ArrayList<>();
        Set<String> names = new HashSet<>();
        var server = imageData.getServer();
        int i = 1;
        boolean hasDuplicates = false;
        ChannelSelectItem item;
        for (var channel : server.getMetadata().getChannels()) {
            var name = channel.getName();
            if (names.contains(name)) {
                logger.warn("Found duplicate channel name! Channel " + i + " (name '" + name + "').");
                logger.warn("Using channel indices instead of names because of duplicated channel names.");
                hasDuplicates = true;
            }
            names.add(name);
            if (hasDuplicates) {
                item = new ChannelSelectItem(name, i - 1);
            } else {
                item = new ChannelSelectItem(name);
            }
            list.add(item);
            i++;
        }
        var stains = imageData.getColorDeconvolutionStains();
        if (stains != null) {
            for (i = 1; i < 4; i++) {
                list.add(new ChannelSelectItem(stains, i));
            }
        }
        return list;
    }

    private void configureThreadSpinner() {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory = (SpinnerValueFactory.IntegerSpinnerValueFactory) threadSpinner.getValueFactory();
        factory.setMax(Runtime.getRuntime().availableProcessors());
        threadSpinner.getValueFactory().valueProperty().bindBidirectional(InstanSegPreferences.numThreadsProperty());
    }

    private void configureRunning() {
        runButton.disableProperty().bind(
                qupath.imageDataProperty().isNull()
                        .or(pendingTask.isNotNull())
                        .or(modelChoiceBox.getSelectionModel().selectedItemProperty().isNull())
                        .or(messageTextHelper.warningText().isNotEmpty())
                        .or(deviceChoices.getSelectionModel().selectedItemProperty().isNull())
        );
        pendingTask.addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                pool.execute(newValue);
            }
        });
    }

    private void configureModelChoices() {
        addRemoteModels(modelChoiceBox);
        tfModelDirectory.textProperty().bindBidirectional(InstanSegPreferences.modelDirectoryProperty());
        handleModelDirectory(tfModelDirectory.getText());
        tfModelDirectory.textProperty().addListener((v, o, n) -> handleModelDirectory(n));
    }

    private static void addRemoteModels(ComboBox<InstanSegModel> comboBox) {
        // todo: list models from eg a JSON file
    }

    private void configureTileSizes() {
        tileSizeChoiceBox.getItems().addAll(128, 256, 512, 1024, 1536, 2048, 3072, 4096);
        tileSizeChoiceBox.getSelectionModel().select(Integer.valueOf(256));
        tileSizeChoiceBox.setValue(InstanSegPreferences.tileSizeProperty().getValue());
        tileSizeChoiceBox.valueProperty().addListener((v, o, n) -> InstanSegPreferences.tileSizeProperty().set(n));
    }

    private void configureSelectButtons() {
        selectAllAnnotationsButton.disableProperty().bind(qupath.imageDataProperty().isNull());
        selectAllTMACoresButton.disableProperty().bind(qupath.imageDataProperty().isNull());
        overrideToggleSelected(selectAllAnnotationsButton);
        overrideToggleSelected(selectAllTMACoresButton);
    }

    // Hack to prevent the toggle buttons from staying selected
    // This allows us to use a segmented button with the appearance of regular, non-toggle buttons
    private static void overrideToggleSelected(ToggleButton button) {
        button.selectedProperty().addListener((value, oldValue, newValue) -> button.setSelected(false));
    }

    public void interrupt() {
        watcher.interrupt();
    }

    private void handleModelDirectory(String n) {
        if (n == null) return;
        var path = Path.of(n);
        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                watcher.register(path); // todo: unregister
                addModelsFromPath(n, modelChoiceBox);
            } catch (IOException e) {
                logger.error("Unable to watch directory", e);
            }
        }
    }

    private void configureDeviceChoices() {
        deviceChoices.disableProperty().bind(Bindings.size(deviceChoices.getItems()).isEqualTo(0));
        addDeviceChoices();
        // Don't bind property for now, since this would cause trouble if the InstanSegPreferences.preferredDeviceProperty() is
        // changed elsewhere
        deviceChoices.getSelectionModel().selectedItemProperty().addListener(
                (value, oldValue, newValue) -> InstanSegPreferences.preferredDeviceProperty().set(newValue));
    }

    private void addDeviceChoices() {
        var available = PytorchManager.getAvailableDevices();
        deviceChoices.getItems().setAll(available);
        var selected = InstanSegPreferences.preferredDeviceProperty().get();
        if (available.contains(selected)) {
            deviceChoices.getSelectionModel().select(selected);
        } else {
            deviceChoices.getSelectionModel().selectFirst();
        }
    }

    private void configureMessageLabel() {
        messageTextHelper = new MessageTextHelper(modelChoiceBox, deviceChoices);
        labelMessage.textProperty().bind(messageTextHelper.messageLabelText());
        if (messageTextHelper.hasWarning().get()) {
            labelMessage.getStyleClass().setAll("warning-message");
        } else {
            labelMessage.getStyleClass().setAll("standard-message");
        }
        messageTextHelper.hasWarning().addListener((observable, oldValue, newValue) -> {
            if (newValue)
                labelMessage.getStyleClass().setAll("warning-message");
            else
                labelMessage.getStyleClass().setAll("standard-message");
        });
    }

    static void addModelsFromPath(String dir, ComboBox<InstanSegModel> box) {
        if (dir == null || dir.isEmpty()) return;
        // See https://github.com/controlsfx/controlsfx/issues/1320
        box.setItems(FXCollections.observableArrayList());
        var path = Path.of(dir);
        if (!Files.exists(path)) return;
        try (var ps = Files.list(path)) {
            for (var file: ps.toList()) {
                if (InstanSegModel.isValidModel(file)) {
                    box.getItems().add(InstanSegModel.fromPath(file));
                }
            }
        } catch (IOException e) {
            logger.error("Unable to list directory", e);
        }
    }

    public void restart() {
        executor = Executors.newSingleThreadExecutor();
        executor.submit(watcher::processEvents);
    }

    @FXML
    private void runInstanSeg() {
        if (!PytorchManager.hasPyTorchEngine()) {
            if (!Dialogs.showConfirmDialog(resources.getString("title"), resources.getString("ui.pytorch"))) {
                Dialogs.showWarningNotification(resources.getString("title"), resources.getString("ui.pytorch-popup"));
                return;
            }
        }

        var model = modelChoiceBox.getSelectionModel().getSelectedItem();
        ImageServer<?> server = qupath.getImageData().getServer();
        // todo: how to record this in workflow?
        List<ChannelSelectItem> selectedChannels = comboChannels
                .getCheckModel().getCheckedItems()
                .stream()
                .filter(Objects::nonNull)
                .toList();

        var task = new InstanSegTask(server, model, selectedChannels);
        pendingTask.set(task);
        // Reset the pending task when it completes (either successfully or not)
        task.stateProperty().addListener((observable, oldValue, newValue) -> {
            if (Set.of(Worker.State.CANCELLED, Worker.State.SUCCEEDED, Worker.State.FAILED).contains(newValue)) {
                if (pendingTask.get() == task)
                    pendingTask.set(null);
            }
        });
    }

    private class InstanSegTask extends Task<Void> {

        private final List<ChannelSelectItem> channels;
        private final ImageServer<?> server;
        private final InstanSegModel model;

        InstanSegTask(ImageServer<?> server, InstanSegModel model, List<ChannelSelectItem> channels) {
            this.server = server;
            this.model = model;
            this.channels = channels;
        }


        @Override
        protected Void call() {
            // Ensure PyTorch engine is available
            if (!PytorchManager.hasPyTorchEngine()) {
                downloadPyTorch();
            }
            String cmd = String.format("""
                            import qupath.ext.instanseg.core.InstanSeg

                            def channels = %s;
                            def instanSeg = InstanSeg.builder()
                                .modelPath("%s")
                                .device("%s")
                                .numOutputChannels(%d)
                                .channels(channels)
                                .tileDims(%d)
                                .imageData(QP.getCurrentImageData())
                                .downsample(%f)
                                .nThreads(QPEx.createTaskRunner(%d))
                                .build();
                            instanSeg.detectObjects();
                            """,
                    ChannelSelectItem.toConstructorString(channels),
                    model.getPath(),
                    deviceChoices.getSelectionModel().getSelectedItem(),
                    nucleiOnlyCheckBox.isSelected() ? 1 : 2,
                    InstanSegPreferences.tileSizeProperty().get(),
                    model.getPixelSizeX() / (double) server.getPixelCalibration().getAveragedPixelSize(),
                    InstanSegPreferences.numThreadsProperty().getValue()
            );
            qupath.getImageData().getHistoryWorkflow()
                    .addStep(
                            new DefaultScriptableWorkflowStep(resources.getString("workflow.title"), cmd)
                    );
            var taskRunner = new TaskRunnerFX(
                    QuPathGUI.getInstance(),
                    InstanSegPreferences.numThreadsProperty().getValue());

            var imageData = qupath.getImageData();
            var selectedObjects = qupath.getImageData().getHierarchy().getSelectionModel().getSelectedObjects();
            var instanSeg = InstanSeg.builder()
                    .model(model)
                    .imageData(imageData)
                    .device(deviceChoices.getSelectionModel().getSelectedItem())
                    .numOutputChannels(nucleiOnlyCheckBox.isSelected() ? 1 : 2)
                    .channels(channels.stream().map(ChannelSelectItem::getTransform).toList())
                    .tileDims(InstanSegPreferences.tileSizeProperty().get())
                    .downsample(model.getPixelSizeX() / (double) server.getPixelCalibration().getAveragedPixelSize())
                    .taskRunner(taskRunner)
                    .build();
            instanSeg.detectObjects(selectedObjects);
            qupath.getImageData().getHierarchy().fireHierarchyChangedEvent(this);
            for (PathObject po: selectedObjects) {
                makeMeasurements(imageData, po.getChildObjects(), model);
            }
            if (model.nFailed() > 0) {
                var errorMessage = String.format(resources.getString("error.tiles-failed"), model.nFailed());
                logger.error(errorMessage);
                Dialogs.showErrorMessage(resources.getString("title"),
                        errorMessage);
            }
            return null;
        }
    }

    private void downloadPyTorch() {
        Platform.runLater(() -> Dialogs.showInfoNotification(resources.getString("title"), resources.getString("ui.pytorch-downloading")));
        PytorchManager.getEngineOnline();
        Platform.runLater(this::addDeviceChoices);
    }

    public void makeMeasurements(ImageData<BufferedImage> imageData, Collection<PathObject> detections, InstanSegModel model) {
        DetectionMeasurer.builder()
                .pixelSize(model.getPixelSizeX())
                .imageData(imageData)
                .build().makeMeasurements(detections);
    }



    @FXML
    private void selectAllAnnotations() {
        var hierarchy = qupath.getImageData().getHierarchy();
        hierarchy.getSelectionModel().setSelectedObjects(hierarchy.getAnnotationObjects(), null);
    }

    @FXML
    private void selectAllTMACores() {
        var hierarchy = qupath.getImageData().getHierarchy();
        hierarchy.getSelectionModel().setSelectedObjects(hierarchy.getTMAGrid().getTMACoreList(), null);
    }

    /**
     * Open the model directory in the system file browser when double-clicked.
     * @param event
     */
    @FXML
    public void handleModelDirectoryLabelClick(MouseEvent event) {
        if (event.getClickCount() != 2) {
            return;
        }
        var path = InstanSegPreferences.modelDirectoryProperty().get();
        if (path == null || path.isEmpty()) {
            return;
        }
        var file = new File(path);
        if (file.exists()) {
            GuiTools.browseDirectory(file);
        } else {
            logger.debug("Can't browse directory for {}", file);
        }
    }

    @FXML
    public void promptForModelDirectory() {
        promptToUpdateDirectory(InstanSegPreferences.modelDirectoryProperty());
    }

    private void promptToUpdateDirectory(StringProperty dirPath) {
        var modelDirPath = dirPath.get();
        var dir = modelDirPath == null || modelDirPath.isEmpty() ? null : new File(modelDirPath);
        if (dir != null) {
            if (dir.isFile())
                dir = dir.getParentFile();
            else if (!dir.exists())
                dir = null;
        }
        var newDir = FileChoosers.promptForDirectory(
                FXUtils.getWindow(tfModelDirectory), // Get window from any node here
                resources.getString("ui.model-directory.choose-directory"),
                dir);
        if (newDir == null)
            return;
        dirPath.set(newDir.getAbsolutePath());
    }

}
