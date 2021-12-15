package bisq.desktop.components;

import com.jfoenix.controls.JFXTextField;

import javafx.scene.control.Skin;

public class HavenoTextField extends JFXTextField {

    public HavenoTextField(String value) {
        super(value);
    }

    public HavenoTextField() {
        super();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXTextFieldSkinBisqStyle<>(this, 0);
    }
}
