package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.ext.djl.DjlTools;


class MatTranslator implements Translator<Mat, Mat[]> {

    private final String inputLayoutNd;
    private final String outputLayoutNd;
    private final int[] outputChannels;

    /**
     * Create a translator from InstanSeg input to output.
     * @param inputLayoutNd N-dimensional output specification
     * @param outputLayoutNd N-dimensional output specification
     * @param outputChannels Array of channels to output; if null or empty, output all channels.
     *                       Values should be true for channels to output, false for channels to ignore.
     */
    MatTranslator(String inputLayoutNd, String outputLayoutNd, boolean[] outputChannels) {
        this.inputLayoutNd = inputLayoutNd;
        this.outputLayoutNd = outputLayoutNd;
        this.outputChannels = convertBooleanArray(outputChannels);
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
        return out;
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
