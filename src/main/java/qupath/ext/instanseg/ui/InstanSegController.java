package qupath.ext.instanseg.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;
import qupath.ext.instanseg.core.InstanSegCommand;

import java.io.IOException;
import java.util.ResourceBundle;

/**
 * Controller for UI pane contained in instanseg_control.fxml
 */
public class InstanSegController extends VBox {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.instanseg.ui.strings");

    public static InstanSegController createInstance() throws IOException {
        return new InstanSegController();
    }

    private InstanSegController() throws IOException {
        var url = InstanSegController.class.getResource("instanseg_control.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
    }

    @FXML
    private void runInstanSeg() {
        InstanSegCommand.runInstanSeg();
    }
}
