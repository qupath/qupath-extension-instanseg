package qupath.ext.instanseg.ui;

import javafx.application.Platform;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSegModel;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

class Watcher {
    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);

    private final WatchService watchService;
    private final Map<WatchKey, Path> keys;
    private final SearchableComboBox<InstanSegModel> modelChoiceBox;
    private boolean interrupted;

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>)event;
    }

    void register(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        keys.put(key, dir);
    }

    void unregister(Path dir) {
        for (var es: keys.entrySet()) {
            if (es.getValue().equals(dir)) {
                logger.debug("Unregister: {}", es.getValue());
                es.getKey().cancel();
                keys.remove(es.getKey());
            }
        }
    }

    /**
     * Creates a WatchService and registers the given directory
     */
    Watcher(SearchableComboBox<InstanSegModel> modelChoiceBox) throws IOException {
        this.modelChoiceBox = modelChoiceBox;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.keys = new ConcurrentHashMap<>();
    }

    /**
     * Process all events for keys queued to the watcher
     */
    void processEvents() {
        while (!interrupted) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                logger.error("WatchKey not recognized!!");
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();

                // Context for directory entry event is the file name of entry
                Path child = dir.resolve(name);

                // print out event
                logger.debug("{}: {}", event.kind().name(), child);

                if (kind == ENTRY_CREATE && InstanSegModel.isValidModel(name)) {
                    Platform.runLater(() -> {
                        try {
                            modelChoiceBox.getItems().add(InstanSegModel.fromPath(child));
                        } catch (IOException e) {
                            logger.error("Unable to add model from path", e);
                        }
                    });
                }
                if (kind == ENTRY_DELETE && InstanSegModel.isValidModel(name)) {
                    Platform.runLater(() -> {
                        modelChoiceBox.getItems().removeIf(model ->
                                model.getPath().map(p -> p.equals(child)).orElse(false));
                    });
                }

            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }

    void interrupt() {
        interrupted = true;
    }
}
