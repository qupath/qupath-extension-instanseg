package qupath.ext.instanseg.core;


import qupath.bioimageio.spec.BioimageIoSpec;
import qupath.lib.gui.UserDirectoryManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;


// local class can have a constructor that takes a path and
// remote class is empty, and fields get populated when we "fetch" it (check locally and download if not)
public class InstanSegModel {

    private Path path = null;
    private URL modelURL = null;
    private BioimageIoSpec.BioimageIoModel model = null;
    private final String name;

    private InstanSegModel(BioimageIoSpec.BioimageIoModel bioimageIoModel) {
        this.model = bioimageIoModel;
        this.path = Paths.get(model.getBaseURI());
        this.name = model.getName();
    }

    public InstanSegModel(URL modelURL, String name) {
        this.modelURL = modelURL;
        this.name = name;
    }

    public static InstanSegModel createModel(Path path) throws IOException {
        return new InstanSegModel(BioimageIoSpec.parseModel(path.toFile()));
    }

    public BioimageIoSpec.BioimageIoModel getModel() throws IOException {
        if (model == null) {
            fetchModel();
        }
        return model;
    }

    private void fetchModel() throws IOException {
        if (modelURL == null) {
            throw new NullPointerException("Model URL should not be null for a local model!");
        }
        downloadZipFile(modelURL, getUserDir().resolve("instanseg"));
    }

    private static void downloadZipFile(URL url, Path localDirectory) throws IOException {
        // Open a connection to the URL
        try (BufferedInputStream in = new BufferedInputStream(url.openStream())) {
            String fileName = url.toString().substring(url.toString().lastIndexOf('/') + 1);
            Path localFilePath = localDirectory.resolve(fileName);
            Files.copy(in, localFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path getUserDir() {
        Path userPath = UserDirectoryManager.getInstance().getUserPath();
        Path cachePath = Paths.get(System.getProperty("user.dir"), ".cache", "QuPath");
        return userPath == null || userPath.toString().isEmpty() ?  cachePath : userPath;
    }

    public String getName() {
        return name;
    }

    public Path getPath() {
        if (path == null) {
            try {
                fetchModel();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return path;
    }
}
