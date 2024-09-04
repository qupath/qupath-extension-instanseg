package qupath.ext.instanseg.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ChoiceBox;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.SearchableComboBox;
import qupath.ext.instanseg.core.InstanSegModel;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ResourceBundle;

/**
 * Helper class for determining which text to display in the message label.
 */
class MessageTextHelper {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.instanseg.ui.strings");
    private static final QuPathGUI qupath = QuPathGUI.getInstance();
    private final SelectedObjectCounter selectedObjectCounter;
    private final SearchableComboBox<InstanSegModel> modelChoiceBox;
    private final ChoiceBox<String> deviceChoiceBox;
    private final CheckComboBox<ChannelSelectItem> comboChannels;
    private final BooleanProperty needsUpdating;

    /**
     * Text to display a warning (because inference can't be run)
     */
    private StringBinding warningText;
    /**
     * Text to display the number of selected objects (usually when inference can be run)
     */
    private StringBinding selectedObjectText;
    /**
     * Text to display in the message label (either the warning or the selected object text)
     */
    private StringBinding messageLabelText;

    /**
     * Binding to check if the warning is empty.
     * Retained here because otherwise code that attaches a listener to {@code warningText.isEmpty()} would need to
     * retain a reference to the binding to prevent garbage collection.
     */
    private BooleanBinding hasWarning;

    MessageTextHelper(SearchableComboBox<InstanSegModel> modelChoiceBox, ChoiceBox<String> deviceChoiceBox, CheckComboBox<ChannelSelectItem> comboChannels, BooleanProperty needsUpdating) {
        this.modelChoiceBox = modelChoiceBox;
        this.deviceChoiceBox = deviceChoiceBox;
        this.comboChannels = comboChannels;
        this.needsUpdating = needsUpdating;
        this.selectedObjectCounter = new SelectedObjectCounter(qupath.imageDataProperty());
        configureMessageTextBindings();
    }

    private void configureMessageTextBindings() {
        this.warningText = createWarningTextBinding();
        this.selectedObjectText = createSelectedObjectTextBinding();
        this.messageLabelText = Bindings.createStringBinding(() -> {
            var warning = warningText.get();
            if (warning == null || warning.isEmpty())
                return selectedObjectText.get();
            else
                return warning;
        }, warningText, selectedObjectText);
        this.hasWarning = warningText.isEmpty().not();
    }

    private StringBinding createSelectedObjectTextBinding() {
        return Bindings.createStringBinding(this::getSelectedObjectText,
                selectedObjectCounter.numSelectedAnnotations,
                selectedObjectCounter.numSelectedDetections,
                selectedObjectCounter.numSelectedTMACores);
    }

    private String getSelectedObjectText() {
        int nAnnotations = selectedObjectCounter.numSelectedAnnotations.get();
        int nDetections = selectedObjectCounter.numSelectedDetections.get();
        int nCores = selectedObjectCounter.numSelectedTMACores.get();
        if (nAnnotations == 1)
            return resources.getString("ui.selection.annotations-single");
        else if (nAnnotations > 1)
            return String.format(resources.getString("ui.selection.annotations-multiple"), nAnnotations);
        else if (nCores == 1)
            return resources.getString("ui.selection.TMA-cores-single");
        else if (nCores > 1)
            return String.format(resources.getString("ui.selection.TMA-cores-multiple"), nCores);
        else if (nDetections == 1)
            return resources.getString("ui.selection.detections-single");
        else if (nDetections > 1)
            return String.format(resources.getString("ui.selection.detections-multiple"), nDetections);
        else
            return resources.getString("ui.selection.empty");
    }

    private StringBinding createWarningTextBinding() {
        return Bindings.createStringBinding(this::getWarningText,
                qupath.imageDataProperty(),
                modelChoiceBox.getSelectionModel().selectedItemProperty(),
                comboChannels.getCheckModel().getCheckedItems(),
                deviceChoiceBox.getSelectionModel().selectedItemProperty(),
                selectedObjectCounter.numSelectedAnnotations,
                selectedObjectCounter.numSelectedTMACores,
                needsUpdating);
    }

    private String getWarningText() {
        if (qupath.imageDataProperty().get() == null)
            return resources.getString("ui.error.no-image");
        if (modelChoiceBox.getSelectionModel().isEmpty())
            return resources.getString("ui.error.no-model");
        if (selectedObjectCounter.numSelectedAnnotations.get() == 0 &&
                selectedObjectCounter.numSelectedTMACores.get() == 0)
            return resources.getString("ui.error.no-selection");
        if (deviceChoiceBox.getSelectionModel().isEmpty())
            return resources.getString("ui.error.no-device");
        if (modelChoiceBox.getSelectionModel().getSelectedItem().isDownloaded(Path.of(InstanSegPreferences.modelDirectoryProperty().get()))) {
            // shouldn't happen if downloaded anyway!
            var modelChannels = modelChoiceBox.getSelectionModel().getSelectedItem().getNumChannels();
            if (modelChannels.isPresent()) {
                int nModelChannels = modelChannels.get();
                int selectedChannels = comboChannels.getCheckModel().getCheckedItems().size();
                if (nModelChannels != InstanSegModel.ANY_CHANNELS) {
                    if (nModelChannels != selectedChannels) {
                        return String.format(
                                resources.getString("ui.error.num-channels-dont-match"),
                                nModelChannels,
                                selectedChannels);
                    }
                }
            }
        }
        return null;
    }

    StringBinding messageLabelText() {
        return messageLabelText;
    }

    BooleanBinding hasWarning() {
        return hasWarning;
    }

    StringBinding warningText() {
        return warningText;
    }

    /**
     * Helper class for maintaining a count of selected annotations and detections,
     * determined from an ImageData property (whose value may change).
     * This addresses the awkwardness of attaching/detaching listeners.
     */
    static class SelectedObjectCounter {

        private final ObjectProperty<ImageData<?>> imageDataProperty = new SimpleObjectProperty<>();

        private final PathObjectSelectionListener selectionListener = this::selectedPathObjectChanged;

        private final ObservableValue<PathObjectHierarchy> hierarchyProperty;

        private final IntegerProperty numSelectedAnnotations = new SimpleIntegerProperty();
        private final IntegerProperty numSelectedDetections = new SimpleIntegerProperty();
        private final IntegerProperty numSelectedTMACores = new SimpleIntegerProperty();

        SelectedObjectCounter(ObservableValue<ImageData<BufferedImage>> imageDataProperty) {
            this.imageDataProperty.bind(imageDataProperty);
            this.hierarchyProperty = createHierarchyBinding();
            hierarchyProperty.addListener((observable, oldValue, newValue) -> updateHierarchy(oldValue, newValue));
            updateHierarchy(null, hierarchyProperty.getValue());
        }

        private ObjectBinding<PathObjectHierarchy> createHierarchyBinding() {
            return Bindings.createObjectBinding(() -> {
                        var imageData = imageDataProperty.get();
                        return imageData == null ? null : imageData.getHierarchy();
                    },
                    imageDataProperty);
        }

        private void updateHierarchy(PathObjectHierarchy oldValue, PathObjectHierarchy newValue) {
            if (oldValue == newValue)
                return;
            if (oldValue != null)
                oldValue.getSelectionModel().removePathObjectSelectionListener(selectionListener);
            if (newValue != null)
                newValue.getSelectionModel().addPathObjectSelectionListener(selectionListener);
            updateSelectedObjectCounts();
        }

        private void selectedPathObjectChanged(PathObject pathObjectSelected, PathObject previousObject, Collection<PathObject> allSelected) {
            updateSelectedObjectCounts();
        }

        private void updateSelectedObjectCounts() {
            if (!Platform.isFxApplicationThread()) {
                Platform.runLater(this::updateSelectedObjectCounts);
                return;
            }
            var hierarchy = hierarchyProperty.getValue();
            if (hierarchy == null) {
                numSelectedAnnotations.set(0);
                numSelectedDetections.set(0);
                numSelectedTMACores.set(0);
                return;
            }

            var selected = hierarchy.getSelectionModel().getSelectedObjects();
            numSelectedAnnotations.set(
                    (int)selected
                            .stream().filter(PathObject::isAnnotation)
                            .count()
            );
            numSelectedDetections.set(
                    (int)selected
                            .stream().filter(PathObject::isDetection)
                            .count()
            );
            numSelectedTMACores.set(
                    (int)selected
                            .stream().filter(PathObject::isTMACore)
                            .count()
            );
        }

    }
}
