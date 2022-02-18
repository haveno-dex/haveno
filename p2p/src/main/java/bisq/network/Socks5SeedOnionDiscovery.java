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

package bisq.network;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.net.InetSocketAddress;

import java.util.concurrent.TimeUnit;

/**
 * Socks5SeedOnionDiscovery provides a list of known Bitcoin .onion seeds.
 * These are nodes running as hidden services on the Tor network.
 */
public class Socks5SeedOnionDiscovery implements PeerDiscovery {
    private InetSocketAddress[] seedAddrs;

    /**
     * Supports finding peers by hostname over a socks5 proxy.
     *
     * @param proxy  proxy the socks5 proxy to connect over.
     * @param params param to be used for seed and port information.
     */
    public Socks5SeedOnionDiscovery(@SuppressWarnings("UnusedParameters") Socks5Proxy proxy, NetworkParameters params) {
        // We do this because NetworkParameters does not contain any .onion
        // seeds.  Perhaps someday...
        String[] seedAddresses = {};
        switch (params.getId()) {
            case NetworkParameters.ID_MAINNET:
                seedAddresses = mainNetSeeds();
                break;
        /*
            case NetworkParameters.ID_TESTNET:
                seedAddresses = testNet3Seeds();
                break;

         */
        }

        this.seedAddrs = convertAddrsString(seedAddresses, params.getPort());
    }

    /**
     * returns .onion nodes available on mainnet
     */
    private String[] mainNetSeeds() {
        // List copied from bitcoin-core on 2017-11-03
        // https://raw.githubusercontent.com/bitcoin/bitcoin/master/contrib/seeds/nodes_main.txt

        return new String[]{
            "2g5qfdkn2vvcbqhzcyvyiitg4ceukybxklraxjnu7atlhd22gdwywaid.onion",
            "2jmtxvyup3ijr7u6uvu7ijtnojx4g5wodvaedivbv74w4vzntxbrhvad.onion",
            "37m62wn7dz3uqpathpc4qfmgrbupachj52nt3jbtbjugpbu54kbud7yd.onion",
            "5g72ppm3krkorsfopcm2bi7wlv4ohhs4u4mlseymasn7g7zhdcyjpfid.onion",
            "7cgwjuwi5ehvcay4tazy7ya6463bndjk6xzrttw5t3xbpq4p22q6fyid.onion",
            "7pyrpvqdhmayxggpcyqn5l3m5vqkw3qubnmgwlpya2mdo6x7pih7r7id.onion",
            "b64xcbleqmwgq2u46bh4hegnlrzzvxntyzbmucn3zt7cssm7y4ubv3id.onion",
            "ejxefzf5fpst4mg2rib7grksvscl7p6fvjp6agzgfc2yglxnjtxc3aid.onion",
            "fjdyxicpm4o42xmedlwl3uvk5gmqdfs5j37wir52327vncjzvtpfv7yd.onion",
            "fpz6r5ppsakkwypjcglz6gcnwt7ytfhxskkfhzu62tnylcknh3eq6pad.onion",
            "fzhn4uoxfbfss7h7d6ffbn266ca432ekbbzvqtsdd55ylgxn4jucm5qd.onion",
            "gxo5anvfnffnftfy5frkgvplq3rpga2ie3tcblo2vl754fvnhgorn5yd.onion",
            "ifdu5qvbofrt4ekui2iyb3kbcyzcsglazhx2hn4wfskkrx2v24qxriid.onion",
            "itz3oxsihs62muvknc237xabl5f6w6rfznfhbpayrslv2j2ubels47yd.onion",
            "lrjh6fywjqttmlifuemq3puhvmshxzzyhoqx7uoufali57eypuenzzid.onion",
            "m7cbpjolo662uel7rpaid46as2otcj44vvwg3gccodnvaeuwbm3anbyd.onion",
            "opnyfyeiibe5qo5a3wbxzbb4xdiagc32bbce46owmertdknta5mi7uyd.onion",
            "owjsdxmzla6d7lrwkbmetywqym5cyswpihciesfl5qdv2vrmwsgy4uqd.onion",
            "q7kgmd7n7h27ds4fg7wocgniuqb3oe2zxp4nfe4skd5da6wyipibqzqd.onion",
            "rp7k2go3s5lyj3fnj6zn62ktarlrsft2ohlsxkyd7v3e3idqyptvread.onion",
            "sys54sv4xv3hn3sdiv3oadmzqpgyhd4u4xphv4xqk64ckvaxzm57a7yd.onion",
            "tddeij4qigtjr6jfnrmq6btnirmq5msgwcsdpcdjr7atftm7cxlqztid.onion",
            "vi5bnbxkleeqi6hfccjochnn65lcxlfqs4uwgmhudph554zibiusqnad.onion",
            "xqt25cobm5zqucac3634zfght72he6u3eagfyej5ellbhcdgos7t2had.onion"
        };
    }

    /**
     * returns .onion nodes available on testnet3
     */
    // No new V3 test seeds. Disabling...
    /*
    private String[] testNet3Seeds() {
        // this list copied from bitcoin-core on 2017-01-19
        //   https://github.com/bitcoin/bitcoin/blob/57b34599b2deb179ff1bd97ffeab91ec9f904d85/contrib/seeds/nodes_test.txt
        return new String[]{
                "thfsmmn2jbitcoin.onion",
                "it2pj4f7657g3rhi.onion",
                "nkf5e6b7pl4jfd4a.onion",
                "4zhkir2ofl7orfom.onion",
                "t6xj6wilh4ytvcs7.onion",
                "i6y6ivorwakd7nw3.onion",
                "ubqj4rsu3nqtxmtp.onion"
        };
    }
    */
    /**
     * Returns an array containing all the Bitcoin nodes within the list.
     */
    @Override
    public InetSocketAddress[] getPeers(long services, long timeoutValue, TimeUnit timeoutUnit) throws PeerDiscoveryException {
        if (services != 0)
            throw new PeerDiscoveryException("DNS seeds cannot filter by services: " + services);
        return seedAddrs;
    }

    /**
     * Converts an array of hostnames to array of unresolved InetSocketAddress
     */
    private InetSocketAddress[] convertAddrsString(String[] addrs, int port) {
        InetSocketAddress[] list = new InetSocketAddress[addrs.length];
        for (int i = 0; i < addrs.length; i++) {
            list[i] = InetSocketAddress.createUnresolved(addrs[i], port);
        }
        return list;
    }

    @Override
    public void shutdown() {
        //TODO should we add a DnsLookupTor.shutdown() ?
    }
}
