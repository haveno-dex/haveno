package haveno.desktop.components;

import com.jfoenix.controls.JFXTextArea;

import javafx.scene.control.Skin;

public class HavenoTextArea extends JFXTextArea {
    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXTextAreaSkinHavenoStyle(this);
    }
}
