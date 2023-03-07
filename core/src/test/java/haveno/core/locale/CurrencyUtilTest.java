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

import haveno.asset.Asset;
import haveno.asset.AssetRegistry;
import haveno.asset.Coin;
import haveno.asset.coins.Ether;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CurrencyUtilTest {

    @Before
    public void setup() {

        Locale.setDefault(new Locale("en", "US"));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testGetTradeCurrency() {
        Optional<TradeCurrency> euro = CurrencyUtil.getTradeCurrency("EUR");
        Optional<TradeCurrency> naira = CurrencyUtil.getTradeCurrency("NGN");
        Optional<TradeCurrency> fake = CurrencyUtil.getTradeCurrency("FAK");

        assertTrue(euro.isPresent());
        assertTrue(naira.isPresent());
        assertFalse("Fake currency shouldn't exist", fake.isPresent());
    }

    @Test
    public void testFindAsset() {
        MockAssetRegistry assetRegistry = new MockAssetRegistry();

        // Add a mock coin which has no mainnet version, needs to fail if we are on mainnet
        MockTestnetCoin.Testnet mockTestnetCoin = new MockTestnetCoin.Testnet();
        try {
            assetRegistry.addAsset(mockTestnetCoin);
            CurrencyUtil.findAsset(assetRegistry, "MOCK_COIN",
                    BaseCurrencyNetwork.XMR_MAINNET);
            Assert.fail("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            String wantMessage = "We are on mainnet and we could not find an asset with network type mainnet";
            Assert.assertTrue("Unexpected exception, want message starting with " +
                    "'" + wantMessage + "', got '" + e.getMessage() + "'", e.getMessage().startsWith(wantMessage));
        }

        // For testnet its ok
        assertEquals(CurrencyUtil.findAsset(assetRegistry, "MOCK_COIN",
                BaseCurrencyNetwork.XMR_LOCAL).get().getTickerSymbol(), "MOCK_COIN");
        assertEquals(Coin.Network.TESTNET, mockTestnetCoin.getNetwork());

        // For regtest its still found
        assertEquals(CurrencyUtil.findAsset(assetRegistry, "MOCK_COIN",
                BaseCurrencyNetwork.XMR_STAGENET).get().getTickerSymbol(), "MOCK_COIN");


        // We test if we are not on mainnet to get the mainnet coin
        Coin ether = new Ether();
        assertEquals(CurrencyUtil.findAsset(assetRegistry, "ETH",
                BaseCurrencyNetwork.XMR_LOCAL).get().getTickerSymbol(), "ETH");
        assertEquals(CurrencyUtil.findAsset(assetRegistry, "ETH",
                BaseCurrencyNetwork.XMR_STAGENET).get().getTickerSymbol(), "ETH");
        assertEquals(Coin.Network.MAINNET, ether.getNetwork());
     }

    @Test
    public void testGetNameAndCodeOfRemovedAsset() {
        assertEquals("N/A (XYZ)", CurrencyUtil.getNameAndCode("XYZ"));
    }

    class MockAssetRegistry extends AssetRegistry {
        private List<Asset> registeredAssets = new ArrayList<>();

        MockAssetRegistry() {
            for (Asset asset : ServiceLoader.load(Asset.class)) {
                registeredAssets.add(asset);
            }
        }

        void addAsset(Asset asset) {
            registeredAssets.add(asset);
        }

        public Stream<Asset> stream() {
            return registeredAssets.stream();
        }
    }
}
