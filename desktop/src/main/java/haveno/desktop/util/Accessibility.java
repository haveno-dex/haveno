/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.util;

import de.jensd.fx.glyphs.GlyphIcons;
import haveno.desktop.components.InfoInputTextField;
import haveno.desktop.components.InfoTextField;
import haveno.desktop.components.InputTextField;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Helpers to expose the UI to screen readers (e.g. VoiceOver).
 *
 * Naming rules: use {@link #setName(Node, String)} for buttons, labels and icons only.
 * For text inputs and combo boxes the accessible-text attribute masks the announced
 * value, so label them via {@link #setLabel(Label, Node)} or {@link #setHelp(Node, String)}.
 */
public final class Accessibility {

    private Accessibility() {
    }

    // sets the name announced by screen readers; not for text inputs (masks their value)
    public static void setName(Node node, String name) {
        if (name != null) node.setAccessibleText(name);
    }

    // sets the help text, announced after the value; safe for any control
    public static void setHelp(Node node, String help) {
        if (help == null) return;
        if (node instanceof InputTextField) ((InputTextField) node).setBaseAccessibleHelp(help); // survives validation resets
        else node.setAccessibleHelp(help);
    }

    // associates a label with the control it titles, unwrapping composite fields
    public static void setLabel(Label label, Node node) {
        if (node instanceof InfoInputTextField) node = ((InfoInputTextField) node).getInputTextField();
        else if (node instanceof InfoTextField) node = ((InfoTextField) node).getTextField();
        label.setLabelFor(node);
    }

    // mirrors popover/info content onto a control's help text when extractable
    public static void setHelpFromContent(Node target, Node content) {
        if (content instanceof Labeled) setHelp(target, ((Labeled) content).getText());
    }

    // names a control from its tooltip so icon-only controls stay readable
    public static void nameFromTooltip(Control control) {
        Tooltip tooltip = control.getTooltip();
        if (tooltip != null) setName(control, tooltip.getText());
    }

    // exposes an icon-styled node as readable static text (e.g. warning icons)
    public static void asText(Node node, String text) {
        node.setAccessibleRole(AccessibleRole.TEXT);
        setName(node, text);
    }

    // hides a decorative node (e.g. icon glyph) from screen readers; a blank
    // accessible text is needed since empty strings fall back to the glyph char
    public static void mute(Node node) {
        node.setAccessibleRole(AccessibleRole.NODE);
        node.setAccessibleText(" ");
    }

    // derives a readable fallback name from an icon constant, e.g. CONTENT_COPY -> "Content copy"
    public static String iconName(GlyphIcons icon) {
        String name = icon.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static final String KEY_HANDLER_INSTALLED = "haveno.a11y.keyHandler";
    private static final String FOCUSABLE_STYLE_CLASS = "a11y-focusable";

    // exposes a clickable node as a named button, focusable and activatable with Space; idempotent.
    // Space matches JavaFX ButtonBehavior; Enter is left to bubble so popup close/default semantics keep working
    public static void asButton(Node node, String name) {
        node.setAccessibleRole(AccessibleRole.BUTTON);
        setName(node, name);
        node.setFocusTraversable(true);
        if (!node.getStyleClass().contains(FOCUSABLE_STYLE_CLASS)) node.getStyleClass().add(FOCUSABLE_STYLE_CLASS);
        if (node.getProperties().put(KEY_HANDLER_INSTALLED, Boolean.TRUE) == null) {
            node.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.SPACE) {
                    click(node);
                    e.consume();
                }
            });
        }
    }

    // fires a synthesized primary-button click centered on the node so position-aware handlers behave
    public static void click(Node node) {
        Bounds local = node.getBoundsInLocal();
        Bounds scene = node.localToScene(local); // sourceless MouseEvent treats x/y as scene coordinates
        Bounds screen = node.localToScreen(local);
        double screenX = screen == null ? 0 : screen.getCenterX();
        double screenY = screen == null ? 0 : screen.getCenterY();
        Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, scene.getCenterX(), scene.getCenterY(), screenX, screenY,
                MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, true, false, true, null));
    }

    // exposes JFoenix tab headers (plain panes in its skin) as named, focusable, keyboard-selectable tabs
    public static void fixTabs(TabPane tabPane) {
        Runnable apply = () -> {
            Node region = tabPane.lookup(".headers-region");
            if (region == null) return;
            int index = 0;
            for (Node header : ((Parent) region).getChildrenUnmodifiable()) {
                if (!header.getStyleClass().contains("tab") || index >= tabPane.getTabs().size()) continue;
                Tab tab = tabPane.getTabs().get(index++);
                if (tab.isDisable()) continue; // decorative header tabs stay unnamed and unfocusable
                header.setAccessibleRole(AccessibleRole.TAB_ITEM);
                setName(header, tab.getText());
                header.setFocusTraversable(true);
                if (!header.getStyleClass().contains(FOCUSABLE_STYLE_CLASS)) header.getStyleClass().add(FOCUSABLE_STYLE_CLASS);
                if (header.getProperties().put(KEY_HANDLER_INSTALLED, Boolean.TRUE) == null) {
                    header.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                        if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                            tabPane.getSelectionModel().select(tab);
                            e.consume();
                        }
                    });
                }
            }
        };
        tabPane.skinProperty().addListener((o, oldSkin, newSkin) -> {
            if (newSkin != null) Platform.runLater(apply);
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> Platform.runLater(apply)); // cover dynamically added tabs
        if (tabPane.getSkin() != null) apply.run();
    }
}
