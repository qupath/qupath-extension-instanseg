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
    private final int nOutputChannels;

    /**
     * Create a translator from InstanSeg input to output.
     * @param inputLayoutNd N-dimensional output specification
     * @param outputLayoutNd N-dimensional output specification
     * @param nOutputChannels Number of output channels, or -1 if unknown. One use of this is to limit the output
     *                        to the first channel only, e.g. to detect nuclei only using a cell detection model
     */
    MatTranslator(String inputLayoutNd, String outputLayoutNd, int nOutputChannels) {
        this.inputLayoutNd = inputLayoutNd;
        this.outputLayoutNd = outputLayoutNd;
        this.nOutputChannels = nOutputChannels;
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
        if (nOutputChannels == 1) {
            var inds = new int[]{1, 0};
            var array = manager.create(inds, new Shape(2));
            var arrayCPU = array.toDevice(Device.cpu(), false);
            out.add(arrayCPU);
        }
        return out;
    }

    @Override
    public Mat processOutput(TranslatorContext ctx, NDList list) {
        var array = list.getFirst();
        return DjlTools.ndArrayToMat(array, outputLayoutNd);
    }

}
