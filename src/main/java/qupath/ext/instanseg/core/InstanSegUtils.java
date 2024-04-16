package qupath.ext.instanseg.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.experimental.pixels.MeasurementProcessor;
import qupath.lib.images.ImageData;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class InstanSegUtils {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegUtils.class);

    private InstanSegUtils() {
        throw new AssertionError("Do not instantiate this class");
    }

    static ImageOp getNormalization(ImageData<BufferedImage> imageData) {
        // if pyramidal, or the image is smallish, use thumbnail (for non-pyramidal this is just the image)
        if (imageData.getServer().nResolutions() > 1 || imageData.getServer().getWidth() < 2000) {
            try {
                var image = imageData.getServer().getDefaultThumbnail(0, 0);
                var raster = image.getRaster();
                int nBands = raster.getNumBands();
                int nDims = image.getWidth() * image.getHeight();
                double[] buffer = null;
                double[] pixels = image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), buffer);
                double eps = 1e-6;
                var percentiles = IntStream.range(0, nBands)
                        .mapToObj(i -> {
                            var chanPix = IntStream.range(0, nDims).mapToDouble(j -> pixels[i + (j * nBands)]).toArray();
                            var lo = MeasurementProcessor.Functions.percentile(1).apply(chanPix);
                            var hi = MeasurementProcessor.Functions.percentile(99).apply(chanPix);
                            double scale;
                            if (hi == lo && eps == 0.0) {
                                logger.warn("Normalization percentiles give the same value ({}), scale will be Infinity", lo);
                                scale = Double.POSITIVE_INFINITY;
                            } else
                                scale = 1.0/(hi - lo + eps);
                            double offset = -lo;
                            return new double[] {offset, scale};
                        });
                var percs = percentiles.toList();
                var e1 = percs.stream().mapToDouble(e -> e[1]).toArray();
                var e0 = percs.stream().mapToDouble(e -> e[0]).toArray();
                return ImageOps.Core.sequential(
                        ImageOps.Core.multiply(e1),
                        ImageOps.Core.add(e0)
                );
            } catch (IOException e) {
                logger.error("Error reading thumbnail", e);
            }
        }
        // default is percentile
        return ImageOps.Normalize.percentile(1, 99, true, 1e-6);
    }

    private static double getMax(DoubleStream values) {
        AtomicReference<Double> max = new AtomicReference<>(Double.MIN_VALUE);
        values.forEach(v -> {
            if (v > max.get()) {
                max.set(v);
            }
        });
        return max.get();
    }
}
