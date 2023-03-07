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

package haveno.common.config;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.utils.MonetaryFormat;

import lombok.Getter;

public enum BaseCurrencyNetwork {
    XMR_MAINNET(new XmrMainNetParams(), "XMR", "MAINNET", "Monero"), // TODO (woodser): network params are part of bitcoinj and shouldn't be needed. only used to get MonetaryFormat? replace with MonetaryFormat if so
    XMR_STAGENET(new XmrStageNetParams(), "XMR", "STAGENET", "Monero"),
    XMR_LOCAL(new XmrTestNetParams(), "XMR", "TESTNET", "Monero");

    @Getter
    private final NetworkParameters parameters;
    @Getter
    private final String currencyCode;
    @Getter
    private final String network;
    @Getter
    private final String currencyName;

    BaseCurrencyNetwork(NetworkParameters parameters, String currencyCode, String network, String currencyName) {
        this.parameters = parameters;
        this.currencyCode = currencyCode;
        this.network = network;
        this.currencyName = currencyName;
    }

    public boolean isMainnet() {
        return "XMR_MAINNET".equals(name());
    }

    public boolean isTestnet() {
        return "XMR_LOCAL".equals(name());
    }

    public boolean isStagenet() {
        return "XMR_STAGENET".equals(name());
    }

    public long getDefaultMinFeePerVbyte() {
        return 15;  // 2021-02-22 due to mempool congestion, increased from 2
    }

    private static final MonetaryFormat XMR_MONETARY_FORMAT = new MonetaryFormat().minDecimals(2).repeatOptionalDecimals(2, 3).noCode().code(0, "XMR");

    private static class XmrMainNetParams extends MainNetParams {
        @Override
        public MonetaryFormat getMonetaryFormat() {
            return XMR_MONETARY_FORMAT;
        }
    }

    private static class XmrTestNetParams extends RegTestParams {
        @Override
        public MonetaryFormat getMonetaryFormat() {
            return XMR_MONETARY_FORMAT;
        }
    }

    private static class XmrStageNetParams extends RegTestParams {
        @Override
        public MonetaryFormat getMonetaryFormat() {
            return XMR_MONETARY_FORMAT;
        }
    }
}
