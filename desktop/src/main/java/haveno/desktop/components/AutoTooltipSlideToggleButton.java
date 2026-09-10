package haveno.desktop.components;

import haveno.core.locale.GlobalSettings;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public class AutoTooltipSlideToggleButton extends AutoTooltipToggleButton {

    public AutoTooltipSlideToggleButton() {
        getStyleClass().setAll("pill-toggle-button");

        Region selectedTrack = new Region();
        selectedTrack.getStyleClass().add("pill-toggle-selected-track");
        selectedTrack.setOpacity(0);

        Region thumb = new Region();
        thumb.getStyleClass().add("pill-toggle-thumb");
        thumb.setTranslateX(-7);

        StackPane track = new StackPane(selectedTrack, thumb);
        track.getStyleClass().add("pill-toggle-track");
        track.setMouseTransparent(true);
        setGraphic(track);

        Timeline animation = new Timeline();
        selectedProperty().addListener((observable, oldValue, selected) -> {
            animation.stop();
            double position = selected ? 7 : -7;
            double opacity = selected ? 1 : 0;
            if (getScene() == null || !GlobalSettings.getUseAnimations()) {
                thumb.setTranslateX(position);
                selectedTrack.setOpacity(opacity);
            } else {
                animation.getKeyFrames().setAll(new KeyFrame(Duration.millis(160),
                        new KeyValue(thumb.translateXProperty(), position, Interpolator.EASE_BOTH),
                        new KeyValue(selectedTrack.opacityProperty(), opacity, Interpolator.EASE_BOTH)));
                animation.playFromStart();
            }
        });
    }
}
