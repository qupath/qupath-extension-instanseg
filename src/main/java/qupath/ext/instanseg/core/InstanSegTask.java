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
import qupath.lib.objects.utils.ObjectMerger;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.regions.RegionRequest;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static qupath.lib.gui.scripting.QPEx.createTaskRunner;
import static qupath.lib.scripting.QP.getCurrentImageData;

public class InstanSegTask extends Task<Void> {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegTask.class);
    private int tileSize, nThreads;
    private List<ColorTransforms.ColorTransform> channels;
    private int padding;
    private double downsample;
    private final Path modelPath;
    private String deviceName;
    private double boundaryThreshold;
    private double overlapTolerance;
    private boolean nucleusOnly;

    public InstanSegTask(Path modelPath) {
        this.modelPath = modelPath;
    }

    public InstanSegTask tileSize(int tileSize) {
        this.tileSize = tileSize;
        return this;
    }

    public InstanSegTask nThreads(int nThreads) {
        this.nThreads = nThreads;
        return this;
    }

    public InstanSegTask channels(List<ColorTransforms.ColorTransform> channels) {
        this.channels = channels;
        return this;
    }

    public InstanSegTask padding(int padding) {
        this.padding = padding;
        return this;
    }

    public InstanSegTask downsample(double downsample) {
        this.downsample = downsample;
        return this;
    }

    public InstanSegTask deviceName(String deviceName) {
        this.deviceName = deviceName;
        return this;
    }

    public InstanSegTask boundaryThreshold(double boundaryThreshold) {
        this.boundaryThreshold = boundaryThreshold;
        return this;
    }

    public InstanSegTask overlapTolerance(double overlapTolerance) {
        this.overlapTolerance = overlapTolerance;
        return this;
    }

    public InstanSegTask nucleusOnly(boolean nucleusOnly) {
        this.nucleusOnly = nucleusOnly;
        return this;
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

                    // double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
                    // var rr = RegionRequest.createInstance(getCurrentImageData().getServer());
                    // try (var mat = ImageOps.buildImageDataOp(channels).apply(getCurrentImageData(), rr)) {
                    //     for (var channel: OpenCVTools.splitChannels(mat)) {
                    //         double max1 = OpenCVTools.maximum(channel);
                    //         if (max1 > max) {
                    //             max = max1;
                    //         }
                    //     }
                    // }

                    var preprocessing = ImageOps.Core.sequential(
                            ImageOps.Core.ensureType(PixelType.FLOAT32),
//                             ImageOps.Core.divide(255.0)
                            ImageOps.Normalize.percentile(1, 99, true, 1e-6)
                            // ImageOps.Core.divide(max)
                    );
                    var predictionProcessor = new TilePredictionProcessor(predictors, baseManager,
                            layout, layoutOutput, preprocessing, inputWidth, inputHeight, padToInputSize);
                    var processor = OpenCVProcessor.builder(predictionProcessor)
                            .imageSupplier((parameters) -> ImageOps.buildImageDataOp(channels).apply(parameters.getImageData(), parameters.getRegionRequest()))
                            // .tiler(Tiler.builder(inputWidth-padding*2, inputHeight-padding*2)
                            .tiler(Tiler.builder((int)(downsample * inputWidth-padding*2), (int)(downsample * inputHeight-padding*2))
                                    .alignTopLeft()
                                    .cropTiles(false)
                                    .build()
                            )
                            .outputHandler(OutputHandler.createObjectOutputHandler(new OutputToObjectConverter(nucleusOnly)))
                            .padding(padding)
                            .merger(ObjectMerger.createSharedTileBoundaryMerger(boundaryThreshold, overlapTolerance))
                            // .mergeSharedBoundaries(0.25)
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
