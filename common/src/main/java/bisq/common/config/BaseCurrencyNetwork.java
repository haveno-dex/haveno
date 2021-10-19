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

package bisq.common.config;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.MonetaryFormat;

import lombok.Getter;

public enum BaseCurrencyNetwork {
    XMR_MAINNET(new XmrMainNetParams(), "XMR", "MAINNET", "Monero"), // TODO (woodser): network params are part of bitcoinj and shouldn't be needed. only used to get MonetaryFormat? replace with MonetaryFormat if so
    XMR_TESTNET(new XmrTestNet3Params(), "XMR", "TESTNET", "Monero"),
    XMR_STAGENET(new XmrRegTestParams(), "XMR", "STAGENET", "Monero");

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
        return "XMR_TESTNET".equals(name());
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

    private static class XmrTestNet3Params extends TestNet3Params {
        @Override
        public MonetaryFormat getMonetaryFormat() {
            return XMR_MONETARY_FORMAT;
        }
    }

    private static class XmrRegTestParams extends RegTestParams {
        @Override
        public MonetaryFormat getMonetaryFormat() {
            return XMR_MONETARY_FORMAT;
        }
    }
}
