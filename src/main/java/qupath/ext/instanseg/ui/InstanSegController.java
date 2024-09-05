package qupath.ext.instanseg.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
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
import javafx.scene.web.WebView;
import org.commonmark.renderer.html.HtmlRenderer;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSeg;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.ext.instanseg.core.InstanSegResults;
import qupath.ext.instanseg.core.PytorchManager;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.ThreadTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.TaskRunnerFX;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.WebViews;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Controller for UI pane contained in instanseg_control.fxml
 */
public class InstanSegController extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegController.class);

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.instanseg.ui.strings");
    private final Watcher watcher;

    @FXML
    private CheckComboBox<ChannelSelectItem> comboChannels;
    @FXML
    private TextField tfModelDirectory;
    @FXML
    private SearchableComboBox<InstanSegModel> modelChoiceBox;
    @FXML
    private Button runButton;
    @FXML
    private Button downloadButton;
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
    private CheckComboBox<InstanSegOutput> checkComboOutputs;
    @FXML
    private CheckBox makeMeasurementsCheckBox;
    @FXML
    private Button infoButton;
    @FXML
    private Label modelDirLabel;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("instanseg", true));
    private final QuPathGUI qupath;
    private final ObjectProperty<FutureTask<?>> pendingTask = new SimpleObjectProperty<>();
    private MessageTextHelper messageTextHelper;

    private final BooleanProperty needsUpdating = new SimpleBooleanProperty();

    private static final ObjectBinding<Path> modelDirectoryProperty = Bindings.createObjectBinding(
            () -> tryToGetPath(InstanSegPreferences.modelDirectoryProperty().get()),
            InstanSegPreferences.modelDirectoryProperty()
    );

    private final BooleanBinding isModelDirectoryValid = Bindings.createBooleanBinding(
            () -> {
                var path = modelDirectoryProperty.get();
                return path != null && Files.isDirectory(path);
            },
            modelDirectoryProperty
    );

    /**
     * Create an instance of the InstanSeg GUI pane.
     * @param qupath The QuPath GUI it should be attached to.
     * @return A handle on the UI element.
     * @throws IOException If the FXML or resources fail to load.
     */
    public static InstanSegController createInstance(QuPathGUI qupath) throws IOException {
        return new InstanSegController(qupath);
    }

    private InstanSegController(QuPathGUI qupath) throws IOException {
        this.qupath = qupath;
        var url = InstanSegController.class.getResource("instanseg_control.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        watcher = new Watcher(modelChoiceBox);

        configureMessageLabel();
        configureDirectoryLabel();
        configureTileSizes();
        configureDeviceChoices();
        configureModelChoices();
        configureSelectButtons();
        configureRunning();
        configureThreadSpinner();
        BooleanBinding currentModelIsDownloaded = createModelDownloadedBinding();
        infoButton.disableProperty().bind(currentModelIsDownloaded.not());
        downloadButton.disableProperty().bind(
                currentModelIsDownloaded.or(
                        modelChoiceBox.getSelectionModel().selectedItemProperty().isNull())
        );
        configureChannelPicker();
        configureOutputChannelCombo();
    }

    private void configureOutputChannelCombo() {
        // Quick way to match widths...
        checkComboOutputs.prefWidthProperty().bind(comboChannels.widthProperty());
        // Show a better title than the text of all selections
        checkComboOutputs.getCheckModel().getCheckedItems().addListener((ListChangeListener<InstanSegOutput>) c -> {
            var list = c.getList();
            if (list.isEmpty() || list.size() == checkComboOutputs.getItems().size())
                checkComboOutputs.setTitle("All available");
            else if (list.size() == 1) {
                checkComboOutputs.setTitle(list.getFirst().toString());
            } else {
                checkComboOutputs.setTitle(list.size() + " selected");
            }
        });
    }

    private BooleanBinding createModelDownloadedBinding() {
        return Bindings.createBooleanBinding(
                () -> {
                    var model = modelChoiceBox.getSelectionModel().getSelectedItem();
                    if (model == null) {
                        return false;
                    }
                    var modelDir = getModelDirectory().orElse(null);
                    return modelDir != null && model.isDownloaded(modelDir);
                },
                modelChoiceBox.getSelectionModel().selectedItemProperty(), needsUpdating,
                InstanSegPreferences.modelDirectoryProperty());
    }


    void interrupt() {
        watcher.interrupt();
    }

    /**
     * Open the model directory in the system file browser when double-clicked.
     * @param event
     */
    @FXML
    void handleModelDirectoryLabelClick(MouseEvent event) {
        if (event.getClickCount() != 2) {
            return;
        }
        var modelDir = getModelDirectory().orElse(null);
        if (modelDir == null) {
            return;
        }
        if (Files.exists(modelDir)) {
            GuiTools.browseDirectory(modelDir.toFile());
        } else {
            logger.debug("Can't browse directory for {}", modelDir);
        }
    }

    @FXML
    void promptForModelDirectory() {
        promptToUpdateDirectory(InstanSegPreferences.modelDirectoryProperty());
    }


    private void configureChannelPicker() {
        updateChannelPicker(qupath.getImageData());
        qupath.imageDataProperty().addListener((v, o, n) -> updateChannelPicker(n));
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
        comboChannels.getCheckModel().clearChecks();
        comboChannels.getItems().clear();
        comboChannels.getItems().setAll(getAvailableChannels(imageData));
        if (imageData.isBrightfield()) {
            comboChannels.getCheckModel().checkIndices(IntStream.range(0, 3).toArray());
            var model = modelChoiceBox.getSelectionModel().selectedItemProperty().get();
            if (model != null && model.isDownloaded(Path.of(InstanSegPreferences.modelDirectoryProperty().get()))) {
                var modelChannels = model.getNumChannels();
                if (modelChannels.isPresent()) {
                    int nModelChannels = modelChannels.get();
                    if (nModelChannels != InstanSegModel.ANY_CHANNELS) {
                        comboChannels.getCheckModel().clearChecks();
                        comboChannels.getCheckModel().checkIndices(0, 1, 2);
                    }
                }

            }
        } else {
            comboChannels.getCheckModel().checkIndices(IntStream.range(0, imageData.getServer().nChannels()).toArray());
        }
    }

    private static String getCheckComboBoxText(CheckComboBox<ChannelSelectItem> comboBox) {
        int n = comboBox.getCheckModel().getCheckedItems().stream()
                .filter(Objects::nonNull)
                .toList()
                .size();
        if (n == 0)
            return resources.getString("ui.options.noChannelSelected");
        if (n == 1)
            return resources.getString("ui.options.oneChannelSelected");
        return String.format(resources.getString("ui.options.nChannelSelected"), n);
    }

    /**
     * Add an option to the ContextMenu of the CheckComboBox to select all
     * currently-visible channels.
     * <p>
     * Particularly useful for images with many channels - it's possible
     * to preview a subset of channels using the brightness and contrast
     * window, and to then transfer this selection to InstanSeg by simply
     * right-clicking and choosing "Set from visible".
     * @param comboChannels The CheckComboBox for selecting channels.
     */
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
                        .or(messageTextHelper.hasWarning())
                        .or(deviceChoices.getSelectionModel().selectedItemProperty().isNull())
                        .or(Bindings.createBooleanBinding(() -> {
                            var model = modelChoiceBox.getSelectionModel().selectedItemProperty().get();
                            if (model == null) {
                                return true;
                            }
                            var modelDir = getModelDirectory().orElse(null);
                            if (modelDir == null || !Files.exists(modelDir)) {
                                return true; // Can't download without somewhere to put it
                            }
                            if (!model.isDownloaded(modelDir)) {
                                return false; // to enable "download and run"
                            }
                            return false;
                        }, modelChoiceBox.getSelectionModel().selectedItemProperty(), needsUpdating))
        );
        pendingTask.addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                pool.execute(newValue);
            }
        });
    }

    private static Path tryToGetPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        } else {
            return Path.of(path);
        }
    }

    static Optional<Path> getModelDirectory() {
        return Optional.ofNullable(modelDirectoryProperty.get());
    }

    private void configureModelChoices() {
        tfModelDirectory.textProperty().bindBidirectional(InstanSegPreferences.modelDirectoryProperty());
        handleModelDirectory(tfModelDirectory.getText());
        addRemoteModels(modelChoiceBox.getItems());
        tfModelDirectory.textProperty().addListener((v, o, n) -> {
            var oldModelDir = tryToGetPath(o);
            if (oldModelDir != null && Files.exists(oldModelDir)) {
                watcher.unregister(oldModelDir);
            }
            handleModelDirectory(n);
//            addRemoteModels(modelChoiceBox.getItems());
        });
        modelChoiceBox.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            if (n == null) {
                return;
            }
            var modelDir = getModelDirectory().orElse(null);
            boolean isDownloaded = modelDir != null && n.isDownloaded(modelDir);
            if (!isDownloaded || qupath.getImageData() == null) {
                return;
            }
            var numChannels = n.getNumChannels();
            if (qupath.getImageData().isBrightfield() && numChannels.isPresent() && numChannels.get() != InstanSegModel.ANY_CHANNELS) {
                comboChannels.getCheckModel().clearChecks();
                comboChannels.getCheckModel().checkIndices(0, 1, 2);
            }
            // Handle output channels
            var nOutputs = n.getOutputChannels().orElse(1);
            checkComboOutputs.getItems().setAll(InstanSegOutput.getOutputsForChannelCount(nOutputs));
            checkComboOutputs.getCheckModel().checkAll();
        });
        downloadButton.setOnAction(e -> downloadModel());
        WebView webView = WebViews.create(true);
        PopOver infoPopover = new PopOver(webView);
        infoButton.setOnAction(e -> {
            parseMarkdown(modelChoiceBox.getSelectionModel().getSelectedItem(), webView, infoButton, infoPopover);
        });
    }

    private void downloadModel() {
        try (var pool = ForkJoinPool.commonPool()) {
            pool.execute(() -> {
                try {
                    var modelDir = getModelDirectory().orElse(null);
                    if (modelDir == null || !Files.exists(modelDir)) {
                        Dialogs.showErrorMessage(resources.getString("title"),
                                resources.getString("ui.model-directory.choose-prompt"));
                        return;
                    }
                    var model = modelChoiceBox.getSelectionModel().getSelectedItem();
                    Dialogs.showInfoNotification(resources.getString("title"),
                            String.format(resources.getString("ui.popup.fetching"), model.getName()));
                    model.download(modelDir);
                    Dialogs.showInfoNotification(resources.getString("title"),
                            String.format(resources.getString("ui.popup.available"), model.getName()));
                    needsUpdating.set(!needsUpdating.get());
                } catch (IOException ex) {
                    Dialogs.showErrorNotification(resources.getString("title"), resources.getString("error.downloading"));
                }
            });
        }
    }

    private static void parseMarkdown(InstanSegModel model, WebView webView, Button infoButton, PopOver infoPopover) {
        Optional<String> readme = model.getREADME();
        if (readme.isEmpty()) return;
        String body = readme.get();

        // Parse the initial markdown only, to extract any YAML front matter
        var parser = org.commonmark.parser.Parser.builder().build();
        var doc = parser.parse(body);

        // If the markdown doesn't start with a title, pre-pending the model title & description (if available)
        if (!body.startsWith("#")) {
            var sb = new StringBuilder();
            sb.append("## ").append(model.getName()).append("\n\n");
            sb.append("----\n\n");
            doc.prependChild(parser.parse(sb.toString()));
        }
        webView.getEngine().loadContent(HtmlRenderer.builder().build().render(doc));
        infoPopover.show(infoButton);
    }

    private static boolean promptToAllowOnlineModelCheck() {
        String always = resources.getString("ui.model-online-check.always");
        String never = resources.getString("ui.model-online-check.never");
        String prompt = resources.getString("ui.model-online-check.allow-once");
        var permit = Dialogs.builder()
                .title(resources.getString("title"))
                .contentText(resources.getString("ui.model-online-check.prompt"))
                .buttons(always, never, prompt)
                .showAndWait()
                .orElse(null);
        if (permit == null)
            return false;
        String text = permit.getText();
        if (always.equals(text)) {
            InstanSegPreferences.permitOnlineProperty().set(InstanSegPreferences.OnlinePermission.YES);
            return true;
        } else if (never.equals(text)) {
            InstanSegPreferences.permitOnlineProperty().set(InstanSegPreferences.OnlinePermission.NO);
            return false;
        } else if (prompt.equals(text)) {
            InstanSegPreferences.permitOnlineProperty().set(InstanSegPreferences.OnlinePermission.PROMPT);
            return true;
        } else {
            logger.warn("Unknown choice: {}", text);
            return false;
        }
    }

    private static void addRemoteModels(ObservableList<InstanSegModel> models) {
        var permit = InstanSegPreferences.permitOnlineProperty().get();
        if (permit == InstanSegPreferences.OnlinePermission.NO) {
            logger.debug("Not allowed to check for models online.");
            return;
        } else if (permit == InstanSegPreferences.OnlinePermission.PROMPT) {
            if (!promptToAllowOnlineModelCheck()) {
                logger.debug("User declined online model check.");
                return;
            }
        }
        var releases = getReleases();
        if (releases.isEmpty()) {
            logger.info("No releases found.");
            return;
        }
        var release = releases.getFirst();
        var assets = getAssets(release);
        assets.forEach(asset -> {
            models.add(
                    InstanSegModel.fromURL(
                            asset.name.replace(".zip", ""),
                            asset.browser_download_url)
            );
        });
    }


    private void configureTileSizes() {
        // The use of 32-bit signed ints for coordinates of the intermediate sparse matrix *might* be
        // an issue for very large tile sizes - but I haven't seen any evidence of this.
        // We definitely can't have very small tiles, because they must be greater than 2 x the padding.
        tileSizeChoiceBox.getItems().addAll(256, 512, 1024, 2048);
        tileSizeChoiceBox.getSelectionModel().select(Integer.valueOf(512));
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

    private void handleModelDirectory(String n) {
        var path = tryToGetPath(n);
        if (path == null)
            return;
        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                var localPath = path.resolve("local");
                if (!Files.exists(localPath)) {
                    Files.createDirectory(localPath);
                }
                watcher.register(localPath); // todo: unregister
                addModelsFromPath(localPath, modelChoiceBox);
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
        messageTextHelper = new MessageTextHelper(modelChoiceBox, deviceChoices, comboChannels, needsUpdating);
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

    private void configureDirectoryLabel() {
        isModelDirectoryValid.addListener((v, o, n) -> updateModelDirectoryLabel());
        updateModelDirectoryLabel();
    }

    private void updateModelDirectoryLabel() {
        if (isModelDirectoryValid.get()) {
            modelDirLabel.getStyleClass().setAll("standard-message");
            modelDirLabel.setText(resources.getString("ui.options.directory"));
        } else {
            modelDirLabel.getStyleClass().setAll("warning-message");
            modelDirLabel.setText(resources.getString("ui.options.directory-not-set"));
        }
    }

    static void addModelsFromPath(Path path, ComboBox<InstanSegModel> box) {
        if (path == null || !Files.exists(path) || !Files.isDirectory(path)) return;
        // See https://github.com/controlsfx/controlsfx/issues/1320
        box.setItems(FXCollections.observableArrayList());
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

    void restart() {
        Thread.ofVirtual().start(watcher::processEvents);
    }

    @FXML
    private void runInstanSeg() {
        if (!PytorchManager.hasPyTorchEngine()) {
            if (!Dialogs.showConfirmDialog(resources.getString("title"), resources.getString("ui.pytorch"))) {
                Dialogs.showWarningNotification(resources.getString("title"), resources.getString("ui.pytorch-popup"));
                return;
            }
        }
        ImageServer<?> server = qupath.getImageData().getServer();
        List<ChannelSelectItem> selectedChannels = comboChannels
                .getCheckModel().getCheckedItems()
                .stream()
                .filter(Objects::nonNull)
                .toList();


        var model = modelChoiceBox.getSelectionModel().getSelectedItem();
        var modelPath = getModelDirectory().orElse(null);
        if (modelPath == null) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("ui.model-directory.choose-prompt"));
            return;
        }
        if (!model.isDownloaded(modelPath)) {
            if (!Dialogs.showYesNoDialog(resources.getString("title"), resources.getString("ui.model-popup")))
                return;
            Dialogs.showInfoNotification(resources.getString("title"), String.format(resources.getString("ui.popup.fetching"), model.getName()));
            downloadModel();
            if (!model.isDownloaded(modelPath)) {
                Dialogs.showErrorNotification(resources.getString("title"), String.format(resources.getString("error.localModel")));
                return;
            }
        }

        int imageChannels = selectedChannels.size();
        var modelChannels = model.getNumChannels();
        if (modelChannels.isEmpty()) {
            Dialogs.showErrorNotification(resources.getString("title"), resources.getString("error.fetching"));
            return;
        }

        int nModelChannels = modelChannels.get();
        if (nModelChannels != InstanSegModel.ANY_CHANNELS && nModelChannels != imageChannels) {
            Dialogs.showErrorNotification(resources.getString("title"), String.format(
                    resources.getString("ui.error.num-channels-dont-match"),
                    nModelChannels, imageChannels));
            return;
        }

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
            var taskRunner = new TaskRunnerFX(
                    QuPathGUI.getInstance(),
                    InstanSegPreferences.numThreadsProperty().getValue());

            var imageData = qupath.getImageData();
            var selectedObjects = imageData.getHierarchy().getSelectionModel().getSelectedObjects();
            Optional<Path> path = model.getPath();
            if (path.isEmpty()) {
                Dialogs.showErrorNotification(resources.getString("title"), resources.getString("error.querying-local"));
                return null;
            }
            // TODO: HANDLE OUTPUT CHANNELS!
            int nOutputs = model.getOutputChannels().orElse(1);
            int[] outputChannels = new int[0];
            if (nOutputs <= 0) {
                logger.warn("Unknown output channels for {}", model);
                nOutputs = 1;
            }
            int nChecked = checkComboOutputs.getCheckModel().getCheckedIndices().size();
            if (nChecked > 0 && nChecked < nOutputs) {
                outputChannels = checkComboOutputs.getCheckModel().getCheckedIndices().stream().mapToInt(Integer::intValue).toArray();
            }

            var instanSeg = InstanSeg.builder()
                    .model(model)
                    .device(deviceChoices.getSelectionModel().getSelectedItem())
                    .inputChannels(channels.stream().map(ChannelSelectItem::getTransform).toList())
                    .outputChannels(outputChannels)
                    .tileDims(InstanSegPreferences.tileSizeProperty().get())
                    .taskRunner(taskRunner)
                    .makeMeasurements(makeMeasurementsCheckBox.isSelected())
                    .build();

            boolean makeMeasurements = makeMeasurementsCheckBox.isSelected();
            String cmd = String.format("""
                            qupath.ext.instanseg.core.InstanSeg.builder()
                                .modelPath("%s")
                                .device("%s")
                                .%s
                                .outputChannels(%s)
                                .tileDims(%d)
                                .nThreads(%d)
                                .makeMeasurements(%s)
                                .build()
                                .detectObjects()
                            """,
                            path.get(),
                            deviceChoices.getSelectionModel().getSelectedItem(),
                            ChannelSelectItem.toConstructorString(channels),
                            outputChannels.length == 0 ? "" : Arrays.stream(outputChannels)
                                    .mapToObj(Integer::toString)
                                    .collect(Collectors.joining(", ")),
                            InstanSegPreferences.tileSizeProperty().get(),
                            InstanSegPreferences.numThreadsProperty().getValue(),
                            makeMeasurements
                    ).strip();
            InstanSegResults results = instanSeg.detectObjects(imageData, selectedObjects);
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
            imageData.getHistoryWorkflow()
                    .addStep(
                            new DefaultScriptableWorkflowStep(resources.getString("workflow.title"), cmd)
                    );
            logger.info("Results: {}", results);
            int nFailed = results.nTilesFailed();
            if (nFailed > 0) {
                var errorMessage = String.format(resources.getString("error.tiles-failed"), nFailed);
                logger.error(errorMessage);
                Dialogs.showErrorMessage(resources.getString("title"), errorMessage);
            }
            return null;
        }
    }

    private void downloadPyTorch() {
        Platform.runLater(() -> Dialogs.showInfoNotification(resources.getString("title"), resources.getString("ui.pytorch-downloading")));
        PytorchManager.getEngineOnline();
        Platform.runLater(this::addDeviceChoices);
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


    private static class GitHubRelease {
        String tag_name;
        String name;
        Date published_at;
        GitHubAsset[] assets;
        String body;

        String getName() {
            return name;
        }
        String getBody() {
            return body;
        }
        Date getDate() {
            return published_at;
        }
        String getTag() {
            return tag_name;
        }

        @Override
        public String toString() {
            return name + " with assets:" + Arrays.toString(assets);
        }
    }

    private static class GitHubAsset {
        String name;
        String content_type;
        URL browser_download_url;
        @Override
        public String toString() {
            return name;
        }

        String getType() {
            return content_type;
        }

        URL getUrl() {
            return browser_download_url;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * Get the list of models from the latest GitHub release, downloading if
     * necessary.
     * @return A list of GitHub releases, possibly empty.
     */
    private static List<GitHubRelease> getReleases() {
        Path modelDir = getModelDirectory().orElse(null);
        Path cachedReleases = modelDir == null ? null : modelDir.resolve("releases.json");

        String uString = "https://api.github.com/repos/instanseg/InstanSeg/releases";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uString))
                .GET()
                .build();
        HttpResponse<String> response;
        String json;
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        // check GitHub api for releases
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // if response is okay, then cache it
            if (response.statusCode() == 200) {
                json = response.body();
                if (cachedReleases != null && Files.exists(cachedReleases.getParent())) {
                    JsonElement jsonElement = JsonParser.parseString(json);
                    Files.writeString(cachedReleases, gson.toJson(jsonElement));
                } else {
                    logger.debug("Unable to cache release information - no model directory specified");
                }
            } else {
                // otherwise problems
                throw new IOException("Unable to fetch GitHub release information, status " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            // if not, try to fall back on a cached version
            if (cachedReleases != null && Files.exists(cachedReleases)) {
                try {
                    json = Files.readString(cachedReleases);
                } catch (IOException ex) {
                    logger.warn("Unable to read cached release information");
                    return List.of();
                }
            } else {
                logger.info("Unable to fetch release information from GitHub and no cached version available.");
                return List.of();
            }
        }

        GitHubRelease[] releases = gson.fromJson(json, GitHubRelease[].class);
        if (!(releases.length > 0)) {
            logger.info("No releases found in JSON string");
            return List.of();
        }
        return List.of(releases);
    }

    private static List<GitHubAsset> getAssets(GitHubRelease release) {
        var assets = Arrays.stream(release.assets)
                .filter(a -> a.getType().equals("application/zip"))
                .toList();
        if (assets.isEmpty()) {
            logger.info("No valid assets identified for {}", release.name);
        } else if (assets.size() > 1) {
            logger.info("More than one matching model: {}", release.name);
        }
        return assets;
    }

    /**
     * Helper class to manage the display of an output channel.
     */
    private static class InstanSegOutput {

        private final int index;
        private final String name;

        private static final List<InstanSegOutput> SINGLE_CHANNEL = List.of(
                new InstanSegOutput(0, "Only channel")
        );

        // We have no way to query channel names currently - b
        // but the first InstanSeg models with two channels are always in this order
        private static final List<InstanSegOutput> TWO_CHANNEL = List.of(
                new InstanSegOutput(0, "Channel 1 (Nuclei)"),
                new InstanSegOutput(0, "Channel 2 (Cells)")
        );

        InstanSegOutput(int index, String name) {
            this.index = index;
            this.name = name;
        }

        private static List<InstanSegOutput> getOutputsForChannelCount(int nChannels) {
            return switch (nChannels) {
                case 0 -> List.of();
                case 1 -> SINGLE_CHANNEL;
                case 2 -> TWO_CHANNEL;
                default ->
                        IntStream.range(0, nChannels).mapToObj(i -> new InstanSegOutput(i, "Channel " + (i + 1))).toList();
            };
        }

        /**
         * Get the index of the output channel.
         * @return
         */
        public int getIndex() {
            return index;
        }

        @Override
        public String toString() {
            return name;
        }

    }


}
