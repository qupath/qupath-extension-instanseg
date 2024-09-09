package qupath.ext.instanseg.ui;

import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import org.controlsfx.glyphfont.FontAwesome;
import org.controlsfx.glyphfont.Glyph;
import org.controlsfx.glyphfont.GlyphFont;
import org.controlsfx.glyphfont.GlyphFontRegistry;
import qupath.ext.instanseg.core.InstanSegModel;

import java.util.ResourceBundle;

public class ModelListCell extends ListCell<InstanSegModel> {

    private ResourceBundle resources = InstanSegResources.getResources();

    private Glyph web = createOnlineIcon();
    private Tooltip tooltip;

    public ModelListCell() {
        super();
        tooltip = new Tooltip();
    }

    public void updateItem(InstanSegModel model, boolean empty) {
        super.updateItem(model, empty);
        if (empty || model == null) {
            setText(null);
            setGraphic(null);
            setTooltip(null);
        } else {
            setText(model.getName());
            var dir = InstanSegUtils.getLocalModelDirectory().orElse(null);
            if (dir != null && !model.isDownloaded(dir)) {
                setGraphic(web);
                tooltip.setText(resources.getString("ui.model-not-downloaded.tooltip"));
                setTooltip(tooltip);
            } else {
                setGraphic(null);
                tooltip.setText(model.getName());
                setTooltip(tooltip);
            }
        }
    }


    private Glyph createOnlineIcon() {
        GlyphFont fontAwesome = GlyphFontRegistry.font("FontAwesome");
        return fontAwesome.create(FontAwesome.Glyph.CLOUD);
    }

}
