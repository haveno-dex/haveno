package haveno.desktop.components;

import com.jfoenix.controls.JFXTextArea;
import javafx.scene.control.Skin;

public class HavenoTextArea extends JFXTextArea {

    public HavenoTextArea() {
        // expose the floating prompt to screen readers
        promptTextProperty().addListener((o, oldValue, newValue) -> setAccessibleHelp(newValue));
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXTextAreaSkinHavenoStyle(this);
    }
}
