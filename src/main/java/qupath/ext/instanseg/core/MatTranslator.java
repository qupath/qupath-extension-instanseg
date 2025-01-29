package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import org.bytedeco.opencv.opencv_core.Mat;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.djl.DjlTools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


class MatTranslator implements Translator<Mat, Mat[]> {
    private static final Logger logger = LoggerFactory.getLogger(MatTranslator.class);

    private final String inputLayoutNd;
    private final String outputLayoutNd;
    private final int[] outputChannels;
    private final Map<String, Object> optionalArgs = new HashMap<>();

    /**
     * Create a translator from InstanSeg input to output.
     * @param inputLayoutNd N-dimensional output specification
     * @param outputLayoutNd N-dimensional output specification
     * @param outputChannels Array of channels to output; if null or empty, output all channels.
     *                       Values should be true for channels to output, false for channels to ignore.
     */
    MatTranslator(String inputLayoutNd, String outputLayoutNd, boolean[] outputChannels, Map<String, ?> optionalArgs) {
        this.inputLayoutNd = inputLayoutNd;
        this.outputLayoutNd = outputLayoutNd;
        this.outputChannels = convertBooleanArray(outputChannels);
        this.optionalArgs.putAll(optionalArgs);
    }

    private static int[] convertBooleanArray(boolean[] array) {
        if (array == null || array.length == 0) {
            return null;
        }
        int[] out = new int[array.length];
        for (int i = 0; i < array.length; i++) {
            out[i] = array[i] ? 1 : 0;
        }
        return out;
    }

    /**
     * Convert Mat to NDArray and add to an NDList.
     * Note that not all OpenCV types are supported.
     * Specifically, 16-bit types should be avoided.
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Mat input) {
        var manager = ctx.getNDManager();
        var ndarray = DjlTools.matToNDArray(manager, input, inputLayoutNd);
        var out = new NDList(ndarray);
        if (outputChannels != null) {
            var array = manager.create(outputChannels);
            var arrayCPU = array.toDevice(Device.cpu(), false);
            out.add(arrayCPU);
        }
        List<NDArray> args = sanitizeOptionalArgs(optionalArgs, manager);
        out.addAll(args);
        return out;
    }

    private static List<NDArray> sanitizeOptionalArgs(Map<String, ?> optionalArgs, NDManager manager) {
        List<NDArray> arrays = new ArrayList<>();
        for (var es : optionalArgs.entrySet()) {
            var val = es.getValue();
            NDArray array = null;
            switch (val) {
                case NDArray ndarray -> array = ndarray;
                case String s -> array = manager.create(s);
                case Boolean bool -> array = manager.create(bool);
                case Byte b -> array = manager.create(b);
                case Integer integer -> array = manager.create(integer);
                case Long l -> array = manager.create(l);
                case Number num ->
                    // Default to float for all other numbers
                    // (not double, which would fail with MPS)
                        array = manager.create(num.floatValue());
                case boolean[] arr -> array = manager.create(arr);
                case byte[] arr -> array = manager.create(arr);
                case int[] arr -> array = manager.create(arr);
                case long[] arr -> array = manager.create(arr);
                case float[] arr -> array = manager.create(arr);
                case null, default ->
                        logger.warn("Unsupported optional argument: name={}, type={}",
                                es.getKey(), val == null ? "null" : val.getClass());
            }
            if (array != null) {
                array.setName("args." + es.getKey());
                arrays.add(array);
            }
        }
        return arrays;
    }

    @Override
    public Mat[] processOutput(TranslatorContext ctx, NDList list) {
        var array = list.getFirst();
        var labels = DjlTools.ndArrayToMat(array, outputLayoutNd);
        var output = new Mat[list.size()];
        output[0] = labels;
        for (int i = 1; i < list.size(); i++) {
            output[i] = DjlTools.ndArrayToMat(list.get(i), "HW");
        }
        return output;
    }

}
