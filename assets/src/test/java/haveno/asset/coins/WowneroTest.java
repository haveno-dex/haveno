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

package haveno.asset.coins;

import haveno.asset.AbstractAssetTest;
import org.junit.jupiter.api.Test;

public class WowneroTest extends AbstractAssetTest {

    public WowneroTest() {
        super(new Wownero());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("WW3euBj4AfuYSVs1T2zDGHiHQbmL9r78JUfrTTuwHkWDSXEoX31Q8viHQaPgk6f9w271YH67UBCfxAZkBccfX6t11VVXSFfdk");
        assertValidAddress("WW3CRUnpWnAQmXyr8rgd5qVneg3tTKbRrZu2qbTzjwMNEtvddodV2inPbRSGjcdRyHKVNsNkwWccjN6iKu1FAGr32hqKzikQP");
        assertValidAddress("WW4NzZ6EXLmeBBAjzPJ1J6jkkmqDsZzrp9TGxtP6E8pTMGtXS3yq8VJF3Rv2cbhkVig4cLEqWZnQpgwxjX5isrWT2FxmeWtWy");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("");
        assertInvalidAddress("Wo3MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94mP");
        assertInvalidAddress("693MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94mP");
        assertInvalidAddress("o3MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94mP");
        assertInvalidAddress("3MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94mP");
    }
}
