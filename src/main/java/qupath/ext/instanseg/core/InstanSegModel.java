package qupath.ext.instanseg.core;

import com.google.gson.internal.LinkedTreeMap;
import qupath.bioimageio.spec.BioimageIoSpec;
import qupath.lib.gui.UserDirectoryManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
        if (path.toString().endsWith(".zip")) {
            var outfile = path.getParent().resolve(path.toString().replace(".zip", ""));
            unzip(path, outfile);
            path = outfile;
        }
        return new InstanSegModel(BioimageIoSpec.parseModel(path.toFile()));
    }

    public BioimageIoSpec.BioimageIoModel getModel() {
        if (model == null) {
            try {
                fetchModel();
            } catch (IOException e) {
                // todo: exception handling here, or...?
                throw new RuntimeException(e);
            }
        }
        return model;
    }

    public Double getPixelSizeX() {
        return getPixelSize().get("x");
    }

    public Double getPixelSizeY() {
        return getPixelSize().get("y");
    }

    private Map<String, Double> getPixelSize() {
        var map = new HashMap<String, Double>();
        var config = (LinkedTreeMap)getModel().getConfig().get("qupath");
        var axes = (ArrayList)config.get("axes");
        map.put("x", (Double) ((LinkedTreeMap)(axes.get(0))).get("step"));
        map.put("y", (Double) ((LinkedTreeMap)(axes.get(1))).get("step"));
        return map;
    }

    private void fetchModel() throws IOException {
        if (modelURL == null) {
            throw new NullPointerException("Model URL should not be null for a local model!");
        }
        downloadAndUnzip(modelURL, getUserDir().resolve("instanseg"));
    }

    private static void downloadAndUnzip(URL url, Path localDirectory) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(url.openStream())) {
            String fileName = url.toString().substring(url.toString().lastIndexOf('/') + 1);
            Path localFilePath = localDirectory.resolve(fileName);
            Files.copy(in, localFilePath, StandardCopyOption.REPLACE_EXISTING);
            // todo: option?
            Path outdir = localDirectory.resolve(fileName.replace(".zip", ""));
            unzip(localFilePath, outdir);
        }
    }

    private static void unzip(Path zipFilePath, Path destDirectory) throws IOException {
        if (!Files.exists(destDirectory)) {
            Files.createDirectory(destDirectory); // todo: check if existing? rename if so?
        }
        try (ZipFile zipFile = new ZipFile(String.valueOf(zipFilePath))) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String entryName = zipEntry.getName();
                Path entryPath = destDirectory.resolve(entryName);

                if (zipEntry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zipFile.getInputStream(zipEntry), entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
                // todo: handle here, or...?
                throw new RuntimeException(e);
            }
        }
        return path;
    }

    @Override
    public String toString() {
        return getName();
    }
}
