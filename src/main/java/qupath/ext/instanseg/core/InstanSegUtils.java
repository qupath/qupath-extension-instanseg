package qupath.ext.instanseg.core;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.nio.file.Files;
import java.nio.file.Path;

public class InstanSegUtils {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegUtils.class);

    private InstanSegUtils() {
        throw new AssertionError("Do not instantiate this class");
    }

    public static boolean isValidModel(Path path) {
        // return path.toString().endsWith(".pt"); // if just looking at pt files
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("instanseg.pt")) && Files.exists(path.resolve("rdf.yaml"));
        }
        return false;
    }

}
