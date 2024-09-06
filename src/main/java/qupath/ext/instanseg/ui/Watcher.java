package qupath.ext.instanseg.ui;

import javafx.beans.binding.ObjectBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.fx.utils.FXUtils;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

class Watcher {

    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);

    private WatchService watchService;
    private volatile boolean isRunning;

    // We use this rather than ConcurrentHashMap because it supports null values
    private final Map<Path, WatchKey> watchKeys = Collections.synchronizedMap(new HashMap<>());
    private final ObservableList<InstanSegModel> models = FXCollections.observableArrayList();
    private final ObservableList<InstanSegModel> modelsUnmodifiable = FXCollections.unmodifiableObservableList(models);

    private final ObjectBinding<Path> modelDirectoryBinding = InstanSegUtils.getModelDirectoryBinding();

    private static Watcher instance = new Watcher();

    private Watcher() {
        modelDirectoryBinding.addListener(this::handleModelDirectoryChange);
        handleModelDirectoryChange(modelDirectoryBinding, null, modelDirectoryBinding.get());
    }

    /**
     * Get a singleton instance of the Watcher.
     * @return
     */
    static Watcher getInstance() {
        return instance;
    }

    private void handleModelDirectoryChange(ObservableValue<? extends Path> observable, Path oldPath, Path newPath) {
        // Currently, we look *only* in the model directory for models
        // But we could register subdirectories here if we wanted (e.g. 'local', 'downloaded')
        if (oldPath != null) {
            unregister(oldPath);
        }
        if (newPath != null && Files.isDirectory(newPath)) {
            try {
                register(newPath);
            } catch (IOException e) {
                logger.error("Unable to register new model directory", e);
            }
        }
        refreshAllModels();
    }

    private synchronized void register(Path dir) throws IOException {
        // Check we aren't already watching
        if (dir == null || watchKeys.containsKey(dir) || !Files.isDirectory(dir))
            return;
        // Add a watch service if we have one - otherwise add to the map for worrying about later
        if (watchService != null) {
            WatchKey key = register(watchService, dir);
            watchKeys.put(dir, key);
        } else {
            watchKeys.put(dir, null);
        }
    }

    private synchronized void unregister(Path dir) {
        if (dir != null && watchKeys.containsKey(dir)) {
            var watchKey = watchKeys.remove(dir);
            if (watchKey != null) {
                watchKey.cancel();
            }
        }
    }

    /**
     * Process all events for keys queued to the watcher
     */
    private void processEvents() {
        while (isRunning) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                isRunning = false;
                return;
            }

            if (key.watchable() instanceof Path dir) {

                for (WatchEvent<?> rawEvent : key.pollEvents()) {
                    WatchEvent<Path> event = (WatchEvent<Path>)rawEvent;
                    WatchEvent.Kind<Path> kind = event.kind();
                    Path fileName = event.context();

                    // Context for directory entry event is the file name of entry
                    Path filePath = dir.resolve(fileName);

                    // print out event
                    logger.debug("{}: {}", event.kind().name(), filePath);

                    if (kind == ENTRY_CREATE) {
                        logger.info("File created: {}", rawEvent);
                        handleFileCreated(filePath);
                    }
                    if (kind == ENTRY_DELETE) {
                        logger.info("File deleted: {}", rawEvent);
                        handleFileDeleted(filePath);
                    }
                    if (kind == ENTRY_MODIFY) {
                        logger.info("File modified: {}", rawEvent);
                    }
                }
            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                watchKeys.values().remove(key);

                // all directories are inaccessible
                if (watchKeys.isEmpty()) {
                    break;
                }
            }
        }
    }

    private void handleFileCreated(Path path) {
        if (InstanSegModel.isValidModel(path)) {
            try {
                var model = InstanSegModel.fromPath(path);
                FXUtils.runOnApplicationThread(() -> ensureModelInList(model));
            } catch (IOException e) {
                logger.error("Unable to add model from path", e);
            }
        }
    }

    /**
     * Add the model, after checking it won't be a duplicate.
     * @param model
     */
    private void ensureModelInList(InstanSegModel model) {
        for (var existingModel : models) {
            if (existingModel.equals(model) || existingModel.getPath().equals(model.getPath())) {
                logger.debug("Existing model found, will not add {}", model);
                return;
            }
        }
        models.add(model);
    }

    private void handleFileDeleted(Path path) {
        var matches = models.stream()
                .filter(model -> model.getPath().map(p -> p.equals(path)).orElse(false))
                .collect(Collectors.toSet());
        if (!matches.isEmpty()) {
            FXUtils.runOnApplicationThread(() -> models.removeAll(matches));
        }
    }


    synchronized void stop() {
        isRunning = false;
    }

    synchronized void start() {
        if (isRunning)
            return;
        if (watchService == null) {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                // We might have registered some directories before we had a watch service
                // so we need to go back and register them now - and also update our model list
                for (Map.Entry<Path, WatchKey> entry : watchKeys.entrySet()) {
                    if (entry.getValue() == null) {
                        entry.setValue(register(watchService, entry.getKey()));
                    }
                }
                // Ensure our list is updated
                refreshAllModels();
            } catch (IOException e) {
                logger.error("Unable to create watch service", e);
            }
        }
        isRunning = true;
        Thread.ofVirtual().start(this::processEvents);
    }

    /**
     * Refresh all models to match the directories we are watching
     */
    private void refreshAllModels() {
        Set<InstanSegModel> set = new LinkedHashSet<>();
        for (var dir : watchKeys.keySet()) {
            for (var modelPath : getModelPathsInDir(dir)) {
                try {
                    set.add(InstanSegModel.fromPath(modelPath));
                } catch (IOException e) {
                    logger.error("Unable to load model from path", e);
                }
            }
        }
        FXUtils.runOnApplicationThread(() -> models.setAll(set));
    }

    private static List<Path> getModelPathsInDir(Path dir) {
        try {
            return Files.list(dir)
                    .filter(InstanSegModel::isValidModel)
                    .toList();
        } catch (IOException e) {
            logger.error("Unable to list files in directory", e);
            return List.of();
        }
    }

    private static WatchKey register(WatchService watchService, Path dir) throws IOException {
        return dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    /**
     * Get an unmodifiable observable list of the models found in the directories being watched.
     * @return
     */
    ObservableList<InstanSegModel> getModels() {
        return modelsUnmodifiable;
    }

}
