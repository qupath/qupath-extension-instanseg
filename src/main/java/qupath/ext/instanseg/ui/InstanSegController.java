package qupath.ext.instanseg.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.ext.instanseg.core.InstanSegTask;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.ThreadTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.scripting.QP;
import qupath.fx.dialogs.FileChoosers;

import java.io.File;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Controller for UI pane contained in instanseg_control.fxml
 */
public class InstanSegController extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegController.class);
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.instanseg.ui.strings");

    @FXML
    private CheckComboBox<ColorTransforms.ColorTransform> comboChannels;

    private final Watcher watcher = new Watcher();
    private ExecutorService executor;

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

    private final ExecutorService pool = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("wsinfer", true));
    private final QuPathGUI qupath = QuPathGUI.getInstance();
    private ObjectProperty<Task<?>> pendingTask = new SimpleObjectProperty<>();
    private MessageTextHelper messageTextHelper;

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

        var imageData = qupath.getImageData();
        configureChannelPicker();
        if (imageData != null) {
            updateChannelPicker(imageData);
        }
    }

    private void configureChannelPicker() {
        updateChannelPicker(qupath.getImageData());
        qupath.imageDataProperty().addListener((v, o, n) -> updateChannelPicker(n));
        comboChannels.disableProperty().bind(qupath.imageDataProperty().isNull());
        comboChannels.titleProperty().bind(Bindings.createStringBinding(() -> getTitle(comboChannels),
                comboChannels.getCheckModel().getCheckedItems()));
        FXUtils.installSelectAllOrNoneMenu(comboChannels);
        addSetFromVisible(comboChannels);
    }

    private void addSetFromVisible(CheckComboBox<ColorTransforms.ColorTransform> comboChannels) {
        var mi = new MenuItem();
        mi.setText("Set from visible");
        mi.setOnAction(e -> {
            comboChannels.getCheckModel().clearChecks();
            var activeChannels = QuPathGUI.getInstance().getViewer().getImageDisplay().selectedChannels();
            var channelNames = activeChannels.stream().map(ChannelDisplayInfo::getName).toList();
            var comboItems = comboChannels.getItems();
            for (int i = 0; i < comboItems.size(); i++) {
                if (channelNames.contains(comboItems.get(i).getName() + " (C" + (i+1) + ")")) {
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

    private void updateChannelPicker(ImageData<BufferedImage> imageData) {
        if (imageData == null) return;
        comboChannels.getItems().setAll(getAvailableChannels(imageData));
        comboChannels.getCheckModel().checkIndices(IntStream.range(0, imageData.getServer().nChannels()).toArray());
    }

    private static Collection<ColorTransforms.ColorTransform> getAvailableChannels(ImageData<?> imageData) {
        List<ColorTransforms.ColorTransform> list = new ArrayList<>();
        for (var name : getAvailableUniqueChannelNames(imageData.getServer()))
            list.add(ColorTransforms.createChannelExtractor(name));
        var stains = imageData.getColorDeconvolutionStains();
        if (stains != null) {
            list.add(ColorTransforms.createColorDeconvolvedChannel(stains, 1));
            list.add(ColorTransforms.createColorDeconvolvedChannel(stains, 2));
            list.add(ColorTransforms.createColorDeconvolvedChannel(stains, 3));
        }
        return list;
    }

    /**
     * Create a collection representing available unique channel names, logging a warning if a channel name is duplicated
     * @param server server containing channels
     * @return set of channel names
     */
    private static Collection<String> getAvailableUniqueChannelNames(ImageServer<?> server) {
        var set = new LinkedHashSet<String>();
        int i = 1;
        for (var c : server.getMetadata().getChannels()) {
            var name = c.getName();
            if (!set.contains(name))
                set.add(name);
            else
                logger.warn("Found duplicate channel name! Will skip channel " + i + " (name '" + name + "')");
            i++;
        }
        return set;
    }



    private static String getTitle(CheckComboBox<ColorTransforms.ColorTransform> comboBox) {
        int n = comboBox.getCheckModel().getCheckedItems().size();
        if (n == 0)
            return "No channels selected!";
        if (n == 1)
            return "1 channel selected";
        return n + " channels selected";
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
                        .or(messageTextHelper.warningText.isNotEmpty())
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
        tileSizeChoiceBox.getItems().addAll(128, 256, 512, 1024);
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

    private void addListeners() {
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
        var available = PytorchManager.getAvailableDevices();
        deviceChoices.getItems().setAll(available);
        var selected = InstanSegPreferences.preferredDeviceProperty().get();
        if (available.contains(selected)) {
            deviceChoices.getSelectionModel().select(selected);
        } else {
            deviceChoices.getSelectionModel().selectFirst();
        }
        // Don't bind property for now, since this would cause trouble if the WSInferPrefs.deviceProperty() is
        // changed elsewhere
        deviceChoices.getSelectionModel().selectedItemProperty().addListener(
                (value, oldValue, newValue) -> InstanSegPreferences.preferredDeviceProperty().set(newValue));

    }

    private void configureMessageLabel() {
        messageTextHelper = new MessageTextHelper();
        labelMessage.textProperty().bind(messageTextHelper.messageLabelText);
        if (messageTextHelper.hasWarning.get()) {
            labelMessage.getStyleClass().setAll("warning-message");
        } else {
            labelMessage.getStyleClass().setAll("standard-message");
        }
        messageTextHelper.hasWarning.addListener((observable, oldValue, newValue) -> {
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
                if (isValidModel(file)) {
                    box.getItems().add(InstanSegModel.createModel(file));
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

    class Watcher {
        private static final Logger logger = LoggerFactory.getLogger(Watcher.class);

        private final WatchService watchService;
        private final Map<WatchKey, Path> keys;
        private boolean interrupted;

        @SuppressWarnings("unchecked")
        static <T> WatchEvent<T> cast(WatchEvent<?> event) {
            return (WatchEvent<T>)event;
        }

        private void register(Path dir) throws IOException {
            WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            keys.put(key, dir);
        }

        private void unregister(Path dir) {
            for (var es: keys.entrySet()) {
                if (es.getValue().equals(dir)) {
                    logger.debug("Unregister: {}", es.getValue());
                    es.getKey().cancel();
                    keys.remove(es.getKey());
                }
            }
        }

        /**
         * Creates a WatchService and registers the given directory
         */
        Watcher() throws IOException {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.keys = new ConcurrentHashMap<>();
        }

        /**
         * Process all events for keys queued to the watcher
         */
        void processEvents() {
            while (!interrupted) {

                // wait for key to be signalled
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException x) {
                    return;
                }

                Path dir = keys.get(key);
                if (dir == null) {
                    logger.error("WatchKey not recognized!!");
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    WatchEvent<Path> ev = cast(event);
                    Path name = ev.context();

                    // Context for directory entry event is the file name of entry
                    Path child = dir.resolve(name);

                    // print out event
                    logger.debug("{}: {}", event.kind().name(), child);

                    if (kind == ENTRY_CREATE && isValidModel(name)) {
                        try {
                            modelChoiceBox.getItems().add(InstanSegModel.createModel(child));
                        } catch (IOException e) {
                            logger.error("Unable to add model", e);
                        }
                    }
                    if (kind == ENTRY_DELETE && isValidModel(name)) {
                        modelChoiceBox.getItems().removeIf(model -> model.getPath().equals(child));
                    }

                }

                // reset key and remove from set if directory no longer accessible
                boolean valid = key.reset();
                if (!valid) {
                    keys.remove(key);

                    // all directories are inaccessible
                    if (keys.isEmpty()) {
                        break;
                    }
                }
            }
        }

        void interrupt() {
            interrupted = true;
        }
    }

    private static boolean isValidModel(Path path) {
        // return path.toString().endsWith(".pt"); // if just looking at pt files
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("instanseg.pt")) && Files.exists(path.resolve("rdf.yaml"));
        }
        return false;
    }


    @FXML
    private void runInstanSeg() {
        var model = modelChoiceBox.getSelectionModel().getSelectedItem();
        ImageServer<?> server = qupath.getImageData().getServer();
        var selectedChannels = comboChannels.getCheckModel().getCheckedItems();
        var task = new InstanSegTask(
                model.getPath().resolve("instanseg.pt"),
                selectedChannels,
                InstanSegPreferences.tileSizeProperty().get(),
                InstanSegPreferences.numThreadsProperty().getValue(),
                model.getPixelSizeX() / (double)server.getPixelCalibration().getAveragedPixelSize(),
                deviceChoices.getSelectionModel().getSelectedItem());
        pendingTask.set(task);
        // Reset the pending task when it completes (either successfully or not)
        task.stateProperty().addListener((observable, oldValue, newValue) -> {
            if (Set.of(Worker.State.CANCELLED, Worker.State.SUCCEEDED, Worker.State.FAILED).contains(newValue)) {
                if (pendingTask.get() == task)
                    pendingTask.set(null);
            }
        });
    }

    @FXML
    private void selectAllAnnotations() {
        QP.selectAnnotations();
    }

    @FXML
    private void selectAllTMACores() {
        QP.selectTMACores();
    }

    /**
     * Helper class for determining which text to display in the message label.
     */
    private class MessageTextHelper {

        private final SelectedObjectCounter selectedObjectCounter;

        /**
         * Text to display a warning (because inference can't be run)
         */
        private StringBinding warningText;
        /**
         * Text to display the number of selected objects (usually when inference can be run)
         */
        private StringBinding selectedObjectText;
        /**
         * Text to display in the message label (either the warning or the selected object text)
         */
        private StringBinding messageLabelText;

        /**
         * Binding to check if the warning is empty.
         * Retained here because otherwise code that attaches a listener to {@code warningText.isEmpty()} would need to
         * retain a reference to the binding to prevent garbage collection.
         */
        private BooleanBinding hasWarning;

        MessageTextHelper() {
            this.selectedObjectCounter = new SelectedObjectCounter(qupath.imageDataProperty());
            configureMessageTextBindings();
        }

        private void configureMessageTextBindings() {
            this.warningText = createWarningTextBinding();
            this.selectedObjectText = createSelectedObjectTextBinding();
            this.messageLabelText = Bindings.createStringBinding(() -> {
                var warning = warningText.get();
                if (warning == null || warning.isEmpty())
                    return selectedObjectText.get();
                else
                    return warning;
            }, warningText, selectedObjectText);
            this.hasWarning = warningText.isEmpty().not();
        }

        private StringBinding createSelectedObjectTextBinding() {
            return Bindings.createStringBinding(this::getSelectedObjectText,
                    selectedObjectCounter.numSelectedAnnotations,
                    selectedObjectCounter.numSelectedDetections);
        }

        private String getSelectedObjectText() {
            int nAnnotations = selectedObjectCounter.numSelectedAnnotations.get();
            int nDetections = selectedObjectCounter.numSelectedDetections.get();
            int nCores = selectedObjectCounter.numSelectedTMACores.get();
            if (nAnnotations == 1)
                return resources.getString("ui.selection.annotations-single");
            else if (nAnnotations > 1)
                return String.format(resources.getString("ui.selection.annotations-multiple"), nAnnotations);
            else if (nCores == 1)
                return resources.getString("ui.selection.TMA-cores-single");
            else if (nCores > 1)
                return String.format(resources.getString("ui.selection.TMA-cores-multiple"), nCores);
            else if (nDetections == 1)
                return resources.getString("ui.selection.detections-single");
            else if (nDetections > 1)
                return String.format(resources.getString("ui.selection.detections-multiple"), nDetections);
            else
                return resources.getString("ui.selection.empty");
        }

        private StringBinding createWarningTextBinding() {
            return Bindings.createStringBinding(this::getWarningText,
                    qupath.imageDataProperty(),
                    modelChoiceBox.getSelectionModel().selectedItemProperty(),
                    selectedObjectCounter.numSelectedAnnotations,
                    selectedObjectCounter.numSelectedTMACores,
                    selectedObjectCounter.numSelectedDetections);
        }

        private String getWarningText() {
            if (qupath.imageDataProperty().get() == null)
                return resources.getString("ui.error.no-image");
            if (modelChoiceBox.getSelectionModel().isEmpty())
                return resources.getString("ui.error.no-model");
            if (selectedObjectCounter.numSelectedAnnotations.get() == 0 &&
                    selectedObjectCounter.numSelectedDetections.get() == 0 &&
                    selectedObjectCounter.numSelectedTMACores.get() == 0)
                return resources.getString("ui.error.no-selection");
            return null;
        }
    }


    /**
     * Helper class for maintaining a count of selected annotations and detections,
     * determined from an ImageData property (whose value may change).
     * This addresses the awkwardness of attaching/detaching listeners.
     */
    private static class SelectedObjectCounter {

        private final ObjectProperty<ImageData<?>> imageDataProperty = new SimpleObjectProperty<>();

        private final PathObjectSelectionListener selectionListener = this::selectedPathObjectChanged;

        private final ObservableValue<PathObjectHierarchy> hierarchyProperty;

        private final IntegerProperty numSelectedAnnotations = new SimpleIntegerProperty();
        private final IntegerProperty numSelectedDetections = new SimpleIntegerProperty();
        private final IntegerProperty numSelectedTMACores = new SimpleIntegerProperty();

        SelectedObjectCounter(ObservableValue<ImageData<BufferedImage>> imageDataProperty) {
            this.imageDataProperty.bind(imageDataProperty);
            this.hierarchyProperty = createHierarchyBinding();
            hierarchyProperty.addListener((observable, oldValue, newValue) -> updateHierarchy(oldValue, newValue));
            updateHierarchy(null, hierarchyProperty.getValue());
        }

        private ObjectBinding<PathObjectHierarchy> createHierarchyBinding() {
            return Bindings.createObjectBinding(() -> {
                        var imageData = imageDataProperty.get();
                        return imageData == null ? null : imageData.getHierarchy();
                    },
                    imageDataProperty);
        }

        private void updateHierarchy(PathObjectHierarchy oldValue, PathObjectHierarchy newValue) {
            if (oldValue == newValue)
                return;
            if (oldValue != null)
                oldValue.getSelectionModel().removePathObjectSelectionListener(selectionListener);
            if (newValue != null)
                newValue.getSelectionModel().addPathObjectSelectionListener(selectionListener);
            updateSelectedObjectCounts();
        }

        private void selectedPathObjectChanged(PathObject pathObjectSelected, PathObject previousObject, Collection<PathObject> allSelected) {
            updateSelectedObjectCounts();
        }

        private void updateSelectedObjectCounts() {
            if (!Platform.isFxApplicationThread()) {
                Platform.runLater(this::updateSelectedObjectCounts);
                return;
            }
            var hierarchy = hierarchyProperty.getValue();
            if (hierarchy == null) {
                numSelectedAnnotations.set(0);
                numSelectedDetections.set(0);
                numSelectedTMACores.set(0);
                return;
            }

            var selected = hierarchy.getSelectionModel().getSelectedObjects();
            numSelectedAnnotations.set(
                    (int)selected
                            .stream().filter(PathObject::isAnnotation)
                            .count()
            );
            numSelectedDetections.set(
                    (int)selected
                            .stream().filter(PathObject::isDetection)
                            .count()
            );
            numSelectedTMACores.set(
                    (int)selected
                            .stream().filter(PathObject::isTMACore)
                            .count()
            );
        }

    }

    @FXML
    public void promptForModelDirectory() {
        var modelDirPath = InstanSegPreferences.modelDirectoryProperty().get();
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
        InstanSegPreferences.modelDirectoryProperty().set(newDir.getAbsolutePath());
    }
}
