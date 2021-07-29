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

package bisq.asset.coins;

import bisq.asset.AbstractAssetTest;

import org.junit.Test;

public class FangoTest extends AbstractAssetTest {

    public FangoTest() {
        super(new Fango());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("fango2A7yAHEURsoSRWC41EZ1JnZ9PQEJiJnFwv8jU1kAd2VHkxNEYbaQMsTRQ6ouwgPeGSmNCK21RPavxAXv9VFQdVAV2ivUH5");
        assertValidAddress("fango2apz4AKv3x9S9FsimTd9sKa3nq58CCHTyjgHDJ41QKTAH11QYY3pjCE58U6tLbaLL87xcqbEQbp2pQxd1tzYwk8ZHTrzDf");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("fango7w8yD5Tp1Vaaai8zh8TBhEyTS7CWhRE3CbkT9nhd68yeXTuggfFTEcyUrMGEt52545bwgpXyUL5c4MxU21zDhXAj4CJTs");
        assertInvalidAddress("fango7TXePaP81hv1t5k6wFg1Vwp7ZYzKTum2xmSuaMt56BMPqTxgjUgv5ReswvTvq16PuAAJgkRcW4DoAJN1iDLJKtgxyjZTFMM");
        assertInvalidAddress("fang04YAgm5QnEsGupKomX1gh3F73xWoMPvfrUA34FC9cdPGf7i6gJm7mHV5p6QG3S8eFUQd3rXRsdziEdtQpZxG5cGswjyCrY3");
        assertInvalidAddress("fanGo3FHSHDYsS3z4ox3d42QK7oo4wEPSBgof8Zv1WWh5gbm1F3QfHW8Yi5NGoSpFQVunDnYqpUmDFt3N7gM1ybjebN2LfJr1Ug");
        assertInvalidAddress("faNgo4bfKY1P2uESMLBeEHBg2jsEXuDgZdxR7RtDjGh99zp3FSbB17Bf8qoyys9Cgx8xB43N8DUJkRc3jwN2wuWq1gitJhEbwTP");
        assertInvalidAddress("fAngo3qmkZkgSojsFS6oYSQFnD43cstW9PuYt48oFBqk2HtfJdo9Wy45BNLCTGRq7aUErLBdt8UY9ekwD47ADXcqitnoqqFmztd");
        assertInvalidAddress("Fango7pSXDxHTEuhPWdX1yiQS2UM2YQzPex7R5GiJHgHb7jtVqGXUvYEzCq4gEY1sqdFX4ySM5jt9VXw2kRtR5JyB86DZWD9Zf3");
        assertInvalidAddress("FANGO69YxGTeR4cWjV6QswKAE2YAoXf8MhgQrfUEMCpA35wWC1UNe73ETMq7fVBUTRWCBAbBaFZTNThWDbuSCUYmPgkSWgSaHYD");
    }
}
