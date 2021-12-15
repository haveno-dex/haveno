package bisq.desktop.components;

import com.jfoenix.controls.JFXTextArea;

import javafx.scene.control.Skin;

public class HavenoTextArea extends JFXTextArea {
    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXTextAreaSkinBisqStyle(this);
    }
}
