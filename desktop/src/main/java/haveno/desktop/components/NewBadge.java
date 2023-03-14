package haveno.desktop.components;

import com.jfoenix.controls.JFXBadge;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import javafx.collections.MapChangeListener;
import javafx.scene.Node;

public class NewBadge extends JFXBadge {

    private final String key;

    public NewBadge(Node control, String key, Preferences preferences) {
        super(control);

        this.key = key;

        setText(Res.get("shared.new"));
        getStyleClass().add("new");

        setEnabled(!preferences.getDontShowAgainMap().containsKey(key));
        refreshBadge();

        preferences.getDontShowAgainMapAsObservable().addListener((MapChangeListener<? super String, ? super Boolean>) change -> {
            if (change.getKey().equals(key)) {
                setEnabled(!change.wasAdded());
                refreshBadge();
            }
        });
    }
}
