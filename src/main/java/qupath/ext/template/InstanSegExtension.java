package qupath.ext.template;

import ai.djl.MalformedModelException;
import ai.djl.repository.zoo.ModelNotFoundException;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.MenuItem;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageOps;
import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.training.util.ProgressBar;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static qupath.lib.gui.scripting.QPEx.createTaskRunner;


public class InstanSegExtension implements QuPathExtension, GitHubProject {
	
	private static final Logger logger = LoggerFactory.getLogger(InstanSegExtension.class);

	/**
	 * Display name for your extension
	 * TODO: define this
	 */
	private static final String EXTENSION_NAME = "InstanSeg";

	/**
	 * Short description, used under 'Extensions > Installed extensions'
	 * TODO: define this
	 */
	private static final String EXTENSION_DESCRIPTION = "Use the InstanSeg deep learning model in QuPath";

	/**
	 * QuPath version that the extension is designed to work with.
	 * This allows QuPath to inform the user if it seems to be incompatible.
	 * TODO: define this
	 */
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");

	/**
	 * GitHub repo that your extension can be found at.
	 * This makes it easier for users to find updates to your extension.
	 * If you don't want to support this feature, you can remove
	 * references to GitHubRepo and GitHubProject from your extension.
	 * TODO: define this
	 */
	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "qupath", "qupath-extension-instanseg");

	/**
	 * Flag whether the extension is already installed (might not be needed... but we'll do it anyway)
	 */
	private boolean isInstalled = false;

	/**
	 * A 'persistent preference' - showing how to create a property that is stored whenever QuPath is closed
	 */
	private BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"enableExtension", true);

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addPreference(qupath);
		addMenuItem(qupath);
	}

	/**
	 * Demo showing how to add a persistent preference to the QuPath preferences pane.
	 * @param qupath
	 */
	private void addPreference(QuPathGUI qupath) {
		qupath.getPreferencePane().addPropertyPreference(
				enableExtensionProperty,
				Boolean.class,
				"Enable my extension",
				EXTENSION_NAME,
				"Enable my extension");
	}

	/**
	 * Demo showing how a new command can be added to a QuPath menu.
	 * @param qupath
	 */
	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem("My menu item");
		menuItem.setOnAction(e -> {
			if (QP.getSelectedObjects().isEmpty()) {
				Dialogs.showErrorNotification("Instanseg", "No annotation selected!");
			}

			// Specify device
			String deviceName = "cpu"; // "mps", "cuda"
//			deviceName = "gpu";

			// May need to reduce threads to avoid trouble (especially if using mps/cuda)
			// int nThreads = qupath.lib.common.ThreadTools.getParallelism()
			int nThreads = 1;
			logger.info("Using $nThreads threads");
			int nPredictors = 1;

			// TODO: Set path!
			var path = "/home/alan/Documents/github/imaging/models/instanseg_39107731.pt";
			var imageData = QP.getCurrentImageData();

			double downsample = 0.5 / imageData.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();

			int inputWidth = 256;
			int inputHeight = inputWidth;
			int padding = 16;
			// Optionally pad images to the required size
			boolean padToInputSize = true;
			String layout = "CHW";

			// TODO: Remove C if not needed (added for instanseg_v0_2_0.pt)
			String layoutOutput = "CHW";


			var device = Device.fromName(deviceName);

			try (var model = Criteria.builder()
					.setTypes(Mat.class, Mat.class)
					.optModelUrls(path)
					.optProgress(new ProgressBar())
					.optDevice(device) // Remove this line if devices are problematic!
					.optTranslator(new MatTranslator(layout, layoutOutput))
					.build()
					.loadModel()) {

				BaseNDManager baseManager = (BaseNDManager)model.getNDManager();

				System.out.println(baseManager.getParentManager());
				printResourceCount("Resource count before prediction", baseManager.getParentManager());
				baseManager.debugDump(2);

				BlockingQueue<Predictor<Mat, Mat>> predictors = new ArrayBlockingQueue<>(nPredictors);

				try {
					for (int i = 0; i < nPredictors; i++)
						predictors.put(model.newPredictor());

					printResourceCount("Resource count after creating predictors", baseManager.getParentManager());

					var preprocessing = ImageOps.Core.sequential(
							ImageOps.Core.ensureType(PixelType.FLOAT32),
							// ImageOps.Core.divide(255.0)
							ImageOps.Normalize.percentile(1, 99, true, 1e-6)
					);
					var predictionProcessor = new TilePredictionProcessor(predictors, baseManager,
							layout, layoutOutput, preprocessing, inputWidth, inputHeight, padToInputSize);
					var processor = OpenCVProcessor.builder(predictionProcessor)
							// .tiler(Tiler.builder(inputWidth-padding*2, inputHeight-padding*2)
							.tiler(Tiler.builder((int)(downsample * inputWidth-padding*2), (int)(downsample * inputHeight-padding*2))
									.alignTopLeft()
									.cropTiles(false)
									.build()
							)
							.outputHandler(OutputHandler.createObjectOutputHandler(new OutputToObjectConvert()))
							.padding(padding)
							.mergeSharedBoundaries(0.25)
							.downsample(downsample)
							.build();
					var runner = createTaskRunner(nThreads);
					processor.processObjects(runner, imageData, QP.getSelectedObjects());
				} finally {
					for (var predictor: predictors) {
						predictor.close();
					}
				}
				printResourceCount("Resource count after prediction", baseManager.getParentManager());
			} catch (ModelNotFoundException | MalformedModelException |
                     IOException | InterruptedException ex) {
				Dialogs.showErrorMessage("Unable to run InstanSeg", ex);
                logger.error("Unable to run InstanSeg", ex);
            }
			QP.fireHierarchyUpdate();
        });
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}
	
	
	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}

	@Override
	public GitHubRepo getRepository() {
		return EXTENSION_REPOSITORY;
	}

	private static void printResourceCount(String title, NDManager manager) {
		if (!title.isEmpty())
			System.out.println(title);
	}

}
