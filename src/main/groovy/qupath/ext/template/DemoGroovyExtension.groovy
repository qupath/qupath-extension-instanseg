package qupath.ext.template;

import javafx.scene.control.MenuItem;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.QuPathExtension;


/**
 * This is a demo to provide a template for creating a new QuPath extension in Groovy.
 * <p>
 * <b>Important!</b> For your extension to work in QuPath, you need to make sure the name & package
 * of this class is consistent with the file
 * <pre>
 *     /resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 * </pre>
 */
class DemoGroovyExtension implements QuPathExtension {

	// Setting the variables here is enough for them to be available in the extension
	String name = "My Groovy extension"
	String description = "This is just a demo to show how Groovy extensions work"
	Version QuPathVersion = Version.parse("v0.4.0")

	@Override
	void installExtension(QuPathGUI qupath) {
		addMenuItem(qupath)
	}

	private void addMenuItem(QuPathGUI qupath) {
		def menu = qupath.getMenu("Extensions>${name}", true)
		def menuItem = new MenuItem("My Groovy menu item")
		menuItem.setOnAction(e -> {
			Dialogs.showMessageDialog(name,
					"Hello! This is my Groovy extension.")
		})
		menu.getItems() << menuItem
	}
	
}
