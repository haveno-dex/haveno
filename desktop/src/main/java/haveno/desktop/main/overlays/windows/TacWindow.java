/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.main.overlays.windows;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import lombok.extern.slf4j.Slf4j;

/**
 * The user agreement popup for existing installations (first-run installations accept the
 * agreement in the startup wizard instead): a fixed-size card paging between the risk and
 * legal pages of {@link TacContent}, accepted once with the agree action on the legal page.
 */
@Slf4j
public class TacWindow extends Overlay<TacWindow> {

    private static final double WINDOW_WIDTH = 900;
    private static final double WINDOW_HEIGHT = 555;
    private static final double LOGO_FIT_WIDTH = 190;
    private static final double SHADOW_PADDING = 30;
    private static final double TITLE_BAR_HEIGHT = 40;
    private static final double FOOTER_HEIGHT = 48;
    private static final double GRID_HORIZONTAL_PADDING = 40;
    private static final double PAGE_WIDTH = WINDOW_WIDTH - GRID_HORIZONTAL_PADDING;
    private static final double PAGE_HEIGHT = WINDOW_HEIGHT - TITLE_BAR_HEIGHT - 24 - 20 - 1 - FOOTER_HEIGHT;

    private final Preferences preferences;
    private TacContent content;
    private StackPane rootContainer;
    private VBox riskPage, legalPage;
    private AutoTooltipButton backButton;
    private boolean isLegalPageVisible;

    @Inject
    public TacWindow(Preferences preferences) {
        this.preferences = preferences;
        type = Type.Attention;
        width = WINDOW_WIDTH;
    }

    @Override
    public void show() {
        rowIndex = -1;
        isLegalPageVisible = false;
        content = new TacContent(PAGE_WIDTH);
        actionButtonText(Res.get("tacWindow.risk.next"));
        closeButtonText(Res.get("tacWindow.disagree"));
        onClose(HavenoApp.getShutDownHandler());
        super.show();
    }

    @Override
    protected void createGridPane() {
        rootContainer = new StackPane();
        rootContainer.getStyleClass().add("tac-agreement-root");
        rootContainer.setPadding(new Insets(SHADOW_PADDING));
        rootContainer.setPrefWidth(width + 2 * SHADOW_PADDING);

        VBox windowContainer = new VBox();
        windowContainer.getStyleClass().add("tac-agreement-window");
        windowContainer.setPrefWidth(width);
        windowContainer.setMaxWidth(width);
        setFixedHeight(windowContainer, WINDOW_HEIGHT);

        gridPane = new GridPane();
        gridPane.setHgap(0);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10, 20, 14, 20));
        gridPane.setPrefWidth(width);

        ColumnConstraints columnConstraints = new ColumnConstraints();
        columnConstraints.setHgrow(Priority.ALWAYS);
        columnConstraints.setFillWidth(true);
        gridPane.getColumnConstraints().add(columnConstraints);

        windowContainer.getChildren().addAll(createTitleBar(), gridPane);

        // compact landscape logo above the card, matching the startup wizard branding
        ImageView logo = new ImageView();
        logo.setId("image-logo-splash-landscape");
        logo.setFitWidth(LOGO_FIT_WIDTH);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        GUIUtil.setBrandingLogo(logo, preferences, "/images/logo_splash_landscape_light_mode.png",
                "/images/logo_splash_landscape_dark_mode.png", LOGO_FIT_WIDTH, 0);

        VBox column = new VBox(16, logo, windowContainer);
        column.setAlignment(Pos.TOP_CENTER);
        rootContainer.getChildren().add(column);
    }

    @Override
    protected Region getRootContainer() {
        return rootContainer;
    }

    @Override
    protected void addHeadLine() {
    }

    @Override
    protected void addMessage() {
        riskPage = content.createRiskPage();
        setFixedHeight(riskPage, PAGE_HEIGHT);
        legalPage = content.createLegalPage();
        setFixedHeight(legalPage, PAGE_HEIGHT);

        StackPane pageContainer = new StackPane(riskPage, legalPage);
        pageContainer.setAlignment(Pos.TOP_LEFT);
        setFixedHeight(pageContainer, PAGE_HEIGHT);
        GridPane.setRowIndex(pageContainer, ++rowIndex);
        GridPane.setHgrow(pageContainer, Priority.ALWAYS);
        GridPane.setVgrow(pageContainer, Priority.ALWAYS);
        gridPane.getChildren().add(pageContainer);
    }

    @Override
    protected void addButtons() {
        Region separator = new Region();
        separator.getStyleClass().add("tac-agreement-footer-separator");
        GridPane.setHgrow(separator, Priority.ALWAYS);
        GridPane.setRowIndex(separator, ++rowIndex);
        gridPane.getChildren().add(separator);

        backButton = new AutoTooltipButton("< " + Res.get("tacWindow.legal.back"));
        backButton.getStyleClass().addAll("tac-agreement-secondary-button", "tac-agreement-back-button");
        backButton.setMinWidth(120);
        backButton.setFocusTraversable(false);
        backButton.setOnAction(event -> setLegalPageVisible(false));

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        closeButton = new AutoTooltipButton(closeButtonText);
        closeButton.getStyleClass().addAll("tac-agreement-secondary-button", "tac-agreement-reject-button");
        closeButton.setMinWidth(190);
        closeButton.setFocusTraversable(false);
        closeButton.setOnAction(event -> doClose());

        actionButton = new AutoTooltipButton(actionButtonText);
        actionButton.getStyleClass().addAll("action-button", "tac-agreement-action-button");
        actionButton.setMinWidth(190);
        actionButton.setDefaultButton(true);
        actionButton.setOnAction(event -> handleAction());

        HBox footer = new HBox(14, backButton, spacer, closeButton, actionButton);
        footer.getStyleClass().add("tac-agreement-footer");
        footer.setAlignment(Pos.CENTER_RIGHT);
        GridPane.setHgrow(footer, Priority.ALWAYS);
        GridPane.setRowIndex(footer, ++rowIndex);
        gridPane.getChildren().add(footer);

        setLegalPageVisible(false);
    }

    @Override
    protected void applyStyles() {
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                doClose();
            } else if (event.getCode() == KeyCode.ENTER && actionButton != null && !actionButton.isDisabled()) {
                event.consume();
                actionButton.fire();
            }
        });
    }

    @Override
    protected void setModality() {
        // non-modal so the app window behind stays resizable and its theme toggle clickable
        stage.initOwner(owner.getScene().getWindow());
        stage.initModality(Modality.NONE);
    }

    @Override
    protected void onShow() {
        display();
    }

    private BorderPane createTitleBar() {
        Label title = new Label(Res.get("tacWindow.headline"));
        title.getStyleClass().add("tac-agreement-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        BorderPane titleBar = new BorderPane(title);
        titleBar.getStyleClass().add("tac-agreement-title-bar");
        BorderPane.setAlignment(title, Pos.CENTER);
        return titleBar;
    }

    private void handleAction() {
        if (!isLegalPageVisible) {
            setLegalPageVisible(true);
        } else {
            hide();
            actionHandlerOptional.ifPresent(Runnable::run);
        }
    }

    private void setLegalPageVisible(boolean visible) {
        isLegalPageVisible = visible;
        riskPage.setManaged(!visible);
        riskPage.setVisible(!visible);
        legalPage.setManaged(visible);
        legalPage.setVisible(visible);
        actionButton.setText(visible ? Res.get("tacWindow.agree") : Res.get("tacWindow.risk.next"));
        backButton.setManaged(visible);
        backButton.setVisible(visible);
    }

    private void setFixedHeight(Region region, double height) {
        region.setMinHeight(height);
        region.setPrefHeight(height);
        region.setMaxHeight(height);
    }
}
