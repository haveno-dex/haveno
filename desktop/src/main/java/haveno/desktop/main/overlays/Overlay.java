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

package haveno.desktop.main.overlays;

import com.google.common.reflect.TypeToken;
import haveno.desktop.util.GlyphsDude;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.util.Utilities;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.LanguageUtil;
import haveno.core.locale.Res;
import haveno.core.user.DontShowAgainLookup;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.main.MainView;
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.CssTheme;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import haveno.desktop.util.Transitions;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static javafx.scene.input.MouseEvent.MOUSE_CLICKED;
import static javafx.scene.input.MouseEvent.MOUSE_PRESSED;

@Slf4j
public abstract class Overlay<T extends Overlay<T>> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enum
    ///////////////////////////////////////////////////////////////////////////////////////////

    private enum AnimationType {
        FadeInAtCenter,
        SlideDownFromCenterTop,
        SlideFromRightTop,
        ScaleDownToCenter,
        ScaleFromCenter,
        ScaleYFromCenter
    }

    private enum ChangeBackgroundType {
        BlurLight,
        BlurUltraLight,
        Darken
    }

    protected enum Type {
        Undefined(AnimationType.ScaleFromCenter, ChangeBackgroundType.BlurLight),

        Notification(AnimationType.SlideFromRightTop, ChangeBackgroundType.BlurLight),

        BackgroundInfo(AnimationType.SlideDownFromCenterTop, ChangeBackgroundType.BlurUltraLight),
        Feedback(AnimationType.SlideDownFromCenterTop, ChangeBackgroundType.BlurLight),

        Information(AnimationType.FadeInAtCenter, ChangeBackgroundType.BlurLight),
        Instruction(AnimationType.ScaleFromCenter, ChangeBackgroundType.BlurLight),
        Attention(AnimationType.ScaleFromCenter, ChangeBackgroundType.BlurLight),
        Confirmation(AnimationType.ScaleYFromCenter, ChangeBackgroundType.BlurLight),

        Warning(AnimationType.ScaleDownToCenter, ChangeBackgroundType.BlurLight),
        Error(AnimationType.ScaleDownToCenter, ChangeBackgroundType.BlurLight);

        public final AnimationType animationType;
        public final ChangeBackgroundType changeBackgroundType;

        Type(AnimationType animationType, ChangeBackgroundType changeBackgroundType) {
            this.animationType = animationType;
            this.changeBackgroundType = changeBackgroundType;
        }
    }

    private static int numCenterOverlays = 0;
    private static int numBlurEffects = 0;

    protected final static double DEFAULT_WIDTH = 800;
    private final static double CARD_INSET = 44; // shadow margin around the card, matching .popup-bg -fx-background-insets
    private final static double CAP_MARGIN = 15; // band of the app left visible around capped popups
    protected Stage stage;
    protected GridPane gridPane;
    protected Pane owner;

    protected int rowIndex = -1;
    protected double width = DEFAULT_WIDTH;
    protected double buttonDistance = 20;

    protected boolean showReportErrorButtons;
    private boolean showBusyAnimation;
    protected boolean hideCloseButton;
    protected boolean isDisplayed;
    protected boolean disableActionButton;

    @Getter
    protected BooleanProperty isHiddenProperty = new SimpleBooleanProperty();

    // Used when a priority queue is used for displaying order of popups. Higher numbers mean lower priority
    @Setter
    @Getter
    protected Integer displayOrderPriority = Integer.MAX_VALUE;

    protected boolean useAnimation = true;
    protected boolean showScrollPane = false;
    private boolean cappedToScreen;
    private StackPane capShell;

    protected TextArea messageTextArea;
    protected Label headlineIcon, copyLabel, headLineLabel;
    protected String headLine, message, closeButtonText, actionButtonText,
            secondaryActionButtonText, dontShowAgainId, dontShowAgainText,
            truncatedMessage;
    private ArrayList<String> messageHyperlinks;
    private String headlineStyle;
    protected Button actionButton, secondaryActionButton;
    private HBox buttonBox;
    protected AutoTooltipButton closeButton;
    protected ScrollPane scrollPane;

    private HPos buttonAlignment = HPos.RIGHT;

    protected Optional<Runnable> closeHandlerOptional = Optional.<Runnable>empty();
    protected Optional<Runnable> actionHandlerOptional = Optional.empty();
    protected Optional<Runnable> secondaryActionHandlerOptional = Optional.<Runnable>empty();
    protected ChangeListener<Number> positionListener;
    private ListChangeListener<String> stylesheetsListener;
    private EventHandler<InputEvent> ownerInputFilter;
    private ChangeListener<Boolean> contentDemandListener;
    private double lastContentDemand;
    private AnimationTimer displayTimer;
    private final Timeline animation = new Timeline();
    private boolean hiding;

    protected Timer centerTime;
    protected Type type = Type.Undefined;

    protected int maxChar = 2200;

    private T cast() {
        //noinspection unchecked
        return (T) this;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Overlay() {
        //noinspection UnstableApiUsage
        TypeToken<T> typeToken = new TypeToken<>(getClass()) {
        };
        if (!typeToken.isSupertypeOf(getClass())) {
            throw new RuntimeException("Subclass of Overlay<T> should be castable to T");
        }
    }

    public void show(boolean showAgainChecked) {
        if (dontShowAgainId == null || DontShowAgainLookup.showAgain(dontShowAgainId)) {
            createGridPane();
            if (LanguageUtil.isDefaultLanguageRTL())
                getRootContainer().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

            addHeadLine();

            if (showBusyAnimation)
                addBusyAnimation();

            addMessage();
            if (showReportErrorButtons)
                addReportErrorButtons();

            addButtons();
            addDontShowAgainCheckBox(showAgainChecked);
            applyStyles();
            onShow();
        }
    }

    public void show() {
        this.show(false);
    }

    protected void onShow() {
    }

    public void hide() {
        if (gridPane != null) {
            animateHide();
        }
        isDisplayed = false;
        isHiddenProperty.set(true);
    }

    protected void animateHide() {
        if (hiding) return;
        hiding = true;
        if (displayTimer != null) {
            displayTimer.stop();
            displayTimer = null;
        }
        animateHide(() -> {
            if (stage != null && stage.isShowing()) {
                if (isCentered()) numCenterOverlays--;
                removeEffectFromBackground();
                stage.hide();
            }
            isDisplayed = false;

            removeListeners();
            cleanup();
            onHidden();
        });
    }

    protected void onHidden() {
    }

    // subclass teardown hook; base teardown runs unconditionally in removeListeners()
    protected void cleanup() {
    }

    private void removeListeners() {
        if (centerTime != null)
            centerTime.stop();

        if (contentDemandListener != null)
            getRootContainer().needsLayoutProperty().removeListener(contentDemandListener);

        if (owner == null)
            owner = MainView.getRootContainer();
        Scene rootScene = owner != null ? owner.getScene() : null;
        if (rootScene != null) {
            if (stylesheetsListener != null)
                rootScene.getStylesheets().removeListener(stylesheetsListener);
            if (ownerInputFilter != null)
                rootScene.removeEventFilter(InputEvent.ANY, ownerInputFilter);
            Window window = rootScene.getWindow();
            if (window != null && positionListener != null) {
                window.xProperty().removeListener(positionListener);
                window.yProperty().removeListener(positionListener);
                window.widthProperty().removeListener(positionListener);
                window.heightProperty().removeListener(positionListener);
            }
        }
    }

    public T onClose(Runnable closeHandler) {
        this.closeHandlerOptional = Optional.of(closeHandler);
        return cast();
    }

    public T onAction(Runnable actionHandler) {
        this.actionHandlerOptional = Optional.of(actionHandler);
        return cast();
    }

    public T onSecondaryAction(Runnable secondaryActionHandlerOptional) {
        this.secondaryActionHandlerOptional = Optional.of(secondaryActionHandlerOptional);
        return cast();
    }

    public T headLine(String headLine) {
        this.headLine = headLine;
        return cast();
    }

    public T notification(String message) {
        type = Type.Notification;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.notification");
        preProcessMessage(message);
        return cast();
    }

    public T instruction(String message) {
        type = Type.Instruction;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.instruction");
        preProcessMessage(message);
        return cast();
    }

    public T attention(String message) {
        type = Type.Attention;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.attention");
        preProcessMessage(message);
        return cast();
    }

    public T backgroundInfo(String message) {
        type = Type.BackgroundInfo;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.backgroundInfo");
        preProcessMessage(message);
        return cast();
    }

    public T feedback(String message) {
        type = Type.Feedback;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.feedback");
        preProcessMessage(message);
        return cast();
    }

    public T confirmation(String message) {
        type = Type.Confirmation;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.confirmation");
        preProcessMessage(message);
        return cast();
    }

    public T information(String message) {
        type = Type.Information;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.information");
        preProcessMessage(message);
        return cast();
    }

    public T warning(String message) {
        type = Type.Warning;

        if (headLine == null)
            this.headLine = Res.get("popup.headline.warning");
        preProcessMessage(message);
        return cast();
    }

    public T error(String message) {
        type = Type.Error;
        showReportErrorButtons();
        width = 1100;
        if (headLine == null)
            this.headLine = Res.get("popup.headline.error");
        preProcessMessage(message);
        return cast();
    }

    @SuppressWarnings("UnusedReturnValue")
    public T showReportErrorButtons() {
        this.showReportErrorButtons = true;
        return cast();
    }

    public T message(String message) {
        preProcessMessage(message);
        return cast();
    }

    public T closeButtonText(String closeButtonText) {
        this.closeButtonText = closeButtonText;
        return cast();
    }

    public T useReportBugButton() {
        this.closeButtonText = Res.get("shared.reportBug");
        this.closeHandlerOptional = Optional.of(() -> GUIUtil.openWebPage("https://github.com/haveno-dex/haveno/issues"));
        return cast();
    }

    public T useIUnderstandButton() {
        this.closeButtonText = Res.get("shared.iUnderstand");
        return cast();
    }

    public T actionButtonTextWithGoTo(String target) {
        this.actionButtonText = Res.get("shared.goTo", Res.get(target));
        return cast();
    }

    public T secondaryActionButtonTextWithGoTo(String target) {
        this.secondaryActionButtonText = Res.get("shared.goTo", Res.get(target));
        return cast();
    }

    public T closeButtonTextWithGoTo(String target) {
        this.closeButtonText = Res.get("shared.goTo", Res.get(target));
        return cast();
    }

    public T actionButtonText(String actionButtonText) {
        this.actionButtonText = actionButtonText;
        return cast();
    }

    public T secondaryActionButtonText(String secondaryActionButtonText) {
        this.secondaryActionButtonText = secondaryActionButtonText;
        return cast();
    }

    public T useShutDownButton() {
        this.actionButtonText = Res.get("shared.shutDown");
        this.actionHandlerOptional = Optional.ofNullable(HavenoApp.getShutDownHandler());
        return cast();
    }

    public T buttonAlignment(HPos pos) {
        this.buttonAlignment = pos;
        return cast();
    }

    public T width(double width) {
        this.width = width;
        return cast();
    }

    public T maxMessageLength(int maxChar) {
        this.maxChar = maxChar;
        return cast();
    }

    public T showBusyAnimation() {
        this.showBusyAnimation = true;
        return cast();
    }

    public T dontShowAgainId(String key) {
        this.dontShowAgainId = key;
        return cast();
    }

    public T dontShowAgainText(String dontShowAgainText) {
        this.dontShowAgainText = dontShowAgainText;
        return cast();
    }

    public T hideCloseButton() {
        this.hideCloseButton = true;
        return cast();
    }

    public T useAnimation(boolean useAnimation) {
        this.useAnimation = useAnimation;
        return cast();
    }

    public T setHeadlineStyle(String headlineStyle) {
        this.headlineStyle = headlineStyle;
        return cast();
    }

    public T disableActionButton() {
        this.disableActionButton = true;
        return cast();
    }

    public T showScrollPane() {
        this.showScrollPane = true;
        return cast();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void createGridPane() {
        gridPane = new GridPane();
        gridPane.setHgap(5);
        gridPane.setVgap(5);
        gridPane.setPadding(new Insets(64, 64, 64, 64));
        gridPane.setPrefWidth(width);
        gridPane.setMaxHeight(Layout.MAX_POPUP_HEIGHT);

        ColumnConstraints columnConstraints1 = new ColumnConstraints();
        columnConstraints1.setHalignment(HPos.RIGHT);
        columnConstraints1.setHgrow(Priority.SOMETIMES);
        ColumnConstraints columnConstraints2 = new ColumnConstraints();
        columnConstraints2.setHgrow(Priority.ALWAYS);
        gridPane.getColumnConstraints().addAll(columnConstraints1, columnConstraints2);
    }

    protected void blurAgain() {
        UserThread.runAfter(MainView::blurLight, Transitions.DEFAULT_DURATION, TimeUnit.MILLISECONDS);
    }

    public void display() {
        if (isDisplayed) return;
        if (owner == null)
            owner = MainView.getRootContainer();

        if (owner != null) {
            Scene rootScene = owner.getScene();
            if (rootScene != null) {
                isDisplayed = true;
                hiding = false;
                UserThread.execute(() -> {
                    if (hiding) return;
                    Scene scene = new Scene(getRootContainer());
                    scene.getStylesheets().setAll(rootScene.getStylesheets());
                    stylesheetsListener = change -> scene.getStylesheets().setAll(rootScene.getStylesheets());
                    rootScene.getStylesheets().addListener(stylesheetsListener); // re-theme the overlay if the css theme changes (light/dark) while it is showing
                    scene.setFill(Color.TRANSPARENT);

                    setupKeyHandler(scene);

                    stage = new Stage();
                    Window window = rootScene.getWindow();
                    // window name for screen readers, falling back to the app title
                    String ownerTitle = window instanceof Stage ? ((Stage) window).getTitle() : null;
                    stage.setTitle(headLine != null ? headLine : ownerTitle != null ? ownerTitle : "Haveno");
                    stage.setScene(scene);
                    setModality();
                    stage.initStyle(StageStyle.TRANSPARENT);
                    stage.setOnCloseRequest(event -> {
                        event.consume();
                        doClose();
                    });
                    getRootContainer().setOpacity(1); // render the complete card while the native window is hidden
                    stage.setOpacity(0); // hide the native window too, else it can flash white before the first frame renders
                    stage.sizeToScene();
                    stage.show();
                    constrainToScreen(scene);

                    // focus the message, not the headline copy icon, so screen readers announce it first
                    if (messageTextArea != null) messageTextArea.requestFocus();

                    layout();

                    // add dropshadow if light mode or multiple centered overlays
                    if (isCentered()) {
                        numCenterOverlays++;
                    }
                    if (!CssTheme.isDarkTheme() || numCenterOverlays > 1) {
                        getDisplayContainer().getStyleClass().add("popup-dropshadow");
                    }

                    addEffectToBackground();

                    // Re-center as the owner window moves and re-fit as it resizes or once movement settles: on Linux the
                    // owner stage does not move the child stage, on Mac popups sometimes end up outside the app.
                    positionListener = (observable, oldValue, newValue) -> {
                        if (stage != null) {
                            boolean resized = observable == window.widthProperty() || observable == window.heightProperty();
                            if (resized) refitToContent(); else layout();
                            if (centerTime != null)
                                centerTime.stop();

                            centerTime = UserThread.runAfter(this::refitToContent, 3);
                        }
                    };
                    window.xProperty().addListener(positionListener);
                    window.yProperty().addListener(positionListener);
                    window.widthProperty().addListener(positionListener);
                    window.heightProperty().addListener(positionListener);

                    // content can settle taller after the initial fit (fonts, styled rows), so watch
                    // layout requests for the popup's lifetime and re-fit when its height demand changes
                    lastContentDemand = 0;
                    contentDemandListener = (observable, oldValue, needsLayout) -> {
                        if (needsLayout) UserThread.execute(this::refitIfDemandChanged);
                    };
                    getRootContainer().needsLayoutProperty().addListener(contentDemandListener);

                    // settle CSS and wrapping before rendering the entrance pose, then reveal it on the next pulse
                    Stage displayedStage = stage;
                    displayTimer = new AnimationTimer() {
                        private int frames;
                        private int stableFrames;
                        private double lastWidth;
                        private double lastHeight;
                        private double lastDemand;
                        private boolean prepared;

                        @Override
                        public void handle(long now) {
                            if (hiding || stage != displayedStage || !displayedStage.isShowing()) {
                                stop();
                                return;
                            }
                            if (prepared) {
                                stop();
                                displayTimer = null;
                                animateDisplay();
                                return;
                            }

                            scene.getRoot().applyCss();
                            scene.getRoot().layout();
                            refitToContent();
                            double width = displayedStage.getWidth();
                            double height = displayedStage.getHeight();
                            double demand = getRootContainer().prefHeight(getRootContainer().getWidth());
                            boolean stable = Math.abs(width - lastWidth) < 0.5 &&
                                    Math.abs(height - lastHeight) < 0.5 && Math.abs(demand - lastDemand) < 0.5;
                            stableFrames = stable ? stableFrames + 1 : 0;
                            lastWidth = width;
                            lastHeight = height;
                            lastDemand = demand;
                            if (++frames >= 10 || stableFrames >= 2) {
                                prepareDisplayAnimation();
                                prepared = true;
                            }
                        }
                    };
                    displayTimer.start();
                });
            }
        }
    }

    protected Region getRootContainer() {
        return gridPane;
    }

    // the outermost visible popup node: the scroll shell when capped, else the content itself
    protected Region getDisplayContainer() {
        return capShell != null ? capShell : getRootContainer();
    }

    // cap the stage to the app window, screen and max popup height, wrapping the content in a scroll pane so oversized popups scroll instead
    private void constrainToScreen(Scene scene) {
        cappedToScreen = false;
        capShell = null;
        double maxWidth = maxPopupWidth();
        double maxHeight = maxPopupHeight();
        // budget the whole stage against the viewport: subclass styling (e.g. grid-pane's background)
        // can paint well outside the standard card inset, so no invisible-margin grace is assumed
        Region rootContainer = getRootContainer();
        double stageWidth = rootContainer.prefWidth(-1);
        double stageHeight = rootContainer.prefHeight(stageWidth);
        if (stageWidth <= maxWidth + 2 * CAP_MARGIN && stageHeight <= maxHeight + 2 * CAP_MARGIN) return;

        ScrollPane scrollRoot = new ScrollPane();
        scrollRoot.getStyleClass().add("popup-scroll-pane");
        scrollRoot.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT); // keep the scroll bar at the styled right edge; content keeps its own orientation
        scrollRoot.setFitToWidth(true);
        scrollRoot.setFocusTraversable(false);

        // the shell paints the card with the bar inside it; the popup-bg and dropshadow styles move
        // along to keep their text styling, while the shell style flattens their insets and shadow
        capShell = new StackPane(scrollRoot);
        capShell.getStyleClass().add("popup-scroll-shell");
        for (String style : new String[]{"popup-bg", "popup-bg-top", "popup-dropshadow"})
            if (rootContainer.getStyleClass().remove(style)) capShell.getStyleClass().add(style);
        rootContainer.setTranslateY(0); // clear the top-anchor settle offset if the cap engages mid-display
        // the card edge replaces the shadow margin, so drop it from the content's padding to keep the normal card-edge distance
        Insets padding = rootContainer.getPadding();
        rootContainer.setPadding(new Insets(
                Math.max(0, padding.getTop() - CARD_INSET),
                Math.max(0, padding.getRight() - CARD_INSET),
                Math.max(0, padding.getBottom() - CARD_INSET),
                Math.max(0, padding.getLeft() - CARD_INSET)));
        // a slim transparent frame around the card leaves room for its shadow, so it still reads as a card;
        // inline style beats the .root background every scene root gets painted with
        StackPane shadowFrame = new StackPane(capShell);
        shadowFrame.setPadding(new Insets(CAP_MARGIN));
        shadowFrame.setStyle("-fx-background-color: transparent;");
        scene.setRoot(shadowFrame);
        scrollRoot.setContent(rootContainer);
        capShell.setPrefSize(Math.min(stage.getWidth() - 2 * CARD_INSET, maxWidth), Math.min(stage.getHeight() - 2 * CARD_INSET, maxHeight));
        stage.sizeToScene();
        cappedToScreen = true;
    }

    // budget against the owner's scene, not the owner region: a region can overflow its viewport
    private double maxPopupWidth() {
        Rectangle2D screenBounds = getScreenBounds();
        Scene ownerScene = owner.getScene();
        double ownerWidth = ownerScene != null && ownerScene.getWidth() > 0 ? ownerScene.getWidth() : screenBounds.getWidth();
        return Math.min(screenBounds.getWidth(), ownerWidth) - 2 * CAP_MARGIN;
    }

    private double maxPopupHeight() {
        Rectangle2D screenBounds = getScreenBounds();
        Scene ownerScene = owner.getScene();
        double ownerHeight = ownerScene != null && ownerScene.getHeight() > 0 ? ownerScene.getHeight() : screenBounds.getHeight();
        return Math.min(Math.min(screenBounds.getHeight(), ownerHeight) - 2 * CAP_MARGIN, Layout.MAX_POPUP_HEIGHT);
    }

    // re-fit the stage to the content and the cap budget: engage the cap once the content outgrows
    // the budget, else track both so a capped popup resizes with its content and the owner window
    private void refitToContent() {
        if (capShell == null) {
            constrainToScreen(stage.getScene()); // no-op while the content still fits
        } else {
            Region rootContainer = getRootContainer();
            double cardWidth = Math.max(rootContainer.prefWidth(-1), rootContainer.minWidth(-1)) - 2 * CARD_INSET;
            capShell.setPrefSize(Math.min(cardWidth, maxPopupWidth()),
                    Math.min(rootContainer.prefHeight(rootContainer.getWidth()), maxPopupHeight()));
        }
        // keep the display animation's transient root translate out of the stage size (sizeToScene adds it)
        Parent sceneRoot = stage.getScene().getRoot();
        double translateX = sceneRoot.getTranslateX();
        double translateY = sceneRoot.getTranslateY();
        sceneRoot.setTranslateX(0);
        sceneRoot.setTranslateY(0);
        stage.sizeToScene();
        sceneRoot.setTranslateX(translateX);
        sceneRoot.setTranslateY(translateY);
        layout();
    }

    // re-fit only when the content's height demand actually changed, so refit-triggered
    // layout passes cannot re-schedule themselves forever
    private void refitIfDemandChanged() {
        if (stage == null || !stage.isShowing()) return;
        Region rootContainer = getRootContainer();
        double demand = rootContainer.prefHeight(rootContainer.prefWidth(-1));
        if (Math.abs(demand - lastContentDemand) < 0.5) return;
        lastContentDemand = demand;
        refitToContent();
    }

    private Rectangle2D getScreenBounds() {
        Window window = owner.getScene().getWindow();
        return Screen.getScreensForRectangle(window.getX(), window.getY(), window.getWidth(), window.getHeight())
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
    }


    protected void setupKeyHandler(Scene scene) {
        if (!hideCloseButton) {
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.ENTER) {
                    e.consume();
                    doClose();
                }
            });
        }
    }

    private void prepareDisplayAnimation() {
        Region rootContainer = getDisplayContainer();
        boolean animate = getDuration(240) > 1;
        double scale = animate ? (type.animationType == AnimationType.ScaleDownToCenter ? 1.02 : 0.98) : 1;
        double translateX = 0;
        double translateY = 0;
        if (type.animationType == AnimationType.SlideFromRightTop) {
            scale = 1;
            translateX = animate ? 16 : 0;
        } else if (type.animationType == AnimationType.SlideDownFromCenterTop) {
            scale = 1;
            translateY = (capShell != null ? 0 : -50) - (animate ? 8 : 0);
        }
        rootContainer.setScaleX(scale);
        rootContainer.setScaleY(scale);
        rootContainer.setTranslateX(translateX);
        rootContainer.setTranslateY(translateY);
    }

    protected void animateDisplay() {
        if (getDuration(240) <= 1) {
            prepareDisplayAnimation();
            stage.setOpacity(1);
            return;
        }
        double translateY = type.animationType == AnimationType.SlideDownFromCenterTop && capShell == null ? -50 : 0;
        playAnimation(0, translateY, 1, 1, getDuration(240), Interpolator.SPLINE(0.2, 0, 0.2, 1), null);
    }

    protected void animateHide(Runnable onFinishedHandler) {
        if (stage == null || stage.getOpacity() == 0 || getDuration(160) <= 1) {
            animation.stop();
            onFinishedHandler.run();
            return;
        }

        if (type.animationType == AnimationType.SlideFromRightTop) {
            Region rootContainer = getDisplayContainer();
            playAnimation(rootContainer.getTranslateX(), rootContainer.getTranslateY(), 1, 0, getDuration(180),
                    Interpolator.SPLINE(0.4, 0, 0.2, 1), onFinishedHandler);
            return;
        }

        double translateY = 0;
        double scale = 0.98;
        if (type.animationType == AnimationType.SlideDownFromCenterTop) {
            scale = 1;
            translateY = (capShell != null ? 0 : -50) - 8;
        }
        playAnimation(0, translateY, scale, 0, getDuration(160),
                Interpolator.SPLINE(0.4, 0, 1, 1), onFinishedHandler);
    }

    private void playAnimation(double translateX, double translateY, double scale, double opacity, double duration,
                               Interpolator interpolator, Runnable onFinishedHandler) {
        // keep the content opaque and fade the native window as a whole, continuing from the current pose
        Region rootContainer = getDisplayContainer();
        double startX = rootContainer.getTranslateX();
        double startY = rootContainer.getTranslateY();
        double startScale = rootContainer.getScaleX();
        double startOpacity = stage.getOpacity();
        animation.stop();
        rootContainer.setTranslateX(startX);
        rootContainer.setTranslateY(startY);
        rootContainer.setScaleX(startScale);
        rootContainer.setScaleY(startScale);
        stage.setOpacity(startOpacity);
        animation.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(stage.opacityProperty(), startOpacity),
                        new KeyValue(rootContainer.translateXProperty(), startX),
                        new KeyValue(rootContainer.translateYProperty(), startY),
                        new KeyValue(rootContainer.scaleXProperty(), startScale),
                        new KeyValue(rootContainer.scaleYProperty(), startScale)),
                new KeyFrame(Duration.millis(duration),
                        new KeyValue(stage.opacityProperty(), opacity, interpolator),
                        new KeyValue(rootContainer.translateXProperty(), translateX, interpolator),
                        new KeyValue(rootContainer.translateYProperty(), translateY, interpolator),
                        new KeyValue(rootContainer.scaleXProperty(), scale, interpolator),
                        new KeyValue(rootContainer.scaleYProperty(), scale, interpolator)));
        animation.setOnFinished(event -> {
            if (onFinishedHandler != null) onFinishedHandler.run();
        });
        animation.play();
    }

    protected void layout() {
        if (owner == null)
            owner = MainView.getRootContainer();
        Scene rootScene = owner.getScene();
        if (rootScene != null) {
            Window window = rootScene.getWindow();
            double titleBarHeight = window.getHeight() - rootScene.getHeight();
            if (Utilities.isWindows())
                titleBarHeight -= 9;
            stage.setX(Math.round(window.getX() + (rootScene.getWidth() - stage.getWidth()) / 2));

            if (type.animationType == AnimationType.SlideDownFromCenterTop)
                stage.setY(Math.round(window.getY() + titleBarHeight));
            else
                stage.setY(Math.round(window.getY() + titleBarHeight + (rootScene.getHeight() - stage.getHeight()) / 2));

            // a popup capped to the screen must stay fully on it, even when centering on the owner would push it off
            if (cappedToScreen) {
                Rectangle2D screenBounds = getScreenBounds();
                stage.setX(Math.max(screenBounds.getMinX(), Math.min(stage.getX(), screenBounds.getMaxX() - stage.getWidth())));
                stage.setY(Math.max(screenBounds.getMinY(), Math.min(stage.getY(), screenBounds.getMaxY() - stage.getHeight())));
            }
        }
    }

    protected void addEffectToBackground() {
        numBlurEffects++;
        if (numBlurEffects > 1) return;
        if (type.changeBackgroundType == ChangeBackgroundType.BlurUltraLight)
            MainView.blurUltraLight();
        else if (type.changeBackgroundType == ChangeBackgroundType.BlurLight)
            MainView.blurLight();
        else
            MainView.darken();
    }


    protected void applyStyles() {
        Region rootContainer = getRootContainer();
        if (type.animationType == AnimationType.SlideDownFromCenterTop) {
            rootContainer.getStyleClass().add("popup-bg-top");
        } else {
            rootContainer.getStyleClass().add("popup-bg");
        }


        if (headLineLabel != null) {
            if (copyLabel != null) {
                copyLabel.getStyleClass().add("popup-icon-information");
                copyLabel.setManaged(true);
                copyLabel.setVisible(true);
                Text copyIcon = GlyphsDude.createIcon(MaterialDesignIcon.CONTENT_COPY, "1.2em");
                copyLabel.setGraphic(copyIcon);
                copyLabel.setCursor(Cursor.HAND);
                copyLabel.addEventHandler(MOUSE_CLICKED, mouseEvent -> {
                    if (message != null) {
                        Utilities.copyToClipboard(getClipboardText());
                        Tooltip tp = new Tooltip(Res.get("shared.copiedToClipboard"));
                        Node node = (Node) mouseEvent.getSource();
                        UserThread.runAfter(() -> tp.hide(), 1);
                        tp.show(node, mouseEvent.getScreenX() + Layout.PADDING, mouseEvent.getScreenY() + Layout.PADDING);
                    }
                });
                Accessibility.asButton(copyLabel, Res.get("shared.copyToClipboard"));
            }

            switch (type) {
                case Information:
                case BackgroundInfo:
                case Instruction:
                case Confirmation:
                case Feedback:
                case Notification:
                case Attention:
                    headLineLabel.getStyleClass().add("popup-headline-information");
                    headlineIcon.getStyleClass().add("popup-icon-information");
                    headlineIcon.setManaged(true);
                    headlineIcon.setVisible(true);
                    FormBuilder.getIconForLabel(FontAwesomeIcon.INFO_CIRCLE, headlineIcon, "1.5em");
                    break;
                case Warning:
                case Error:
                    headLineLabel.getStyleClass().add("popup-headline-warning");
                    headlineIcon.getStyleClass().add("popup-icon-warning");
                    headlineIcon.setManaged(true);
                    headlineIcon.setVisible(true);
                    FormBuilder.getIconForLabel(FontAwesomeIcon.EXCLAMATION_CIRCLE, headlineIcon, "1.5em");
                    break;
                default:
                    headLineLabel.getStyleClass().add("popup-headline");
            }
        }
    }

    protected void setModality() {
        stage.initOwner(owner.getScene().getWindow());
        stage.initModality(Modality.NONE); // non-modal keeps the owner window movable and resizable
        // emulate modality by blocking the owner's input, bouncing focus back to the popup on click
        ownerInputFilter = event -> {
            event.consume();
            if (event.getEventType() == MOUSE_PRESSED && stage != null) stage.requestFocus();
        };
        owner.getScene().addEventFilter(InputEvent.ANY, ownerInputFilter);
    }

    protected void removeEffectFromBackground() {
        numBlurEffects--;
        if (numBlurEffects > 0) return;
        MainView.removeEffect();
    }

    protected void addHeadLine() {
        if (headLine != null) {
            ++rowIndex;

            HBox hBox = new HBox();
            hBox.setSpacing(7);
            headLineLabel = new AutoTooltipLabel(headLine);
            headlineIcon = new Label();
            headlineIcon.setManaged(false);
            headlineIcon.setVisible(false);
            headlineIcon.setPadding(new Insets(3));
            headLineLabel.setMouseTransparent(true);

            if (headlineStyle != null)
                headLineLabel.setStyle(headlineStyle);

            if (message != null) {
                copyLabel = new Label();
                copyLabel.setManaged(false);
                copyLabel.setVisible(false);
                copyLabel.setPadding(new Insets(3));
                copyLabel.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
                final Pane spacer = new Pane();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                spacer.setMinSize(Layout.PADDING, 1);
                hBox.getChildren().addAll(headlineIcon, headLineLabel, spacer, copyLabel);
            } else {
                hBox.getChildren().addAll(headlineIcon, headLineLabel);
            }

            GridPane.setHalignment(hBox, HPos.LEFT);
            GridPane.setRowIndex(hBox, rowIndex);
            GridPane.setColumnSpan(hBox, 2);
            gridPane.getChildren().addAll(hBox);
        }
    }

    protected void addMessage() {
        if (message != null) {
            messageTextArea = new TextArea(truncatedMessage);
            messageTextArea.setEditable(false);
            messageTextArea.getStyleClass().add("text-area-popup");
            GUIUtil.adjustHeightAutomatically(messageTextArea);
            messageTextArea.setWrapText(true);

            Region messageRegion;
            if (showScrollPane) {
                scrollPane = new ScrollPane(messageTextArea);
                scrollPane.setHbarPolicy(ScrollBarPolicy.NEVER);
                scrollPane.setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
                scrollPane.setFitToWidth(true);

                messageRegion = scrollPane;
            } else
                messageRegion = messageTextArea;

            GridPane.setHalignment(messageRegion, HPos.LEFT);
            GridPane.setHgrow(messageRegion, Priority.ALWAYS);
            GridPane.setMargin(messageRegion, new Insets(3, 0, 0, 0));
            GridPane.setRowIndex(messageRegion, ++rowIndex);
            GridPane.setColumnIndex(messageRegion, 0);
            GridPane.setColumnSpan(messageRegion, 2);
            gridPane.getChildren().add(messageRegion);

            addFooter();
        }
    }

    // footer contains optional hyperlinks extracted from the message
    private void addFooter() {
        if (messageHyperlinks != null && messageHyperlinks.size() > 0) {
            VBox footerBox = new VBox();
            GridPane.setRowIndex(footerBox, ++rowIndex);
            GridPane.setColumnSpan(footerBox, 2);
            GridPane.setMargin(footerBox, new Insets(buttonDistance, 0, 0, 0));
            gridPane.getChildren().add(footerBox);
            for (int i = 0; i < messageHyperlinks.size(); i++) {
                Label label = new Label(String.format("[%d]", i + 1));
                Hyperlink link = new Hyperlink(messageHyperlinks.get(i));
                link.setOnAction(event -> GUIUtil.openWebPageNoPopup(link.getText()));
                footerBox.getChildren().addAll(new HBox(label, link));
            }
        }
    }

    private void addReportErrorButtons() {
        messageTextArea.setText(Res.get("popup.reportError", truncatedMessage));

        Button logButton = new AutoTooltipButton(Res.get("popup.reportError.log"));
        GridPane.setMargin(logButton, new Insets(20, 0, 0, 0));
        GridPane.setHalignment(logButton, HPos.LEFT);
        GridPane.setRowIndex(logButton, ++rowIndex);
        gridPane.getChildren().add(logButton);
        logButton.setOnAction(event -> {
            try {
                File dataDir = Config.appDataDir();
                File logFile = new File(dataDir, "haveno.log");
                Utilities.openFile(logFile);
            } catch (IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }
        });

        Button gitHubButton = new AutoTooltipButton(Res.get("popup.reportError.gitHub"));
        GridPane.setHalignment(gitHubButton, HPos.RIGHT);
        GridPane.setRowIndex(gitHubButton, ++rowIndex);
        gridPane.getChildren().add(gitHubButton);
        gitHubButton.setOnAction(event -> {
            if (message != null)
                Utilities.copyToClipboard(message);
            GUIUtil.openWebPage("https://github.com/haveno-dex/haveno/issues");
            hide();
        });
    }

    protected void addBusyAnimation() {
        BusyAnimation busyAnimation = new BusyAnimation();
        GridPane.setHalignment(busyAnimation, HPos.CENTER);
        GridPane.setRowIndex(busyAnimation, ++rowIndex);
        GridPane.setColumnSpan(busyAnimation, 2);
        gridPane.getChildren().add(busyAnimation);
    }

    protected void addDontShowAgainCheckBox(boolean isChecked) {
        if (dontShowAgainId != null) {
            // We might have set it and overridden the default, so we check if it is not set
            if (dontShowAgainText == null)
                dontShowAgainText = Res.get("popup.doNotShowAgain");

            CheckBox dontShowAgainCheckBox = new AutoTooltipCheckBox(dontShowAgainText);
            HBox.setHgrow(dontShowAgainCheckBox, Priority.NEVER);
            buttonBox.getChildren().add(0, dontShowAgainCheckBox);

            dontShowAgainCheckBox.setSelected(isChecked);
            DontShowAgainLookup.dontShowAgain(dontShowAgainId, isChecked);
            dontShowAgainCheckBox.setOnAction(e -> DontShowAgainLookup.dontShowAgain(dontShowAgainId, dontShowAgainCheckBox.isSelected()));
        }
    }

    protected void addDontShowAgainCheckBox() {
        this.addDontShowAgainCheckBox(false);
    }

    protected void addButtons() {
        if (!hideCloseButton) {
            closeButton = new AutoTooltipButton(closeButtonText == null ? Res.get("shared.close") : closeButtonText);
            closeButton.getStyleClass().add("compact-button");
            closeButton.setOnAction(event -> doClose());
            closeButton.setMinWidth(70);
            HBox.setHgrow(closeButton, Priority.SOMETIMES);
        }

        Pane spacer = new Pane();

        if (buttonAlignment == HPos.RIGHT) {
            HBox.setHgrow(spacer, Priority.ALWAYS);
            spacer.setMaxWidth(Double.MAX_VALUE);
        }

        buttonBox = new HBox();

        GridPane.setHalignment(buttonBox, buttonAlignment);
        GridPane.setRowIndex(buttonBox, ++rowIndex);
        GridPane.setColumnSpan(buttonBox, 2);
        GridPane.setMargin(buttonBox, new Insets(buttonDistance, 0, 0, 0));
        gridPane.getChildren().add(buttonBox);

        if (actionHandlerOptional.isPresent() || actionButtonText != null) {
            actionButton = new AutoTooltipButton(actionButtonText == null ? Res.get("shared.ok") : actionButtonText);

            if (!disableActionButton)
                actionButton.setDefaultButton(true);
            else
                actionButton.setDisable(true);

            HBox.setHgrow(actionButton, Priority.SOMETIMES);

            actionButton.getStyleClass().add("action-button");
            //TODO app wide focus
            //actionButton.requestFocus();

            if (!disableActionButton) {
                actionButton.setOnAction(event -> {
                    hide();
                    actionHandlerOptional.ifPresent(Runnable::run);
                });
            }

            buttonBox.setSpacing(10);

            buttonBox.setAlignment(Pos.CENTER);

            if (buttonAlignment == HPos.RIGHT)
                buttonBox.getChildren().add(spacer);

            buttonBox.getChildren().addAll(actionButton);

            if (secondaryActionButtonText != null && secondaryActionHandlerOptional.isPresent()) {
                secondaryActionButton = new AutoTooltipButton(secondaryActionButtonText);
                secondaryActionButton.setOnAction(event -> {
                    hide();
                    secondaryActionHandlerOptional.ifPresent(Runnable::run);
                });

                buttonBox.getChildren().add(secondaryActionButton);
            }

            if (!hideCloseButton)
                buttonBox.getChildren().add(closeButton);
        } else if (!hideCloseButton) {
            closeButton.setDefaultButton(true);
            buttonBox.getChildren().addAll(spacer, closeButton);
        }
    }

    protected void doClose() {
        hide();
        closeHandlerOptional.ifPresent(Runnable::run);
    }

    protected void setTruncatedMessage() {
        if (message != null && message.length() > maxChar)
            truncatedMessage = StringUtils.abbreviate(message, maxChar);
        else truncatedMessage = Objects.requireNonNullElse(message, "");
    }

    // separate a popup message from optional hyperlinks.  [bisq-network/bisq/pull/4637]
    // hyperlinks are distinguished by [HYPERLINK:] tag
    // referenced in order from within the message via [1], [2] etc.
    // e.g. [HYPERLINK:https://haveno.exchange/wiki]
    private void preProcessMessage(String message) {
        Pattern pattern = Pattern.compile("\\[HYPERLINK:(.*?)\\]");
        Matcher matcher = pattern.matcher(message);
        String work = message;
        while (matcher.find()) {  // extract hyperlinks & store in array
            if (messageHyperlinks == null) {
                messageHyperlinks = new ArrayList<>();
            }
            messageHyperlinks.add(matcher.group(1));
            // replace hyperlink in message with [n] reference
            work = work.replaceFirst(pattern.toString(), String.format("[%d]", messageHyperlinks.size()));
        }
        this.message = work.stripTrailing(); // drop trailing blank lines to avoid extra whitespace at popup bottom
        setTruncatedMessage();
    }

    protected double getDuration(double duration) {
        return useAnimation && GlobalSettings.getUseAnimations() ? duration : 1;
    }

    public boolean isDisplayed() {
        return isDisplayed;
    }

    public String getClipboardText() {
        return headLineLabel.getText() + System.lineSeparator() + message
                + System.lineSeparator() + (messageHyperlinks == null ? "" : messageHyperlinks.toString());
    }

    @Override
    public String toString() {
        return "Popup{" +
                "headLine='" + headLine + '\'' +
                ", message='" + message + '\'' +
                '}';
    }

    private boolean isCentered() {
        if (type.animationType == AnimationType.SlideDownFromCenterTop) return false;
        if (type.animationType == AnimationType.SlideFromRightTop) return false;
        return true;
    }
}
