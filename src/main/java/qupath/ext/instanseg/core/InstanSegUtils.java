package qupath.ext.instanseg.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.experimental.pixels.MeasurementProcessor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class InstanSegUtils {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegUtils.class);

    private InstanSegUtils() {
        throw new AssertionError("Do not instantiate this class");
    }

    /**
     * Try to fetch percentile normalisation factors from the image, using a
     * large downsample if the input pathObject is large. Uses the
     * bounding box of the pathObject so hopefully allows comparable output
     * to the same image through InstanSeg in Python as a full image.
     *
     * @param imageData  ImageData for the current image.
     * @param pathObject The object that we'll be doing segmentation in.
     * @param channels   The channels/color transforms that the segmentation
     *                   will be restricted to.
     * @param lowPerc
     * @param hiPerc
     * @return Percentile-based normalisation based on the bounding box,
     * or default tile-based percentile normalisation if that fails.
     */
     static ImageOp getNormalization(ImageData<BufferedImage> imageData, PathObject pathObject, List<ColorTransforms.ColorTransform> channels, double lowPerc, double hiPerc) {
        var defaults = ImageOps.Normalize.percentile(1, 99, true, 1e-6);
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
                var hi = MeasurementProcessor.Functions.percentile(hiPerc).apply(usePixels);
                scale = 1.0 / (hi - lo + eps);
                offset = -lo * scale;
                return new double[]{offset, scale};
            }).toList();

            return ImageOps.Core.sequential(
                    ImageOps.Core.multiply(params.stream().mapToDouble(e -> e[1]).toArray()),
                    ImageOps.Core.add(params.stream().mapToDouble(e -> e[0]).toArray())
            );

        } catch (IOException e) {
            logger.error("Error reading image", e);
        }
        return defaults;
    }

    public static boolean isValidModel(Path path) {
        // return path.toString().endsWith(".pt"); // if just looking at pt files
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("instanseg.pt")) && Files.exists(path.resolve("rdf.yaml"));
        }
        return false;
    }

}
