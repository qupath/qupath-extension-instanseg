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
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSegTask;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collection;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private VBox vBox;
    private final Watcher watcher = new Watcher();
    private ExecutorService executor;

    public VBox getVBox() {
        return vBox;
    }

    @FXML
    private TextField tfModelDirectory;
    @FXML
    private SearchableComboBox<Path> modelChoiceBox;
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
    private Button selectAllAnnotationsButton;

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
        tileSizeChoiceBox.getItems().addAll(128, 256, 512, 1024);
        tileSizeChoiceBox.getSelectionModel().select(Integer.valueOf(256));
        addListeners();
        configureAvailableDevices();
        modelChoiceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Path object) {
                if (object == null) return null;
                return object.getFileName().toString();
            }

            @Override
            public Path fromString(String string) {
                return Path.of(InstanSegPreferences.modelDirectoryProperty().get(), string);
            }
        });
    }

    public void interrupt() {
        watcher.interrupt();
    }

    private void addListeners() {
        tfModelDirectory.textProperty().bindBidirectional(InstanSegPreferences.modelDirectoryProperty());
        handleModelDirectory(tfModelDirectory.getText());
        tfModelDirectory.textProperty().addListener((v, o, n) -> handleModelDirectory(n));
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
        SpinnerValueFactory.IntegerSpinnerValueFactory factory = (SpinnerValueFactory.IntegerSpinnerValueFactory) threadSpinner.getValueFactory();
        factory.setMax(Runtime.getRuntime().availableProcessors());
        threadSpinner.getValueFactory().valueProperty().bindBidirectional(InstanSegPreferences.numThreadsProperty());
        InstanSegPreferences.tileSizeProperty().bind(tileSizeChoiceBox.valueProperty());
    }

    private void handleModelDirectory(String n) {
        var path = Path.of(n);
        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                watcher.register(path);
            } catch (IOException e) {
                logger.error("Unable to watch directory", e);
            }
        }
        tryToPopulateChoiceBox(n);
    }

    private void configureAvailableDevices() {
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


    void tryToPopulateChoiceBox(String dir) {
        if (dir == null || dir.isEmpty()) return;
        modelChoiceBox.getItems().clear();
        var path = Path.of(dir);
        if (!Files.exists(path)) return;
        try (var ps = Files.list(path)) {
            for (var file: ps.toList()) {
                if (file.toString().endsWith(".pt")) {
                    modelChoiceBox.getItems().add(file);
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

                    if (kind == ENTRY_CREATE && name.toString().endsWith(".pt")) {
                        modelChoiceBox.getItems().add(child);
                    }
                    if (kind == ENTRY_DELETE && name.toString().endsWith(".pt")) {
                        modelChoiceBox.getItems().remove(child);
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


    @FXML
    private void runInstanSeg() {
        var task = new InstanSegTask(
                modelChoiceBox.getSelectionModel().getSelectedItem(),
                InstanSegPreferences.tileSizeProperty().get(),
                InstanSegPreferences.numThreadsProperty().getValue(),
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


}
