package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.ext.djl.DjlTools;


class MatTranslator implements Translator<Mat, Mat> {

    private final String inputLayoutNd;
    private final String outputLayoutNd;
    private boolean nucleiOnly = false;

    /**
     * Create a translator from InstanSeg input to output.
     * @param inputLayoutNd N-dimensional output specification
     * @param outputLayoutNd N-dimensional output specification
     * @param firstChannelOnly Should the model only be concerned with the first output channel?
     */
    MatTranslator(String inputLayoutNd, String outputLayoutNd, boolean firstChannelOnly) {
        this.inputLayoutNd = inputLayoutNd;
        this.outputLayoutNd = outputLayoutNd;
        this.nucleiOnly = firstChannelOnly;
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
        if (nucleiOnly) {
            var inds = new int[]{1, 0};
            var array = manager.create(inds, new Shape(2));
            var arrayCPU = array.toDevice(Device.cpu(), false);
            out.add(arrayCPU);
        }
        return out;
    }

    @Override
    public Mat processOutput(TranslatorContext ctx, NDList list) {
        var array = list.get(0);
        return DjlTools.ndArrayToMat(array, outputLayoutNd);
    }

}
