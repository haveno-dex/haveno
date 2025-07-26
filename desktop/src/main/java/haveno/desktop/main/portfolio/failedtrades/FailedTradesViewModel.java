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

package haveno.desktop.main.portfolio.failedtrades;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.common.model.ActivatableWithDataModel;
import haveno.desktop.common.model.ViewModel;
import javafx.collections.ObservableList;

class FailedTradesViewModel extends ActivatableWithDataModel<FailedTradesDataModel> implements ViewModel {
    private final CoinFormatter formatter;


    @Inject
    public FailedTradesViewModel(FailedTradesDataModel dataModel, @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        super(dataModel);

        this.formatter = formatter;
    }

    public ObservableList<FailedTradesListItem> getList() {
        return dataModel.getList();
    }
}
