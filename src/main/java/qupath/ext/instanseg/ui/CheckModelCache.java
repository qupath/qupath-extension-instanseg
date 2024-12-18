package qupath.ext.instanseg.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import org.controlsfx.control.CheckComboBox;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Helper class to cache the checked items of a CheckComboBox based on an observable value.
 *
 * @param <S> the type of the value
 * @param <T> the type of the checked items
 */
public class CheckModelCache<S, T> {

    private final ObjectProperty<S> value = new SimpleObjectProperty<>();
    private final CheckComboBox<T> checkbox;
    private final Map<S, List<Integer>> lastChecks = new WeakHashMap<>();

    private CheckModelCache(ObservableValue<S> value, CheckComboBox<T> checkbox) {
        this.value.bind(value);
        this.checkbox = checkbox;
        this.value.addListener(this::handleValueChange);
    }

    /**
     * Create a new cache to store the last state of items in a CheckComboBox before an observable value changes,
     * or when {@link #snapshotChecks()} is called.
     * <p>
     * This can then be used to restore the checks later, if needed.
     *
     * @param value
     * @param checkBox
     * @return
     * @param <S>
     * @param <T>
     */
    public static <S, T> CheckModelCache<S, T> create(ObservableValue<S> value, CheckComboBox<T> checkBox) {
        return new CheckModelCache<>(value, checkBox);
    }

    private void handleValueChange(ObservableValue<? extends S> observable, S oldValue, S newValue) {
        if (oldValue != null) {
            lastChecks.put(oldValue, List.copyOf(checkbox.getCheckModel().getCheckedIndices()));
        }
    }

    /**
     * Get the value property.
     * @return
     */
    public ReadOnlyObjectProperty<S> valueProperty() {
        return value;
    }

    /**
     * Restore the checks for the current value.
     * <p>
     * This does not happen automatically, because the user might need to take other actions before restoring the checks,
     * such as to update the items in the CheckModel.
     * <p>
     * Also, it will not restore checks if the items in the CheckModel have changed, so that the previously-checked
     * items are no longer available.
     *
     * @return true if the checks were restored, false otherwise
     */
    public boolean restoreChecks() {
        List<Integer> checks = lastChecks.get(value.get());
        if (checks != null && checks.stream().allMatch(i -> i < checkbox.getItems().size())) {
            var checkModel = checkbox.getCheckModel();
            checkModel.clearChecks();
            checks.forEach(checkModel::checkIndices);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Query whether there are checks stored for the current value.
     * @return true if checks are stored, false otherwise
     */
    public boolean hasChecks() {
        return lastChecks.containsKey(value.get());
    }

    /**
     * Remove the checks for the current value.
     * @return true if checks were removed, false otherwise
     */
    public boolean resetChecks() {
        return lastChecks.remove(value.get()) != null;
    }

    /**
     * Create a snapshot of the checks currently associated with the observable value.
     * This is useful in case some other checkbox manipulation is required without switching the value,
     * and we want to restore the checks later (e.g. changing the items).
     * @return
     */
    public boolean snapshotChecks() {
        var val = value.get();
        if (val != null) {
            lastChecks.put(val, List.copyOf(checkbox.getCheckModel().getCheckedIndices()));
            return true;
        } else {
            return false;
        }
    }

}
