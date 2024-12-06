package qupath.ext.instanseg.ui;

import javafx.application.Platform;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
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

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Watcher to look for changes in the model directory.
 * This can provide an observable list containing the available local models.
 */
class Watcher {

    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);

    private WatchService watchService;
    private volatile boolean isRunning;

    // We use this rather than ConcurrentHashMap because it supports null values,
    // which we use to indicate directories we will want to watch as soon as the watch service is activated
    private final Map<Path, WatchKey> watchKeys = Collections.synchronizedMap(new HashMap<>());

    // Store an observable list of model paths
    private final ObservableList<Path> modelPaths = FXCollections.observableArrayList();

    // Store an observable list of models, which we update only when the paths change
    private final ObservableList<InstanSegModel> models = FXCollections.observableArrayList();
    private final ObservableList<InstanSegModel> modelsUnmodifiable = FXCollections.unmodifiableObservableList(models);

    //Binding to the directory we want to watch for models.
    private final ObjectBinding<Path> modelDirectoryBinding = InstanSegUtils.getModelDirectoryBinding();

    private static final Watcher instance = new Watcher();

    private Watcher() {
        modelDirectoryBinding.addListener(this::handleModelDirectoryChange);
        modelPaths.addListener(this::handleModelPathsChanged);
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
            unregister(oldPath.resolve("local"));
        }
        if (newPath != null && Files.isDirectory(newPath.resolve("local"))) {
            try {
                register(newPath.resolve("local"));
            } catch (IOException e) {
                logger.error("Unable to register new model directory", e);
            }
        }
        refreshAllModelPaths();
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

    private static WatchKey register(WatchService watchService, Path dir) throws IOException {
        return dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
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
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> event = (WatchEvent<Path>)rawEvent;
                    WatchEvent.Kind<Path> kind = event.kind();
                    Path fileName = event.context();

                    // Context for directory entry event is the file name of entry
                    Path filePath = dir.resolve(fileName);

                    // print out event
                    logger.debug("{}: {}", event.kind().name(), filePath);

                    // At least on macOS, renaming a file results in a CREATE then a DELETE event.
                    // This means that listeners can be notified of a 'new' model before they are informed
                    // that the previous model has been deleted - and both models will have the same name,
                    // because this is read from rdf.yaml.
                    // To reduce the risk of this causing trouble, do a full directory refresh on any change.
                    if (kind == ENTRY_CREATE) {
                        logger.debug("File created: {}", rawEvent);
                        refreshAllModelPaths();
                    }
                    if (kind == ENTRY_DELETE) {
                        logger.debug("File deleted: {}", rawEvent);
                        refreshAllModelPaths();
                    }
                    if (kind == ENTRY_MODIFY) {
                        logger.debug("File modified: {}", rawEvent);
                        refreshAllModelPaths();
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
                refreshAllModelPaths();
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
    private void refreshAllModelPaths() {
        Set<Path> currentPaths = new LinkedHashSet<>();
        for (var dir : watchKeys.keySet()) {
            currentPaths.addAll(getModelPathsInDir(dir));
        }
        // We only want to update the list if it has changed, to avoid rebuilding models unnecessarily
        if (modelPaths.size() == currentPaths.size() && currentPaths.containsAll(modelPaths))
            return;
        FXUtils.runOnApplicationThread(() -> modelPaths.setAll(currentPaths));
    }

    /**
     * Update all models in response to a change in the model paths.
     * @param change
     */
    private void handleModelPathsChanged(ListChangeListener.Change<? extends Path> change) {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("Must be on FX thread");
        Set<InstanSegModel> set = new LinkedHashSet<>();
        for (var modelPath : modelPaths) {
            try {
                set.add(InstanSegModel.fromPath(modelPath));
            } catch (IOException e) {
                logger.error("Unable to load model from path", e);
            }
        }
        models.setAll(set);
    }

    /**
     * Get all the local models in a directory.
     * @param dir
     * @return
     */
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

    /**
     * Get an unmodifiable observable list of the models found in the directories being watched.
     * <p>
     * Note that this list is updated only when the paths change, but it is *not* guaranteed to return
     * the same instance of InstanSeg model for each path.
     * Any calling code needs to figure out of models are really the same or different, which requires some
     * decision-making (e.g. is a model that has been downloaded the same as the local representation...?
     * Or are two models the same if the directories are duplicated, so that one has a different path...?).
     * @return
     */
    ObservableList<InstanSegModel> getModels() {
        return modelsUnmodifiable;
    }

}
