package qupath.ext.instanseg.ui;

import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.value.ObservableValue;
import qupath.ext.instanseg.core.InstanSegModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Helper class for creating the InstanSeg UI.
 * These are here to simplify the controller class.
 */
class InstanSegUtils {

    private InstanSegUtils() {
        // Prevent instantiation
    }

    private static ObjectBinding<Path> modelDirectoryBinding = Bindings.createObjectBinding(
                () -> tryToGetPath(InstanSegPreferences.modelDirectoryProperty().get()),
                InstanSegPreferences.modelDirectoryProperty()
        );


    public static ObjectBinding<Path> getModelDirectoryBinding() {
        return modelDirectoryBinding;
    }


    public static Path tryToGetPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        } else {
            return Path.of(path);
        }
    }

    public static BooleanBinding isModelDirectoryValid(ObservableValue<Path> modelDirectory) {
        return Bindings.createBooleanBinding(
                () -> {
                    var path = modelDirectory.getValue();
                    return path != null && Files.isDirectory(path);
                },
                modelDirectory
        );
    }

    static Optional<Path> getModelDirectory() {
        return Optional.ofNullable(modelDirectoryBinding.get());
    }

    static Optional<Path> getDownloadedModelDirectory() {
        return getModelDirectory();
    }

    static Optional<Path> getLocalModelDirectory() {
        return getModelDirectory().map(p -> p.resolve("local"));
    }

    public static BooleanBinding createModelDownloadedBinding(ObservableValue<InstanSegModel> selectedModel, Observable needsUpdating) {
        return Bindings.createBooleanBinding(
                () -> {
                    var model = selectedModel.getValue();
                    if (model == null) {
                        return false;
                    }
                    var modelDir = getModelDirectory().orElse(null);
                    return modelDir != null && model.isDownloaded(modelDir);
                },
                selectedModel, needsUpdating,
                InstanSegPreferences.modelDirectoryProperty());
    }

}
