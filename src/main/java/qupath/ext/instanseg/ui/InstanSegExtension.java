package qupath.ext.instanseg.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;


public class InstanSegExtension implements QuPathExtension, GitHubProject {
	
	private static final Logger logger = LoggerFactory.getLogger(InstanSegExtension.class);

	private static final String EXTENSION_NAME = "InstanSeg";

	private static final String EXTENSION_DESCRIPTION = "Use the InstanSeg deep learning model in QuPath";

	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");

	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "qupath", "qupath-extension-instanseg");

	private boolean isInstalled = false;

	private StringProperty modelDirectoryProperty = PathPrefs.createPersistentPreference(
			"instanseg.model.dir",
			null);

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
		addPreference(qupath);
		addMenuItem(qupath);
	}

	private void addPreference(QuPathGUI qupath) {
		qupath.getPreferencePane().addPropertyPreference(
				modelDirectoryProperty,
				String.class,
				"InstanSeg path",
				EXTENSION_NAME,
				"Path to InstanSeg model file.");
	}

	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem("Run InstanSeg");
		menuItem.setOnAction(e -> createStage());
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}

	private void createStage() {
		if (stage == null) {
			try {
				stage = new Stage();
				Scene scene = new Scene(InstanSegController.createInstance());
				stage.setScene(scene);
			} catch (IOException e) {
				Dialogs.showErrorMessage("InstanSeg", "GUI loading failed");
				logger.error("Unable to load InstanSeg FXML", e);
			}
		}
		stage.show();
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
