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

package haveno.core.locale;


import com.google.protobuf.Message;
import lombok.Getter;
import lombok.ToString;

import java.util.Currency;
import java.util.Locale;

@ToString
@Getter
public final class TraditionalCurrency extends TradeCurrency {

    // http://boschista.deviantart.com/journal/Cool-ASCII-Symbols-214218618
    private final static String PREFIX = "★ ";

    public TraditionalCurrency(String currencyCode) {
        this(Currency.getInstance(currencyCode), getLocale());
    }

    public TraditionalCurrency(String currencyCode, String name) {
        super(currencyCode, name);
    }

    public TraditionalCurrency(TraditionalCurrency currency) {
        this(currency.getCode(), currency.getName());
    }

    @SuppressWarnings("WeakerAccess")
    public TraditionalCurrency(Currency currency) {
        this(currency, getLocale());
    }

    @SuppressWarnings("WeakerAccess")
    public TraditionalCurrency(Currency currency, Locale locale) {
        super(currency.getCurrencyCode(), currency.getDisplayName(locale));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Message toProtoMessage() {
        return getTradeCurrencyBuilder()
                .setCode(code)
                .setName(name)
                .setTraditionalCurrency(protobuf.TraditionalCurrency.newBuilder())
                .build();
    }

    public static TraditionalCurrency fromProto(protobuf.TradeCurrency proto) {
        return new TraditionalCurrency(proto.getCode(), proto.getName());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static Locale getLocale() {
        return GlobalSettings.getLocale();
    }

    @Override
    public String getDisplayPrefix() {
        return PREFIX;
    }

}
