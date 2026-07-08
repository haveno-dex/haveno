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

package haveno.desktop.main.overlays.windows;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.desktop.app.StartupWizard;
import haveno.desktop.components.AutoTooltipCheckBox;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/**
 * The user agreement content, shared by the startup wizard and {@link TacWindow}: a risk overview
 * page with acknowledgment checkboxes and a legal terms page with a final acceptance checkbox.
 * The host owns page navigation and calls the validation methods to flag unchecked boxes.
 */
public class TacContent {

    private static final PseudoClass ERROR_PSEUDO_CLASS = PseudoClass.getPseudoClass("error");
    private static final double DETAIL_ICON_BOX_WIDTH = 44;
    private static final double DETAIL_TEXT_GAP = 18;
    private static final double DETAIL_HORIZONTAL_PADDING = 48;

    private final double pageWidth;
    private final CheckBox lossOfFundsCheckBox;
    private final CheckBox compensationCheckBox;
    private final CheckBox legalTermsCheckBox;
    private boolean riskValidationRequested;
    private boolean legalValidationRequested;

    public TacContent(double pageWidth) {
        this.pageWidth = pageWidth;
        lossOfFundsCheckBox = createCheckBox(Res.get("tacWindow.risk.accept1"));
        compensationCheckBox = createCheckBox(Res.get("tacWindow.risk.accept2"));
        legalTermsCheckBox = createCheckBox(Res.get("tacWindow.legal.accept"));
    }

    public VBox createRiskPage() {
        VBox page = new VBox(8);
        page.getStyleClass().add("tac-agreement-page");
        page.setMaxWidth(pageWidth);
        page.getChildren().addAll(createRiskOverview(), createConfirmationsPanel());
        return page;
    }

    public VBox createLegalPage() {
        VBox page = new VBox(12);
        page.getStyleClass().add("tac-agreement-page");
        page.setMaxWidth(pageWidth);
        VBox legalPanel = createLegalPanel();
        VBox.setVgrow(legalPanel, Priority.ALWAYS);
        page.getChildren().addAll(legalPanel, createConfirmRow(legalTermsCheckBox));
        return page;
    }

    public boolean isRiskAccepted() {
        return lossOfFundsCheckBox.isSelected() && compensationCheckBox.isSelected();
    }

    public boolean isAllAccepted() {
        return isRiskAccepted() && legalTermsCheckBox.isSelected();
    }

    /** Flag unchecked risk boxes (called when trying to advance past the risk page). */
    public void requestRiskValidation() {
        riskValidationRequested = true;
        updateCheckBoxErrorStates();
    }

    /** Flag the unchecked legal box (called when trying to accept the terms). */
    public void requestLegalValidation() {
        legalValidationRequested = true;
        updateCheckBoxErrorStates();
    }

    private VBox createRiskOverview() {
        VBox section = new VBox(8);
        section.getChildren().addAll(
                StartupWizard.createHeaderSection(MaterialDesignIcon.SHIELD_OUTLINE,
                        Res.get("tacWindow.risk.headline"),
                        Res.get("tacWindow.risk.subtitle")),
                createRiskDetailRow(MaterialDesignIcon.ACCOUNT_MULTIPLE_OUTLINE,
                        Res.get("tacWindow.risk.p2p.title"),
                        Res.get("tacWindow.risk.p2p.body")),
                createSeparator(),
                createRiskDetailRow(MaterialDesignIcon.ALERT_OUTLINE,
                        Res.get("tacWindow.risk.financial.title"),
                        Res.get("tacWindow.risk.financial.body")),
                createSeparator(),
                createRiskDetailRow(MaterialDesignIcon.CASH_MULTIPLE,
                        Res.get("tacWindow.risk.noGuarantees.title"),
                        Res.get("tacWindow.risk.noGuarantees.body")));
        return section;
    }

    private HBox createRiskDetailRow(MaterialDesignIcon icon, String titleText, String bodyText) {
        double textWidth = pageWidth - DETAIL_HORIZONTAL_PADDING - DETAIL_ICON_BOX_WIDTH - DETAIL_TEXT_GAP;

        StackPane iconBox = new StackPane(createIcon(icon, "1.5em", "tac-agreement-risk-detail-icon"));
        iconBox.getStyleClass().add("tac-agreement-risk-detail-icon-box");

        Label title = new Label(titleText);
        title.getStyleClass().add("tac-agreement-risk-detail-title");
        title.setWrapText(true);
        title.setPrefWidth(textWidth);

        Label body = new Label(bodyText);
        body.getStyleClass().add("tac-agreement-risk-detail-body");
        body.setWrapText(true);
        body.setPrefWidth(textWidth);

        VBox textBox = new VBox(3, title, body);
        textBox.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox row = new HBox(DETAIL_TEXT_GAP, iconBox, textBox);
        row.getStyleClass().add("tac-agreement-risk-detail-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setFillHeight(false);
        row.setMinHeight(Region.USE_PREF_SIZE);
        // rows share spare page height equally so the page stays vertically balanced at any host height
        VBox.setVgrow(row, Priority.ALWAYS);
        return row;
    }

    private Region createSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("tac-agreement-separator");
        return separator;
    }

    private VBox createConfirmationsPanel() {
        Label headline = new Label(Res.get("tacWindow.risk.confirm.headline"));
        headline.getStyleClass().add("tac-agreement-confirm-headline");

        VBox panel = new VBox(6, headline, createConfirmRow(lossOfFundsCheckBox), createConfirmRow(compensationCheckBox));
        panel.setPadding(new Insets(9, 0, 0, 0));
        return panel;
    }

    private VBox createLegalPanel() {
        ScrollPane scrollPane = new ScrollPane(createLegalScrollContent());
        scrollPane.getStyleClass().add("tac-agreement-legal-scroll-pane");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(260);
        scrollPane.setMinHeight(200);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        return new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.SCALE_BALANCE,
                        Res.get("tacWindow.legal.headline"),
                        Res.get("tacWindow.legal.subtitle")),
                scrollPane,
                createLegalAcknowledgment());
    }

    private VBox createLegalScrollContent() {
        VBox content = new VBox(10);
        content.getStyleClass().add("tac-agreement-legal-scroll-content");
        for (int i = 1; i <= 7; i++) {
            content.getChildren().add(createLegalSection(i + ".",
                    Res.get("tacWindow.legal.section" + i + ".title"),
                    Res.get("tacWindow.legal.section" + i + ".body")));
        }
        return content;
    }

    private HBox createLegalSection(String number, String titleText, String bodyText) {
        Label numberLabel = new Label(number);
        numberLabel.getStyleClass().add("tac-agreement-legal-section-number");

        Label title = new Label(titleText);
        title.getStyleClass().add("tac-agreement-legal-section-title");
        title.setWrapText(true);

        Label body = new Label(bodyText);
        body.getStyleClass().add("tac-agreement-legal-section-body");
        body.setWrapText(true);

        VBox textBox = new VBox(6, title, body);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox section = new HBox(6, numberLabel, textBox);
        section.setAlignment(Pos.TOP_LEFT);
        return section;
    }

    private HBox createLegalAcknowledgment() {
        Text icon = createIcon(MaterialDesignIcon.INFORMATION_OUTLINE, "1.2em", "tac-agreement-acknowledgment-icon");

        Label text = new Label(Res.get("tacWindow.legal.acknowledgment"));
        text.getStyleClass().add("tac-agreement-acknowledgment-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox acknowledgment = new HBox(12, icon, text);
        acknowledgment.getStyleClass().add("tac-agreement-acknowledgment");
        acknowledgment.setAlignment(Pos.CENTER_LEFT);
        return acknowledgment;
    }

    private CheckBox createCheckBox(String text) {
        CheckBox checkBox = new AutoTooltipCheckBox(text);
        checkBox.getStyleClass().add("tac-agreement-check-box");
        checkBox.setMaxWidth(Double.MAX_VALUE);
        checkBox.setWrapText(true);
        checkBox.setOnAction(event -> {
            if (riskValidationRequested || legalValidationRequested) updateCheckBoxErrorStates();
        });
        return checkBox;
    }

    private VBox createConfirmRow(CheckBox checkBox) {
        HBox row = new HBox(10, checkBox);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(checkBox, Priority.ALWAYS);
        return new VBox(row);
    }

    private Text createIcon(MaterialDesignIcon icon, String size, String styleClass) {
        return StartupWizard.createIcon(icon, size, styleClass);
    }

    private void updateCheckBoxErrorStates() {
        updateCheckBoxErrorState(lossOfFundsCheckBox, riskValidationRequested);
        updateCheckBoxErrorState(compensationCheckBox, riskValidationRequested);
        updateCheckBoxErrorState(legalTermsCheckBox, legalValidationRequested);
    }

    private void updateCheckBoxErrorState(CheckBox checkBox, boolean validationRequested) {
        checkBox.pseudoClassStateChanged(ERROR_PSEUDO_CLASS, validationRequested && !checkBox.isSelected());
    }
}
