package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.training.util.ProgressBar;
import com.google.gson.internal.LinkedTreeMap;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.BioimageIoSpec;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.experimental.pixels.MeasurementProcessor;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.utils.ObjectMerger;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class InstanSegModel {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegModel.class);

    private Path path = null;
    private URL modelURL = null;
    private BioimageIoSpec.BioimageIoModel model = null;
    private final String name;

    private InstanSegModel(BioimageIoSpec.BioimageIoModel bioimageIoModel) {
        this.model = bioimageIoModel;
        this.path = Paths.get(model.getBaseURI());
        this.name = model.getName();
    }

    public InstanSegModel(URL modelURL, String name) {
        this.modelURL = modelURL;
        this.name = name;
    }

    public static InstanSegModel createModel(Path path) throws IOException {
        return new InstanSegModel(BioimageIoSpec.parseModel(path.toFile()));
    }

    public BioimageIoSpec.BioimageIoModel getModel() {
        if (model == null) {
            try {
                fetchModel();
            } catch (IOException e) {
                // todo: exception handling here, or...?
                throw new RuntimeException(e);
            }
        }
        return model;
    }

    public Double getPixelSizeX() {
        return getPixelSize().get("x");
    }

    public Double getPixelSizeY() {
        return getPixelSize().get("y");
    }

    private Map<String, Double> getPixelSize() {
        // todo: this code is horrendous
        var map = new HashMap<String, Double>();
        var config = (LinkedTreeMap)getModel().getConfig().get("qupath");
        var axes = (ArrayList)config.get("axes");
        map.put("x", (Double) ((LinkedTreeMap)(axes.get(0))).get("step"));
        map.put("y", (Double) ((LinkedTreeMap)(axes.get(1))).get("step"));
        return map;
    }

    private void fetchModel() throws IOException {
        if (modelURL == null) {
            throw new NullPointerException("Model URL should not be null for a local model!");
        }
        downloadAndUnzip(modelURL, getUserDir().resolve("instanseg"));
    }

    private static void downloadAndUnzip(URL url, Path localDirectory) throws IOException {
        // todo: implement
    }


    private static Path getUserDir() {
        Path userPath = UserDirectoryManager.getInstance().getUserPath();
        Path cachePath = Paths.get(System.getProperty("user.dir"), ".cache", "QuPath");
        return userPath == null || userPath.toString().isEmpty() ?  cachePath : userPath;
    }

    public String getName() {
        return name;
    }

    public Path getPath() {
        if (path == null) {
            try {
                fetchModel();
            } catch (IOException e) {
                // todo: handle here, or...?
                throw new RuntimeException(e);
            }
        }
        return path;
    }

    @Override
    public String toString() {
        return getName();
    }

    public void runInstanSeg(
            Collection<PathObject> pathObjects,
            ImageData<BufferedImage> imageData,
            List<ColorTransforms.ColorTransform> channels,
            int tileSize,
            double downsample,
            String deviceName,
            boolean nucleiOnly,
            TaskRunner taskRunner) throws ModelNotFoundException, MalformedModelException, IOException, InterruptedException {

        Path modelPath = getPath().resolve("instanseg.pt");
        int nPredictors = 1; // todo: change me?

        int padding = 40; // todo: setting? or just based on tile size. Should discuss.
        int boundary = 20;
        if (tileSize == 128) {
            padding = 25;
            boundary = 15;
        }
        // Optionally pad images to the required size
        boolean padToInputSize = true;
        String layout = "CHW";

        // TODO: Remove C if not needed (added for instanseg_v0_2_0.pt) - still relevant?
        String layoutOutput = "CHW";

        var device = Device.fromName(deviceName);

        try (var model = Criteria.builder()
                .setTypes(Mat.class, Mat.class)
                .optModelUrls(String.valueOf(modelPath))
                .optProgress(new ProgressBar())
                .optDevice(device) // Remove this line if devices are problematic!
                .optTranslator(new MatTranslator(layout, layoutOutput, nucleiOnly))
                .build()
                .loadModel()) {

            BaseNDManager baseManager = (BaseNDManager)model.getNDManager();
            printResourceCount("Resource count before prediction",
                    (BaseNDManager)baseManager.getParentManager());
            baseManager.debugDump(2);
            BlockingQueue<Predictor<Mat, Mat>> predictors = new ArrayBlockingQueue<>(nPredictors);

            try {
                for (int i = 0; i < nPredictors; i++) {
                    predictors.put(model.newPredictor());
                }

                printResourceCount("Resource count after creating predictors",
                        (BaseNDManager)baseManager.getParentManager());
                for (var pathObject: pathObjects) {
                    pathObject.setLocked(true);
                    ImageOp norm = ImageOps.Normalize.percentile(1, 99);

                    if (imageData.isFluorescence()) {
                        norm = getNormalization(imageData, pathObject, channels, 0.1, 99.9);
                    }
                    ImageOp preprocessing = ImageOps.Core.sequential(
                            ImageOps.Core.ensureType(PixelType.FLOAT32),
                            norm,
                            ImageOps.Core.clip(-0.5, 1.5)
                    );

                    int sizeWithoutPadding = (int) Math.ceil(downsample * (tileSize - (double) padding));
                    var predictionProcessor = new TilePredictionProcessor(predictors, baseManager,
                            layout, layoutOutput, preprocessing, tileSize, tileSize, padToInputSize);
                    var processor = OpenCVProcessor.builder(predictionProcessor)
                            .imageSupplier((parameters) -> ImageOps.buildImageDataOp(channels).apply(parameters.getImageData(), parameters.getRegionRequest()))
                            .tiler(Tiler.builder(sizeWithoutPadding)
                                    .alignCenter()
                                    .cropTiles(false)
                                    .build()
                            )
                            .outputHandler(new OutputToObjectConverter.PruneObjectOutputHandler<>(new OutputToObjectConverter(), boundary))
                            .padding(padding)
                            .merger(ObjectMerger.createIoUMerger(0.2))
                            .downsample(downsample)
                            .build();
                    processor.processObjects(taskRunner, imageData, Collections.singleton(pathObject));
                }
            } finally {
                for (var predictor: predictors) {
                    predictor.close();
                }
            }
            printResourceCount("Resource count after prediction", (BaseNDManager)baseManager.getParentManager());
        }
    }

    private static void printResourceCount(String title, BaseNDManager manager) {
        logger.info(title);
        manager.debugDump(2);
    }

    public static boolean isValidModel(Path path) {
        // return path.toString().endsWith(".pt"); // if just looking at pt files
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("instanseg.pt")) && Files.exists(path.resolve("rdf.yaml"));
        }
        return false;
    }

    /**
     * Try to fetch percentile normalisation factors from the image, using a
     * large downsample if the input pathObject is large. Uses the
     * bounding box of the pathObject so hopefully allows comparable output
     * to the same image through InstanSeg in Python as a full image.
     *
     * @param imageData  ImageData for the current image.
     * @param pathObject The object that we'll be doing segmentation in.
     * @param channels The channels/color transforms that the segmentation
     *                 will be restricted to.
     * @param lowPerc The lower percentile to use in normalisation.
     * @param highPerc The upper percentile to use in normalisation.
     * @return Percentile-based normalisation based on the bounding box,
     * or default tile-based percentile normalisation if that fails.
     */
    private static ImageOp getNormalization(ImageData<BufferedImage> imageData, PathObject pathObject, List<ColorTransforms.ColorTransform> channels, double lowPerc, double highPerc) {
        var defaults = ImageOps.Normalize.percentile(lowPerc, highPerc, true, 1e-6);
        try {
            // read the bounding box of the current object
            var roi = pathObject.getROI();

            BufferedImage image;
            double downsample = Math.max(1,  Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) / 1024);
            var request = RegionRequest.createInstance(imageData.getServerPath(), downsample, roi);
            image = imageData.getServer().readRegion(request);
            double eps = 1e-6;

            var params = channels.stream().map(colorTransform -> {
                var mask = BufferedImageTools.createROIMask(image.getWidth(), image.getHeight(), roi, request);
                float[] maskPix = ColorTransforms.createChannelExtractor(0).extractChannel(null, mask, null);
                float[] fpix = colorTransform.extractChannel(imageData.getServer(), image, null);
                assert maskPix.length == fpix.length;

                int ind = 0;
                for (int i = 0; i< maskPix.length; i++) {
                    if (maskPix[i] == 255) {
                        fpix[ind] = fpix[i];
                        ind++;
                    }
                }
                double[] usePixels = new double[ind];
                for (int i = 0; i < ind; i++) {
                    usePixels[i] = fpix[i];
                }

                double offset;
                double scale;
                var lo = MeasurementProcessor.Functions.percentile(lowPerc).apply(usePixels);
                var hi = MeasurementProcessor.Functions.percentile(highPerc).apply(usePixels);
                scale = 1.0 / (hi - lo + eps);
                offset = -lo * scale;
                return new double[]{offset, scale};
            }).toList();

            return ImageOps.Core.sequential(
                    ImageOps.Core.multiply(params.stream().mapToDouble(e -> e[1]).toArray()),
                    ImageOps.Core.add(params.stream().mapToDouble(e -> e[0]).toArray())
            );

        } catch (Exception e) {
            logger.error("Error reading thumbnail", e);
        }
        return defaults;
    }


}
