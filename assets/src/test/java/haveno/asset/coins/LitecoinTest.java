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

package haveno.asset.coins;

import haveno.asset.AbstractAssetTest;
import org.junit.jupiter.api.Test;

public class LitecoinTest extends AbstractAssetTest {

    public LitecoinTest() {
        super(new Litecoin());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("Lg3PX8wRWmApFCoCMAsPF5P9dPHYQHEWKW");
        assertValidAddress("LTuoeY6RBHV3n3cfhXVVTbJbxzxnXs9ofm");
        assertValidAddress("LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW");
        assertValidAddress("M8T1B2Z97gVdvmfkQcAtYbEepune1tzGua");
        assertValidAddress("ltc1qr07zu594qf63xm7l7x6pu3a2v39m2z6hh5pp4t");
        assertValidAddress("ltc1qzvcgmntglcuv4smv3lzj6k8szcvsrmvk0phrr9wfq8w493r096ssm2fgsw");
        assertValidAddress("MESruSiB2uC9i7tMU6VMUVom91ohM7Rnbd");
        assertValidAddress("ltc1q2a0laq2jg2gntzhfs43qptajd325kkx7hrq9cs");
        assertValidAddress("ltc1qd6d54mt8xxcg0xg3l0vh6fymdfvd2tv0vnwyrv");
        assertValidAddress("ltc1gmay6ht028aurcm680f8e8wxdup07y2tq46f6z2d4v8rutewqmmcqk29jtm");
        assertValidAddress("litecoin:ltc1q8tk47lvgqu55h4pfast39r3t9360gmll5z9m6z?time=1708476604&exp=600");
        assertValidAddress("litecoin:ltc1q026xyextkwhmveh7rpf6v6mp5p88vwc25aynxr?time=1708476626");
        assertValidAddress("Litecoin:LaRoRBC6utQtY3U2FbHwhmhhDPyxodDeKA");
        assertValidAddress("LITECOIN:MDMFP9Dx84tyaxiYksjvkG1jymBdqCuHGA");
        assertValidAddress("LITECOIN:ltc1qftddy5ghtxur954h3znmt3cx7feegux2twem2kadkx9xqp0s50yqetxp0e?time=1708476729&exp=86400");
        assertValidAddress("MADpfTtabZ6pDjms4pMd3ZmnrgyhTCo4N8?time=1708476729&exp=86400");
        assertValidAddress("3MSvaVbVFFLML86rt5eqgA9SvW23upaXdY");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("1LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW");
        assertInvalidAddress("LgfapHEPhZbdRF9pMd5WPT35hFXcZS1USrW");
        assertInvalidAddress("LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW#");
        assertInvalidAddress("3MSvaVbVFFLML86rt5eqgl9SvW23upaXdY"); // contains lowercase l
        assertInvalidAddress("LURw7hYhREXjWHyiXhQNsKInWtPezwNe98"); // contains uppercase I
        assertInvalidAddress("LM4ch8ZtAowdiGLSnf92MrMOC9dVmve2hr"); // contains uppercase O
        assertInvalidAddress("MArsfeyS7P0HzsqLpAFGC9pFdhuqHgdL2R"); // contains number 0
        assertInvalidAddress("ltc1qr6quwn3v2gxpadd0cu040r9385gayk5vdcyl5"); // too short
        assertInvalidAddress("ltc1q5det08ke2gpet06wczcdfs2v3hgfqllxw28uln8vxxx82qlue6uswceljma"); // too long
    }
}
