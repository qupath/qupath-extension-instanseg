package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.training.util.ProgressBar;
import javafx.concurrent.Task;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageOps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static qupath.lib.gui.scripting.QPEx.createTaskRunner;

public class InstanSegTask extends Task<Void> {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegTask.class);
    private final int tileSize, nThreads;
    private final List<ColorTransforms.ColorTransform> channels;
    private double downsample;
    private final Path modelPath;
    private final String deviceName;

    public InstanSegTask(Path modelPath,
                         List<ColorTransforms.ColorTransform> channels,
                         int tileSize,
                         int nThreads,
                         double downsample,
                         String deviceName) {
        this.modelPath = modelPath;
        this.channels = channels;
        this.tileSize = tileSize;
        this.nThreads = nThreads;
        this.downsample = downsample;
        this.deviceName = deviceName;
    }

    private static void printResourceCount(String title, BaseNDManager manager) {
        logger.info(title);
        manager.debugDump(2);
    }

    @Override
    protected Void call() throws Exception {
            logger.info("Using $nThreads threads");
            int nPredictors = 1;

            // TODO: Set path!
            var imageData = QP.getCurrentImageData();

            // todo: based on pixel size
            // double downsample = 0.5 / imageData.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();

            int inputWidth = tileSize;
            // int inputWidth = 256;
            int inputHeight = inputWidth;
            int padding = 16;
            // Optionally pad images to the required size
            boolean padToInputSize = true;
            String layout = "CHW";

            // TODO: Remove C if not needed (added for instanseg_v0_2_0.pt)
            String layoutOutput = "CHW";

            var device = Device.fromName(deviceName);

            try (var model = Criteria.builder()
                    .setTypes(Mat.class, Mat.class)
                    .optModelUrls(String.valueOf(modelPath))
                    .optProgress(new ProgressBar())
                    .optDevice(device) // Remove this line if devices are problematic!
                    .optTranslator(new MatTranslator(layout, layoutOutput))
                    .build()
                    .loadModel()) {

                BaseNDManager baseManager = (BaseNDManager)model.getNDManager();

                printResourceCount("Resource count before prediction", (BaseNDManager)baseManager.getParentManager());
                baseManager.debugDump(2);

                BlockingQueue<Predictor<Mat, Mat>> predictors = new ArrayBlockingQueue<>(nPredictors);

                try {
                    for (int i = 0; i < nPredictors; i++)
                        predictors.put(model.newPredictor());

                    printResourceCount("Resource count after creating predictors", (BaseNDManager)baseManager.getParentManager());

                    var preprocessing = ImageOps.Core.sequential(
                            ImageOps.Core.ensureType(PixelType.FLOAT32),
                            ImageOps.Normalize.percentile(1, 99, true, 1e-6)
                    );
                    var predictionProcessor = new TilePredictionProcessor(predictors, baseManager,
                            layout, layoutOutput, preprocessing, inputWidth, inputHeight, padToInputSize);
                    var processor = OpenCVProcessor.builder(predictionProcessor)
                            .imageSupplier((parameters) -> ImageOps.buildImageDataOp(channels).apply(parameters.getImageData(), parameters.getRegionRequest()))
                            .tiler(Tiler.builder((int)(downsample * inputWidth-padding*2), (int)(downsample * inputHeight-padding*2))
                                    .alignTopLeft()
                                    .cropTiles(false)
                                    .build()
                            )
                            .outputHandler(OutputHandler.createObjectOutputHandler(new OutputToObjectConvert()))
                            .padding(padding)
                            .mergeSharedBoundaries(0.25)
                            .downsample(downsample)
                            .build();
                    var runner = createTaskRunner(nThreads);
                    processor.processObjects(runner, imageData, QP.getSelectedObjects());
                } finally {
                    for (var predictor: predictors) {
                        predictor.close();
                    }
                }
                printResourceCount("Resource count after prediction", (BaseNDManager)baseManager.getParentManager());
            } catch (ModelNotFoundException | MalformedModelException |
                     IOException | InterruptedException ex) {
                Dialogs.showErrorMessage("Unable to run InstanSeg", ex);
                logger.error("Unable to run InstanSeg", ex);
            }
            logger.info("Updating hierarchy");
            QP.fireHierarchyUpdate();
            return null;
    }
}
