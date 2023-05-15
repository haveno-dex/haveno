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

package haveno.desktop.main.offer.offerbook;

import haveno.common.config.Config;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.locale.Res;
import haveno.core.offer.OfferDirection;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import javafx.scene.layout.GridPane;

import javax.inject.Inject;
import javax.inject.Named;

@FxmlView
public class BtcOfferBookView extends OfferBookView<GridPane, BtcOfferBookViewModel> {

    @Inject
    BtcOfferBookView(BtcOfferBookViewModel model,
                     Navigation navigation,
                     OfferDetailsWindow offerDetailsWindow,
                     @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                     PrivateNotificationManager privateNotificationManager,
                     @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys,
                     AccountAgeWitnessService accountAgeWitnessService,
                     SignedWitnessService signedWitnessService) {
        super(model, navigation, offerDetailsWindow, formatter, privateNotificationManager, useDevPrivilegeKeys, accountAgeWitnessService, signedWitnessService);
    }

    @Override
    protected String getMarketTitle() {
        return model.getDirection().equals(OfferDirection.BUY) ?
                Res.get("offerbook.availableOffersToBuy", Res.getBaseCurrencyCode(), Res.get("shared.traditional")) :
                Res.get("offerbook.availableOffersToSell", Res.getBaseCurrencyCode(), Res.get("shared.traditional"));


    }

    @Override
    String getTradeCurrencyCode() {
        return Res.getBaseCurrencyCode();
    }
}
