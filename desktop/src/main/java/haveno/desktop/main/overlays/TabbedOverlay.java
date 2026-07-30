package haveno.desktop.main.overlays;

import com.jfoenix.controls.JFXTabPane;
import haveno.desktop.util.Accessibility;
import javafx.scene.layout.Region;

public abstract class TabbedOverlay<T extends TabbedOverlay<T>> extends Overlay<T> {

    protected JFXTabPane tabPane;

    protected void createTabPane() {
        this.tabPane = new JFXTabPane();
        tabPane.setMinWidth(width);
        Accessibility.fixTabs(tabPane);
    }

    @Override
    protected Region getRootContainer() {
        return tabPane;
    }
}
