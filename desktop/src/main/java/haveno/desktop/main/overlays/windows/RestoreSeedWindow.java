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
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import java.util.List;

public class RestoreSeedWindow extends ConfirmationWindow<RestoreSeedWindow> {
    private final int offerCount;
    private final boolean missingHeight;

    public RestoreSeedWindow(int offerCount, boolean missingHeight) {
        super(560);
        this.offerCount = offerCount;
        this.missingHeight = missingHeight;
        headLine = Res.get("popup.restore.title");
        actionButtonText = Res.get("popup.restore.action");
        destructive = true;
        acknowledgement = Res.get("popup.restore.acknowledgement");
    }

    @Override
    protected void addContent() {
        VBox header = hero(MaterialDesignIcon.BACKUP_RESTORE, "confirm-send-icon", headLine,
                (Node) null, Res.get("popup.restore.subtitle"));
        header.getStyleClass().add("hero-accent-red");
        List<Node> rows = new ArrayList<>();
        rows.add(HeroWindowComponents.featureRow(MaterialDesignIcon.FOLDER_LOCK, "hero-accent-red",
                Res.get("popup.restore.wallet.title"), Res.get("popup.restore.wallet.body")));
        rows.add(HeroWindowComponents.featureRow(MaterialDesignIcon.SWAP_HORIZONTAL, "hero-accent-amber",
                Res.get("popup.restore.trades.title"), Res.get("popup.restore.trades.body")));
        if (offerCount > 0) rows.add(HeroWindowComponents.featureRow(MaterialDesignIcon.TAG_REMOVE, "hero-accent-amber",
                Res.get(offerCount == 1 ? "popup.restore.offers.one" : "popup.restore.offers", offerCount), null));
        if (missingHeight) rows.add(HeroWindowComponents.featureRow(MaterialDesignIcon.TIMER_SAND, "hero-accent-primary",
                Res.get("popup.restore.height.title"), Res.get("popup.restore.height.body")));
        VBox sheet = HeroWindowComponents.rows(rows.toArray(new Node[0]));
        sheet.getStyleClass().addAll("confirm-send-sheet", "review-restore-sheet");
        addReview(header, sheet);
    }
}
