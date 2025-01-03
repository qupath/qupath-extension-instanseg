package qupath.ext.instanseg.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.BioimageIoSpec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.PixelCalibration;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Objects;

import static qupath.bioimageio.spec.BioimageIoSpec.getAxesString;

public class InstanSegModel {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegModel.class);
    private String version;
    private URL modelURL = null;

    /**
     * Constant to indicate that any number of channels are supported.
     */
    public static final int ANY_CHANNELS = -1;

    private Path path = null;
    private BioimageIoSpec.BioimageIoModel model = null;
    private final String name;

    private InstanSegModel(BioimageIoSpec.BioimageIoModel bioimageIoModel) {
        this.model = bioimageIoModel;
        this.path = Paths.get(model.getBaseURI());
        this.version = model.getVersion();
        this.name = model.getName();
    }

    private InstanSegModel(String name, String version, URL modelURL) {
        this.name = name;
        this.version = version;
        this.modelURL = modelURL;
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
     * Create an InstanSeg model from a remote URL.
     * @param name The model name
     * @param browserDownloadUrl The download URL from eg GitHub
     * @return A handle on the created model
     */
    public static InstanSegModel fromURL(String name, String version, URL browserDownloadUrl) {
        return new InstanSegModel(name, version, browserDownloadUrl);
    }

    /**
     * Check if the model has been downloaded already.
     * @return True if the model has a known path that exists and is valid, or if a suitable directory can be found in the localModelPath
     */
    public boolean isValid() {
        // Check path first - *sometimes* the model might be downloaded, but have a name
        // that doesn't match with the filename (although we'd prefer this didn't happen...)
        if (path != null && model != null && isValidModel(path))
            return true;
        // The model may have been deleted or renamed - we won't be able to load it
        return false;
    }

    /**
     * Trigger a download for a model
     * @throws IOException If an error occurs when downloading, unzipping, etc.
     */
    public void download(Path downloadedModelDir) throws IOException {
        if (path != null && isValidModel(path) && model != null) {
            return;
        }
        var zipFile = downloadZipIfNeeded(
                this.modelURL,
                downloadedModelDir,
                getFolderName(name, version));
        this.path = unzipIfNeeded(zipFile);
        this.model = BioimageIoSpec.parseModel(path.toFile());
        this.version = model.getVersion();
    }

    /**
     * Extract the README from a local file
     * @return The README as a String, if possible. If not present, or an error
     * occurs when reading, nothing.
     */
    public Optional<String> getREADME() {
        return getPath().map(this::getREADMEString);
    }

    /**
     * Get the pixel size in the X dimension.
     * @return the pixel size in the X dimension, or empty if the model isn't downloaded yet.
     */
    private Optional<Number> getPixelSizeX() {
        return getPixelSize().flatMap(p -> Optional.ofNullable(p.getOrDefault("x", null)));
    }

    /**
     * Get the pixel size in the Y dimension.
     * @return the pixel size in the Y dimension, or empty if the model isn't downloaded yet.
     */
    private Optional<Number> getPixelSizeY() {
        return getPixelSize().flatMap(p -> Optional.ofNullable(p.getOrDefault("y", null)));
    }

    /**
     * Get the preferred pixel size for running the model, in the absence of any other information.
     * This is the average of the X and Y pixel sizes if both are available.
     * Otherwise, it is the value of whichever value is available - or null if neither is found.
     * @return the pixel size, or empty if the model isn't downloaded yet.
     */
    public Optional<Number> getPreferredPixelSize() {
        var x = getPixelSizeX();
        var y = getPixelSizeY();
        if (x.isEmpty()) {
            return y;
        } else if (y.isEmpty()) {
            return x;
        } else {
            return Optional.of((x.get().doubleValue() + y.get().doubleValue()) / 2.0);
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
        Optional<Number> preferred = getPreferredPixelSize();
        double current = cal.getAveragedPixelSize().doubleValue();
        if (preferred.isEmpty()) {
            return current;
        }
        double requested = preferred.get().doubleValue();
        if (requested > 0 && current > 0) {
            return getPreferredDownsample(current, requested);
        } else {
            logger.warn("Invalid pixel size of {} for {}", requested, cal);
            return 1.0;
        }
    }

    /**
     * Get the preferred pixel size for running the model, incorporating information from the pixel calibration of the
     * image. This tries to encourage downsampling by an integer amount.
     * @param currentPixelSize The current pixel size (probably in microns)
     * @param requestedPixelSize The pixel size that the model expects (probably in microns)
     * @return The exact downsample, unless it's close to an integer, in which case the integer.
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
     * @return A path on disk.
     */
    public Optional<Path> getPath() {
        return Optional.ofNullable(path);
    }

    @Override
    public String toString() {
        String name = getName();
        String parent = getPath().map(Path::getParent).map(Path::getFileName).map(Path::toString).orElse(null);
        String version = getModel().map(BioimageIoSpec.BioimageIoModel::getVersion).orElse(this.version);
        if (parent != null && !parent.equals(name)) {
            name = parent + "/" + name;
        }
        if (version != null)
            name += "-" + version;
        return name;
    }

    /**
     * Check if a path is (likely) a valid InstanSeg model.
     * @param path The path to a folder.
     * @return True if the folder contains an instanseg.pt file and an accompanying rdf.yaml.
     * Does not currently validate the contents of either, but may in future check
     * the yaml contents and the checksum of the pt file.
     */
    public static boolean isValidModel(Path path) {
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("instanseg.pt")) && Files.exists(path.resolve("rdf.yaml"));
        }
        return false;
    }

    /**
     * Get the model name
     * @return A string
     */
    public String getName() {
        return name;
    }

    /**
     * Try to check the number of channels in the model.
     * @return The integer if the model is downloaded, otherwise empty
     */
    public Optional<Integer> getNumChannels() {
        return getModel().flatMap(model -> Optional.of(extractChannelNum(model)));
    }

    /**
     * Try to check the output tensors from the model spec.
     * @return The output tensors if the model is downloaded, otherwise empty.
     */
    public Optional<List<BioimageIoSpec.OutputTensor>> getOutputs() {
        return getModel().flatMap(model -> Optional.ofNullable(model.getOutputs()));
    }

    /**
     * Try to check the output classes from the model spec.
     * @return The output classes if the model is downloaded, and it's present, otherwise empty.
     */
    public List<String> getClasses() {
        var config = model.getConfig().getOrDefault("qupath", null);
        if (config instanceof Map configMap) {
            List<String> classes = new ArrayList<>();
            var el = configMap.get("classes");
            if (el != null && el instanceof List elList) {
                for (var t: elList) {
                    classes.add(t.toString());
                }
            }
            return classes;
        }
        return List.of();
    }

    public enum OutputType {
        // "instance segmentation" "cell embeddings" "cell classes" "cell probabilities" "semantic segmentation"
        INSTANCE_SEGMENTATION("instance_segmentation"),
        DETECTION_EMBEDDINGS("detection_embeddings"),
        DETECTION_LOGITS("detection_logits"),
        DETECTION_CLASSES("detection_classes"),
        SEMANTIC_SEGMENTATION("semantic_segmentation");

        private final String type;
        OutputType(String type) {
            this.type = type;
        }
        @Override
        public String toString() {
            return type;
        }
    }

    private static int extractChannelNum(BioimageIoSpec.BioimageIoModel model) {
        String axes = getAxesString(model.getInputs().getFirst().getAxes());
        int ind = axes.indexOf("c");
        var shape = model.getInputs().getFirst().getShape();
        if (shape.getShapeStep()[ind] == 1) {
            return ANY_CHANNELS;
        } else {
            return shape.getShapeMin()[ind];
        }
    }

    /**
     * Retrieve the BioImage model spec.
     * @return The BioImageIO model spec for this InstanSeg model.
     */
    private Optional<BioimageIoSpec.BioimageIoModel> getModel() {
        return Optional.ofNullable(model);
    }

    private static Path downloadZipIfNeeded(URL url, Path downloadDirectory, String filename) throws IOException {
        Files.createDirectories(downloadDirectory);
        var zipFile = downloadDirectory.resolve(filename + ".zip");
        if (!isDownloadedAlready(zipFile)) {
            try (InputStream stream = url.openStream()) {
                try (ReadableByteChannel readableByteChannel = Channels.newChannel(stream)) {
                    try (FileOutputStream fos = new FileOutputStream(zipFile.toFile())) {
                        fos.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                    }
                }
            }
        }
        return zipFile;
    }

    private static boolean isDownloadedAlready(Path zipFile) {
        if (!Files.exists(zipFile)) {
            return false;
        }
        try {
            BioimageIoSpec.parseModel(zipFile.toFile());
        } catch (IOException e) {
            logger.warn("Invalid zip file", e);
            return false;
        }
        return true;
    }

    private Path unzipIfNeeded(Path zipFile) throws IOException {
        var zipSpec = BioimageIoSpec.parseModel(zipFile);
        String version = zipSpec.getVersion();
        var outdir = zipFile.resolveSibling(getFolderName(zipSpec.getName(), version));
        if (!isUnpackedAlready(outdir)) {
            try {
                unzip(zipFile, outdir);
                // Files.delete(zipFile);
            } catch (IOException e) {
                logger.error("Error unzipping model", e);
                // clean up files just in case!
                Files.deleteIfExists(outdir);
            } finally {
                Files.deleteIfExists(zipFile);
            }
        }
        return outdir;
    }

    private String getFolderName(String name, String version) {
        if (version == null) {
            return name;
        }
        return name + "-" + version;
    }

    private static boolean isUnpackedAlready(Path outdir) {
        return Files.exists(outdir) && isValidModel(outdir);
    }

    private static void unzip(Path zipFile, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.createDirectories(destination);
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
        int read;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }

    private String getREADMEString(Path path) {
        var file = path.resolve(name + "_README.md");
        if (Files.exists(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("Unable to find README", e);
                return null;
            }
        } else {
            logger.debug("No README found for model {}", name);
            return null;
        }
    }

    private Optional<Map<String, Double>> getPixelSize() {
        return getModel().flatMap(model -> {
            var config = model.getConfig().getOrDefault("qupath", null);
            if (config instanceof Map configMap) {
                var axes = (List) configMap.get("axes");
                String x = String.valueOf(((Map) (axes.get(0))).get("step"));
                String y = String.valueOf(((Map) (axes.get(1))).get("step"));
                return Optional.of(Map.of(
                        "x", Double.valueOf(x),
                        "y", Double.valueOf(y)
                ));
            }
            return Optional.of(Map.of("x", 1.0, "y", 1.0));
        });
    }

    /**
     * Get the number of output channels provided by the model (typically 1 or 2)
     * @return a positive integer
     */
    public Optional<Integer> getOutputChannels() {
        return getModel().map(model -> {
            var output = model.getOutputs().getFirst();
            String axes = getAxesString(output.getAxes());
            int ind = axes.indexOf("c");
            var shape = output.getShape().getShape();
            if (shape != null && shape.length > ind)
                return shape[ind];
            return (int)Math.round(output.getShape().getOffset()[ind] * 2);
        });
    }

}
