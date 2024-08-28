package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.training.util.ProgressBar;
import com.google.gson.internal.LinkedTreeMap;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.BioimageIoSpec;

import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.utils.ObjectMerger;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.plugins.TaskRunner;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InstanSegModel {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegModel.class);
    private Path localModelPath;
    private URL modelURL = null;
    private boolean downloaded = false;

    private Path path = null;
    private BioimageIoSpec.BioimageIoModel model = null;
    private final String name;
    private int nFailed = 0;

    private InstanSegModel(BioimageIoSpec.BioimageIoModel bioimageIoModel) {
        this.model = bioimageIoModel;
        this.path = Paths.get(model.getBaseURI());
        this.name = model.getName();
        this.downloaded = true;
    }

    private InstanSegModel(String name, URL modelURL, Path localModelPath) {
        this.modelURL = modelURL;
        this.name = name;
        this.localModelPath = localModelPath;
    }

    /**
     * Create an InstanSeg model from an existing path.
     * @param path The path to the folder that contains the model .pt file and the config YAML file.
     * @return A handle on the model that can be used for inference.
     * @throws IOException If the directory can't be found or isn't a valid model directory.
     */
    public static InstanSegModel fromPath(Path path) throws IOException {
        return new InstanSegModel(BioimageIoSpec.parseModel(path.toFile()));
    }

    public static InstanSegModel fromURL(String name, URL browserDownloadUrl, Path localModelPath) {
        // todo: this constructor should initialise a
        return new InstanSegModel(name, browserDownloadUrl, localModelPath);
    }

    /**
     * Get the pixel size in the X dimension.
     * @return the pixel size in the X dimension.
     */
    public Double getPixelSizeX() throws IOException {
        return getPixelSize().get("x");
    }

    /**
     * Get the pixel size in the Y dimension.
     * @return the pixel size in the Y dimension.
     */
    public Double getPixelSizeY() throws IOException {
        return getPixelSize().get("y");
    }

    /**
     * Get the path where the model is stored on disk.
     * @return A path on disk, or an exception if it can't be found.
     */
    public Path getPath() throws IOException {
        if (path == null) {
            download();
        }
        return path;
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Check if a path is (likely) a valid InstanSeg model.
     * @param path The path to a folder.
     * @return True if the folder contains an instanseg.pt file and an accompanying rdf.yaml.
     * Does not currently validate the contents of either, but may in future check
     * the yaml contents and the checksum of the pt file.
     */
    public static boolean isValidModel(Path path) {
        // return path.toString().endsWith(".pt"); // if just looking at pt files
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("instanseg.pt")) && Files.exists(path.resolve("rdf.yaml"));
        }
        return false;
    }

    /**
     * The number of tiles that failed during processing.
     * @return The count of the number of failed tiles.
     */
    public int nFailed() {
        return nFailed;
    }

    /**
     * Get the model name
     * @return A string
     */
    public String getName() {
        return name;
    }

    /**
     * Retrieve the BioImage model spec.
     * @return The BioImageIO model spec for this InstanSeg model.
     */
    BioimageIoSpec.BioimageIoModel getModel() throws IOException {
        if (model == null) {
            download();
        }
        return model;
    }

    public void download() throws IOException {
        if (downloaded) return;
        var zipFile = downloadZip(
            this.modelURL,
            localModelPath,
            name);
        this.path = unzip(zipFile);
        this.model = BioimageIoSpec.parseModel(path.toFile());
        this.downloaded = true;
    }

    private static Path downloadZip(URL url, Path localDirectory, String filename) {
        // "https://github.com/instanseg/instanseg/releases/download/instanseg_models_v1/fluorescence_nuclei_and_cells.zip"
        var zipFile = localDirectory.resolve(Path.of(filename + ".zip"));
        if (!Files.exists(zipFile)) {
            try (InputStream stream = url.openStream()) {
                try (ReadableByteChannel readableByteChannel = Channels.newChannel(stream)) {
                    try (FileOutputStream fos = new FileOutputStream(zipFile.toFile())) {
                        fos.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return zipFile;
    }

    private static Path unzip(Path zipFile) {
        var outdir = zipFile.resolveSibling(zipFile.getFileName().toString().replace(".zip", ""));
        if (!Files.exists(outdir)) {
            try {
                unzip(zipFile, zipFile.getParent());
                // Files.delete(zipFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return outdir;
    }

    private static void unzip(Path zipFile, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.createDirectory(destination);
        }
        ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile.toFile())));
        ZipEntry entry = zipIn.getNextEntry();
        while (entry != null) {
            Path filePath = destination.resolve(entry.getName());
            if (entry.isDirectory()) {
                Files.createDirectory(filePath);
            } else {
                extractFile(zipIn, filePath);
            }
            zipIn.closeEntry();
            entry = zipIn.getNextEntry();
        }
        zipIn.close();
    }

    private static void extractFile(ZipInputStream zipIn, Path filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath.toFile()));
        byte[] bytesIn = new byte[4096];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }


    private Map<String, Double> getPixelSize() throws IOException {
        var map = new HashMap<String, Double>();
        var config = (LinkedTreeMap)getModel().getConfig().get("qupath");
        var axes = (ArrayList)config.get("axes");
        map.put("x", (Double) ((LinkedTreeMap)(axes.get(0))).get("step"));
        map.put("y", (Double) ((LinkedTreeMap)(axes.get(1))).get("step"));
        return map;
    }

    public int getNumChannels() throws IOException {
        assert getModel().getInputs().getFirst().getAxes().equals("bcyx");
        int numChannels = getModel().getInputs().getFirst().getShape().getShapeMin()[1];
        if (getModel().getInputs().getFirst().getShape().getShapeStep()[1] == 1) {
            numChannels = Integer.MAX_VALUE;
        }
        return numChannels;
    }

    void runInstanSeg(
            ImageData<BufferedImage> imageData,
            Collection<PathObject> pathObjects,
            Collection<ColorTransforms.ColorTransform> channels,
            int tileDims,
            double downsample,
            int padding,
            int boundary,
            Device device,
            boolean nucleiOnly,
            List<Class<? extends PathObject>> outputClasses,
            TaskRunner taskRunner) {

        nFailed = 0;
        Path modelPath;
        try {
            modelPath = getPath().resolve("instanseg.pt");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int nPredictors = 1; // todo: change me?


        // Optionally pad images to the required size
        boolean padToInputSize = true;
        String layout = "CHW";

        // TODO: Remove C if not needed (added for instanseg_v0_2_0.pt) - still relevant?
        String layoutOutput = "CHW";


        try (var model = Criteria.builder()
                .setTypes(Mat.class, Mat.class)
                .optModelUrls(String.valueOf(modelPath.toUri()))
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

                int sizeWithoutPadding = (int) Math.ceil(downsample * (tileDims - (double) padding));
                var predictionProcessor = new TilePredictionProcessor(predictors, baseManager,
                        layout, layoutOutput, channels, tileDims, tileDims, padToInputSize);
                var processor = OpenCVProcessor.builder(predictionProcessor)
                        .imageSupplier((parameters) -> ImageOps.buildImageDataOp(channels).apply(parameters.getImageData(), parameters.getRegionRequest()))
                        .tiler(Tiler.builder(sizeWithoutPadding)
                                .alignCenter()
                                .cropTiles(false)
                                .build()
                        )
                        .outputHandler(new PruneObjectOutputHandler<>(new InstansegOutputToObjectConverter(outputClasses), boundary))
                        .padding(padding)
                        .merger(ObjectMerger.createIoUMerger(0.2))
                        .downsample(downsample)
                        .build();
                processor.processObjects(taskRunner, imageData, pathObjects);
                nFailed = predictionProcessor.nFailed();
            } finally {
                for (var predictor: predictors) {
                    predictor.close();
                }
            }
            printResourceCount("Resource count after prediction", (BaseNDManager)baseManager.getParentManager());
        } catch (Exception e) {
            logger.error("Error running InstanSeg", e);
        }
    }

    private static void printResourceCount(String title, BaseNDManager manager) {
        logger.info(title);
        manager.debugDump(2);
    }

    public boolean isDownloaded() {
        return downloaded;
    }

    public String getREADME() throws IOException {
        var file = path.resolve(name + "_README.md");
        return Files.readString(file, StandardCharsets.UTF_8);

    }
}
