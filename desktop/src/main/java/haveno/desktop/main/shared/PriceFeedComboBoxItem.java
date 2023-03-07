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

package haveno.desktop.main.shared;

import haveno.common.UserThread;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import lombok.Getter;
import lombok.Setter;

public class PriceFeedComboBoxItem {
    public final String currencyCode;
    public final StringProperty displayStringProperty = new SimpleStringProperty();
    @Setter
    @Getter
    private boolean isPriceAvailable;
    @Setter
    @Getter
    private boolean isExternallyProvidedPrice;

    public PriceFeedComboBoxItem(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public void setDisplayString(String displayString) {
        UserThread.execute(() ->  this.displayStringProperty.set(displayString));
    }
}
