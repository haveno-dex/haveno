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

import com.jfoenix.controls.JFXTabPane;
import com.jfoenix.skins.JFXTabPaneSkin;
import de.jensd.fx.glyphs.GlyphIcons;
import haveno.desktop.components.InfoInputTextField;
import haveno.desktop.components.InfoTextField;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TextFieldWithCopyIcon;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAttribute;
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

import java.util.ArrayList;
import java.util.List;

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
        if (node instanceof TextFieldWithCopyIcon) node = ((TextFieldWithCopyIcon) node).getTextField(); // wrapper pane is not announced
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

    // exposes an icon-styled node as readable static text (e.g. warning icons); reverts asButton focusability
    public static void asText(Node node, String text) {
        node.setAccessibleRole(AccessibleRole.TEXT);
        setName(node, text);
        node.setFocusTraversable(false);
        node.getStyleClass().remove(FOCUSABLE_STYLE_CLASS);
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

    // spaces out characters so screen readers spell them, e.g. ids and currency codes
    public static String spellOut(String text) {
        return text == null ? null : String.join(" ", text.split(""));
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
            int index = 0;
            for (Node header : tabHeaders(tabPane)) {
                if (index >= tabPane.getTabs().size()) break;
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
                        } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
                            selectAdjacentTab(tabPane, tab, e.getCode() == KeyCode.RIGHT ? 1 : -1);
                            e.consume();
                        }
                    });
                    // headers are panes that clicks don't focus, so focus them to announce the selected tab
                    header.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> header.requestFocus());
                }
            }
        };
        tabPane.skinProperty().addListener((o, oldSkin, newSkin) -> {
            if (newSkin == null) return;
            if (installAccessibleSkin(tabPane)) return; // replaced; this listener fires again with ours
            Platform.runLater(apply);
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> Platform.runLater(apply)); // cover dynamically added tabs
        if (!installAccessibleSkin(tabPane) && tabPane.getSkin() != null) apply.run();
    }

    // arrows move between tabs like a native tab bar, selecting and focusing so the name is announced
    private static void selectAdjacentTab(TabPane tabPane, Tab from, int step) {
        List<Node> headers = tabHeaders(tabPane);
        for (int index = tabPane.getTabs().indexOf(from) + step; index >= 0 && index < tabPane.getTabs().size(); index += step) {
            if (tabPane.getTabs().get(index).isDisable()) continue; // skip decorative label tabs
            tabPane.getSelectionModel().select(index);
            if (index < headers.size()) headers.get(index).requestFocus();
            return;
        }
    }

    // positional header panes of the skin's headers-region, parallel to the tab list
    private static List<Node> tabHeaders(TabPane tabPane) {
        Node region = tabPane.lookup(".headers-region");
        if (region == null) return List.of();
        List<Node> headers = new ArrayList<>();
        for (Node header : ((Parent) region).getChildrenUnmodifiable())
            if (header.getStyleClass().contains("tab")) headers.add(header);
        return headers;
    }

    private static boolean installAccessibleSkin(TabPane tabPane) {
        if (!(tabPane instanceof JFXTabPane) || tabPane.getSkin() instanceof TabsAccessibleSkin) return false;
        tabPane.setSkin(new TabsAccessibleSkin((JFXTabPane) tabPane));
        return true;
    }

    // JFoenix's skin answers no accessibility queries, leaving mac tab groups announced bare; expose the
    // selected header as the group's value and the headers as its tabs, like the standard skin does
    private static class TabsAccessibleSkin extends JFXTabPaneSkin {
        private final JFXTabPane tabPane;

        TabsAccessibleSkin(JFXTabPane tabPane) {
            super(tabPane);
            this.tabPane = tabPane;
        }

        @Override
        protected Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
            switch (attribute) {
                case FOCUS_ITEM: return headerAt(tabPane.getSelectionModel().getSelectedIndex());
                case ITEM_COUNT: return tabHeaders(tabPane).size();
                case ITEM_AT_INDEX: return headerAt((Integer) parameters[0]);
                default: return super.queryAccessibleAttribute(attribute, parameters);
            }
        }

        private Node headerAt(int index) {
            List<Node> headers = tabHeaders(tabPane);
            return index >= 0 && index < headers.size() ? headers.get(index) : null;
        }
    }
}
