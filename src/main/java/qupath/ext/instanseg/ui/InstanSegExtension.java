package qupath.ext.instanseg.ui;

import javafx.beans.property.BooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;
import java.util.ResourceBundle;


public class InstanSegExtension implements QuPathExtension, GitHubProject {
	
	private static final Logger logger = LoggerFactory.getLogger(InstanSegExtension.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.instanseg.ui.strings");

	private static final String EXTENSION_NAME = "InstanSeg";

	private static final String EXTENSION_DESCRIPTION = "Use InstanSeg deep learning models for inference in QuPath";

	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "qupath", "qupath-extension-instanseg");

	private boolean isInstalled = false;


	private final BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"enableExtension", true);
	private Stage stage;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addMenuItem(qupath);
	}


	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem("Run InstanSeg");
		menuItem.setOnAction(e -> createStage(qupath));
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}

	private void createStage(QuPathGUI qupath) {
		if (stage == null) {
			try {
				stage = new Stage();
				var pane = InstanSegController.createInstance(qupath);
				Scene scene = new Scene(new BorderPane(pane));
				pane.heightProperty().addListener((v, o, n) -> handleStageHeightChange());
				stage.setScene(scene);
				stage.initOwner(QuPathGUI.getInstance().getStage());
				stage.setTitle(resources.getString("title"));
				stage.setResizable(false);
				stage.setOnShown(e -> Watcher.getInstance().start());
				stage.setOnHidden(e -> Watcher.getInstance().stop());
			} catch (IOException e) {
				Dialogs.showErrorMessage("InstanSeg", "GUI loading failed");
				logger.error("Unable to load InstanSeg FXML", e);
			}
		}
		stage.show();
	}


	private void handleStageHeightChange() {
		stage.sizeToScene();
		// This fixes a bug where the stage would migrate to the corner of a screen if it is
		// resized, hidden, then shown again
		if (stage.isShowing() && Double.isFinite(stage.getX()) && Double.isFinite(stage.getY()))
			FXUtils.retainWindowPosition(stage);
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


}
