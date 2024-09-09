package qupath.ext.instanseg.ui;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSeg;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.ext.instanseg.core.InstanSegResults;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.TaskRunnerFX;
import qupath.lib.images.ImageData;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

class InstanSegTask extends Task<Void> {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegTask.class);

    private static final ResourceBundle resources = InstanSegResources.getResources();

    private final ImageData<BufferedImage> imageData;
    private final List<InputChannelItem> channels;
    private final List<Integer> outputChannels;
    private final InstanSegModel model;
    private final String device;
    private final boolean makeMeasurements;
    private final boolean randomColors;

    InstanSegTask(ImageData<BufferedImage> imageData, InstanSegModel model, List<InputChannelItem> channels,
                  List<Integer> outputChannels, String device, boolean makeMeasurements, boolean randomColors) {
        this.imageData = imageData;
        this.model = model;
        this.channels = List.copyOf(channels);
        this.outputChannels = List.copyOf(outputChannels);
        this.device = device;
        this.makeMeasurements = makeMeasurements;
        this.randomColors = randomColors;
    }


    @Override
    protected Void call() {
        var taskRunner = new TaskRunnerFX(
                QuPathGUI.getInstance(),
                InstanSegPreferences.numThreadsProperty().getValue());

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
        int nChecked = this.outputChannels.size();
        if (nChecked > 0 && nChecked < nOutputs) {
            outputChannels = this.outputChannels.stream().mapToInt(Integer::intValue).toArray();
        }

        var instanSeg = InstanSeg.builder()
                .model(model)
                .device(device)
                .inputChannels(channels.stream().map(InputChannelItem::getTransform).toList())
                .outputChannels(outputChannels)
                .tileDims(InstanSegPreferences.tileSizeProperty().get())
                .taskRunner(taskRunner)
                .makeMeasurements(makeMeasurements)
                .randomColors(randomColors)
                .build();

        String cmd = String.format("""
                            qupath.ext.instanseg.core.InstanSeg.builder()
                                .modelPath("%s")
                                .device("%s")
                                .%s
                                .outputChannels(%s)
                                .tileDims(%d)
                                .nThreads(%d)
                                .makeMeasurements(%s)
                                .randomColors(%s)
                                .build()
                                .detectObjects()
                            """,
                path.get(),
                device,
                InputChannelItem.toConstructorString(channels),
                outputChannels.length == 0 ? "" : Arrays.stream(outputChannels)
                        .mapToObj(Integer::toString)
                        .collect(Collectors.joining(", ")),
                InstanSegPreferences.tileSizeProperty().get(),
                InstanSegPreferences.numThreadsProperty().getValue(),
                makeMeasurements,
                randomColors
        ).strip();
        InstanSegResults results = instanSeg.detectObjects(imageData, selectedObjects);
        imageData.getHierarchy().fireHierarchyChangedEvent(this);
        imageData.getHistoryWorkflow()
                .addStep(
                        new DefaultScriptableWorkflowStep(resources.getString("workflow.title"), cmd)
                );
        logger.info("Results: {}", results);
        int nFailed = results.nTilesFailed();
        if (nFailed > 0 && !results.wasInterrupted()) {
            var errorMessage = String.format(resources.getString("error.tiles-failed"), nFailed);
            logger.error(errorMessage);
            Dialogs.showErrorMessage(resources.getString("title"), errorMessage);
        }
        return null;
    }

}
