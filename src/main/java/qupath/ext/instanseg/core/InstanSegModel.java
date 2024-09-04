package qupath.ext.instanseg.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.BioimageIoSpec;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.images.servers.PixelCalibration;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InstanSegModel {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegModel.class);

    /**
     * Constant to indicate that any number of channels are supported.
     */
    public static final int ANY_CHANNELS = -1;

    private Path path = null;
    private URL modelURL = null;
    private BioimageIoSpec.BioimageIoModel model = null;
    private final String name;

    private InstanSegModel(BioimageIoSpec.BioimageIoModel bioimageIoModel) {
        this.model = bioimageIoModel;
        this.path = Paths.get(model.getBaseURI());
        this.name = model.getName();
    }

    private InstanSegModel(URL modelURL, String name) {
        this.modelURL = modelURL;
        this.name = name;
    }

    /**
     * Create an InstanSeg model from an existing path.
     * @param path The path to the folder that contains the model .pt file and the config YAML file.
     * @return A handle on the model that can be used for inference.
     * @throws IOException If the directory can't be found or isn't a valid model directory.
     */
    public static InstanSegModel fromPath(Path path) throws IOException {
        return new InstanSegModel(BioimageIoSpec.parseModel(path));
    }

    /**
     * Request an InstanSeg model from the set of available models
     * @param name The model name
     * @return The specified model.
     */
    public static InstanSegModel fromName(String name) {
        // todo: instantiate built-in models somehow
        throw new UnsupportedOperationException("Fetching models by name is not yet implemented!");
    }

    /**
     * Get the pixel size in the X dimension.
     * @return the pixel size in the X dimension.
     */
    private Number getPixelSizeX() {
        return getPixelSize().getOrDefault("x", null);
    }

    /**
     * Get the pixel size in the Y dimension.
     * @return the pixel size in the Y dimension.
     */
    private Number getPixelSizeY() {
        return getPixelSize().getOrDefault("y", null);
    }

    /**
     * Get the preferred pixel size for running the model, in the absence of any other information.
     * This is the average of the X and Y pixel sizes if both are available.
     * Otherwise, it is the value of whichever value is available - or null if neither is found.
     * @return the pixel size
     */
    public Number getPreferredPixelSize() {
        var x = getPixelSizeX();
        var y = getPixelSizeY();
        if (x == null) {
            return y;
        } else if (y == null) {
            return x;
        } else {
            return (x.doubleValue() + y.doubleValue()) / 2.0;
        }
    }

    /**
     * Get the preferred downsample for running the model, incorporating information from the pixel calibration of the
     * image.
     * This is based on {@link #getPreferredPixelSize()} but is permitted to adjust values based on the pixel calibration
     * to avoid unnecessary rounding.
     * <p>
     * For example, if the computed value would request a downsample of 2.04, this method is permitted to return 2.0.
     * That is usually desirable to avoid introducing interpolation artifacts and unnecessary floating point vertices
     * in the output.
     * <p>
     * In general, it is recommended to use this method rather than #getPreferredPixelSize() to calculate a downsample
     * when the pixel calibration is available.
     *
     * @param cal The pixel calibration of the image
     * @return the preferred downsample to use
     */
    public double getPreferredDownsample(PixelCalibration cal) {
        Objects.requireNonNull(cal, "Pixel calibration must not be null");
        Number preferred = getPreferredPixelSize();
        double current = cal.getAveragedPixelSize().doubleValue();
        if (preferred == null) {
            return current;
        }
        double requested = preferred.doubleValue();
        if (requested > 0 && current > 0) {
            return getPreferredDownsample(current, requested);
        } else {
            logger.warn("Invalid pixel size of {} for pixel calibration {}", requested, cal);
            return 1.0;
        }
    }

    /**
     * Get the preferred pixel size for running the model, incorporating information from the pixel calibration of the
     * image. This tries to encourage downsampling by an integer amount.
     * @param currentPixelSize
     * @param requestedPixelSize
     * @return
     */
    static double getPreferredDownsample(double currentPixelSize, double requestedPixelSize) {
        double downsample = requestedPixelSize / currentPixelSize;
        double downsampleRounded = Math.round(downsample);
        if (GeneralTools.almostTheSame(downsample, Math.round(downsample), 0.01)) {
            return downsampleRounded;
        } else {
            return downsample;
        }
    }

    /**
     * Get the path where the model is stored on disk.
     * @return A path on disk, or an exception if it can't be found.
     */
    public Path getPath() {
        if (path == null) {
            fetchModel();
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
     * Get the model name
     * @return A string
     */
    String getName() {
        return name;
    }

    /**
     * Retrieve the BioImage model spec.
     * @return The BioImageIO model spec for this InstanSeg model.
     */
    private BioimageIoSpec.BioimageIoModel getModel() {
        if (model == null) {
            fetchModel();
        }
        return model;
    }

    private Map<String, Double> getPixelSize() {
        // todo: this code is horrendous
        var config = getModel().getConfig().getOrDefault("qupath", null);
        if (config instanceof Map configMap) {
            var axes = (List)configMap.get("axes");
            return Map.of(
                    "x", (Double) ((Map) (axes.get(0))).get("step"),
                    "y", (Double) ((Map) (axes.get(1))).get("step")
            );
        }
        return Map.of("x", 1.0, "y", 1.0);
    }

    /**
     * Get the number of input channels supported by the model.
     * @return a positive integer, or {@link #ANY_CHANNELS} if any number of channels is supported.
     */
    public int getInputChannels() {
        String axes = getModel().getInputs().getFirst().getAxes().toLowerCase();
        int ind = axes.indexOf("c");
        var shape = getModel().getInputs().getFirst().getShape();
        if (shape.getShapeStep()[ind] == 1) {
            return ANY_CHANNELS;
        } else {
            return shape.getShapeMin()[ind];
        }
    }

    /**
     * Get the number of output channels provided by the model (typically 1 or 2)
     * @return a positive integer
     */
    public int getOutputChannels() {
        var output = getModel().getOutputs().getFirst();
        String axes = output.getAxes().toLowerCase();
        int ind = axes.indexOf("c");
        var shape = output.getShape().getShape();
        if (shape != null && shape.length > ind)
            return shape[ind];
        return (int)Math.round(output.getShape().getOffset()[ind] * 2);
    }

    private void fetchModel() {
        if (modelURL == null) {
            throw new NullPointerException("Model URL should not be null for a local model!");
        }
        downloadAndUnzip(modelURL, getUserDir().resolve("instanseg"));
    }

    private static void downloadAndUnzip(URL url, Path localDirectory) {
        // todo: implement
        throw new UnsupportedOperationException("Downloading and unzipping models is not yet implemented!");
    }

    private static Path getUserDir() {
        Path userPath = UserDirectoryManager.getInstance().getUserPath();
        Path cachePath = Paths.get(System.getProperty("user.dir"), ".cache", "QuPath");
        return userPath == null || userPath.toString().isEmpty() ?  cachePath : userPath;
    }

}
