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

package haveno.core.provider.mempool;

import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;
import org.bitcoinj.core.Coin;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TxValidatorTest {
    private static final Logger log = LoggerFactory.getLogger(TxValidatorTest.class);

    private List<String> btcFeeReceivers = new ArrayList<>();

    public TxValidatorTest() {
        btcFeeReceivers.add("1EKXx73oUhHaUh8JBimtiPGgHfwNmxYKAj");
        btcFeeReceivers.add("1HpvvMHcoXQsX85CjTsco5ZAAMoGu2Mze9");
        btcFeeReceivers.add("3EfRGckBQQuk7cpU7SwatPv8kFD1vALkTU");
        btcFeeReceivers.add("13sxMq8mTw7CTSqgGiMPfwo6ZDsVYrHLmR");
        btcFeeReceivers.add("19qA2BVPoyXDfHKVMovKG7SoxGY7xrBV8c");
        btcFeeReceivers.add("19BNi5EpZhgBBWAt5ka7xWpJpX2ZWJEYyq");
        btcFeeReceivers.add("38bZBj5peYS3Husdz7AH3gEUiUbYRD951t");
        btcFeeReceivers.add("3EtUWqsGThPtjwUczw27YCo6EWvQdaPUyp");
        btcFeeReceivers.add("1BVxNn3T12veSK6DgqwU4Hdn7QHcDDRag7");
        btcFeeReceivers.add("3A8Zc1XioE2HRzYfbb5P8iemCS72M6vRJV");
        btcFeeReceivers.add("34VLFgtFKAtwTdZ5rengTT2g2zC99sWQLC");
        log.warn("Known BTC fee receivers: {}", btcFeeReceivers.toString());
    }

    @Test
    public void testMakerTx()  throws InterruptedException {
        String mempoolData, offerData;
    }

    @Test
    public void testTakerTx()  throws InterruptedException {
        String mempoolData, offerData;
    }

    private void testOfferSet(Map<String, String> offers, Map<String, String> mempoolData, boolean expectedResult) {
        Set<String> knownValuesList = new HashSet<>(offers.values());
        knownValuesList.forEach(offerData -> {
            TxValidator txValidator = createTxValidator(offerData);
            log.warn("TESTING {}", txValidator.getTxId());
            String jsonTxt = mempoolData.get(txValidator.getTxId());
            if (jsonTxt == null || jsonTxt.isEmpty()) {
                log.warn("{} was not found in the mempool", txValidator.getTxId());
                assertFalse(expectedResult);  // tx was not found in explorer
            } else {
                //txValidator.parseJsonValidateMakerFeeTx(jsonTxt, btcFeeReceivers);
		log.warn("expectedResult {}", expectedResult );
		log.warn("getResult {}", txValidator.getResult() );
                assertTrue(expectedResult == txValidator.getResult());
            }
        });
    }

    private Map<String, String> loadJsonTestData(String fileName) {
        String json = "";
        try {
            json = IOUtils.toString(this.getClass().getResourceAsStream(fileName), "UTF-8");
        } catch (IOException e) {
            log.error(e.toString());
        }
        Map<String, String> map = new Gson().fromJson(json, Map.class);
        return map;
    }

    // initialize the TxValidator with offerData to be validated
    private TxValidator createTxValidator(String offerData) {
        try {
            String[] y = offerData.split(",");
            String txId = y[1];
            long amount = Long.parseLong(y[2]);
            TxValidator txValidator = new TxValidator(txId, Coin.valueOf(amount));
            return txValidator;
        } catch (RuntimeException ignore) {
            // If input format is not as expected we ignore entry
        }
        return null;
    }
}
