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

package haveno.core.trade.txproof;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies that a crypto payment matches a trade: chain, recipient, amount, confirmations,
 * token contract identity and age (the payment must not predate the trade).
 * <p>
 * UTXO chains (BTC, LTC) use an Esplora compatible REST API (electrs, mempool). EVM chains (ETH and
 * ERC-20 tokens) use standard JSON-RPC. Every inconsistency or RPC failure fails closed.
 */
@Slf4j
public class CryptoTxProofService {

    // canonical ethereum mainnet contracts, any other token contract is rejected
    static final Map<String, Erc20> ERC20_TOKENS = Map.of(
            "USDT-ERC20", new Erc20("0xdac17f958d2ee523a2206206994597c13d831ec7", 6),
            "USDC-ERC20", new Erc20("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", 6),
            "DAI-ERC20", new Erc20("0x6b175474e89094c44da98b954eedeb131c4f2d29", 18));
    static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    record Erc20(String contract, int decimals) {
    }

    public static boolean isSupported(String currencyCode) {
        return isUtxo(currencyCode) || isEvm(currencyCode);
    }

    static boolean isUtxo(String currencyCode) {
        return "BTC".equals(currencyCode) || "LTC".equals(currencyCode);
    }

    static boolean isEvm(String currencyCode) {
        return "ETH".equals(currencyCode) || ERC20_TOKENS.containsKey(currencyCode);
    }

    /**
     * Returns true if the service url does not leave the local machine or network, otherwise the user should be warned.
     */
    public static boolean isLocalServiceUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.endsWith(".local")
                    || host.startsWith("10.") || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
        } catch (Exception e) {
            return false;
        }
    }

    private final HttpClient httpClient;

    public CryptoTxProofService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    CryptoTxProofService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * @param currencyCode     BTC, LTC, ETH, USDT-ERC20, USDC-ERC20 or DAI-ERC20
     * @param serviceUrl       Esplora base url (UTXO) or JSON-RPC url (EVM)
     * @param txId             transaction id provided by the buyer
     * @param recipient        seller's receiving address from the seller's payment account
     * @param expectedAmount   trade volume in the asset's whole units
     * @param minConfirmations required confirmations
     * @param tradeStartSec    unix time of the trade start, the payment must not be older
     */
    public CryptoTxProofResult verify(String currencyCode, String serviceUrl, String txId, String recipient,
                                      BigDecimal expectedAmount, int minConfirmations, long tradeStartSec) {
        try {
            if (txId == null || !txId.matches("(0x)?[0-9a-fA-F]{64}")) return CryptoTxProofResult.failed("invalid transaction id");
            if (recipient == null || recipient.isBlank()) return CryptoTxProofResult.failed("missing recipient address");
            if (isUtxo(currencyCode)) return verifyUtxo(serviceUrl, txId, recipient, expectedAmount, minConfirmations, tradeStartSec);
            if (isEvm(currencyCode)) return verifyEvm(currencyCode, serviceUrl, txId, recipient, expectedAmount, minConfirmations, tradeStartSec);
            return CryptoTxProofResult.error("unsupported currency " + currencyCode);
        } catch (Exception e) {
            log.warn("Error verifying {} payment proof {}: {}", currencyCode, txId, e.getMessage());
            return CryptoTxProofResult.error("service failure: " + e.getMessage());
        }
    }

    private CryptoTxProofResult verifyUtxo(String baseUrl, String txId, String recipient, BigDecimal expectedAmount,
                                           int minConfirmations, long tradeStartSec) throws Exception {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        JsonObject tx = JsonParser.parseString(get(base + "/tx/" + txId)).getAsJsonObject();
        if (!txId.equalsIgnoreCase(tx.get("txid").getAsString())) return CryptoTxProofResult.error("service returned a different transaction");
        long expectedSats = expectedAmount.movePointRight(8).setScale(0, RoundingMode.CEILING).longValueExact();
        long paidSats = 0;
        for (JsonElement out : tx.getAsJsonArray("vout")) {
            JsonObject o = out.getAsJsonObject();
            if (o.has("scriptpubkey_address") && recipient.equals(o.get("scriptpubkey_address").getAsString())) paidSats += o.get("value").getAsLong();
        }
        if (paidSats == 0) return CryptoTxProofResult.failed("no output to the seller's address");
        if (paidSats < expectedSats) return CryptoTxProofResult.failed("underpaid: " + paidSats + " < " + expectedSats + " sats");
        JsonObject status = tx.getAsJsonObject("status");
        if (!status.get("confirmed").getAsBoolean()) return CryptoTxProofResult.pending("unconfirmed", 0);
        long tip = Long.parseLong(get(base + "/blocks/tip/height").trim());
        int confirmations = (int) (tip - status.get("block_height").getAsLong() + 1);
        if (status.get("block_time").getAsLong() < tradeStartSec) return CryptoTxProofResult.failed("payment predates the trade");
        if (confirmations < minConfirmations) return CryptoTxProofResult.pending(confirmations + "/" + minConfirmations + " confirmations", confirmations);
        return CryptoTxProofResult.verified(confirmations);
    }

    private CryptoTxProofResult verifyEvm(String currencyCode, String rpcUrl, String txId, String recipient, BigDecimal expectedAmount,
                                          int minConfirmations, long tradeStartSec) throws Exception {
        String hash = txId.startsWith("0x") ? txId : "0x" + txId;
        String to = recipient.toLowerCase(Locale.ROOT);
        JsonElement txEl = rpc(rpcUrl, "eth_getTransactionByHash", "[\"" + hash + "\"]");
        if (txEl == null || txEl.isJsonNull()) return CryptoTxProofResult.pending("transaction not found", 0);
        JsonObject tx = txEl.getAsJsonObject();
        JsonElement rcEl = rpc(rpcUrl, "eth_getTransactionReceipt", "[\"" + hash + "\"]");
        if (rcEl == null || rcEl.isJsonNull() || tx.get("blockNumber").isJsonNull()) return CryptoTxProofResult.pending("unconfirmed", 0);
        JsonObject receipt = rcEl.getAsJsonObject();
        if (hexToBigInteger(receipt.get("status").getAsString()).signum() == 0) return CryptoTxProofResult.failed("transaction reverted");
        BigDecimal paid;
        if ("ETH".equals(currencyCode)) {
            if (tx.get("to").isJsonNull() || !to.equals(tx.get("to").getAsString().toLowerCase(Locale.ROOT))) return CryptoTxProofResult.failed("recipient mismatch");
            paid = new BigDecimal(hexToBigInteger(tx.get("value").getAsString()), 18);
        } else {
            Erc20 token = ERC20_TOKENS.get(currencyCode);
            BigInteger sum = BigInteger.ZERO;
            JsonArray logs = receipt.getAsJsonArray("logs");
            for (JsonElement el : logs) {
                JsonObject logObj = el.getAsJsonObject();
                if (!token.contract().equals(logObj.get("address").getAsString().toLowerCase(Locale.ROOT))) continue; // reject fake tokens
                JsonArray topics = logObj.getAsJsonArray("topics");
                if (topics.size() != 3 || !TRANSFER_TOPIC.equals(topics.get(0).getAsString())) continue;
                String topicTo = "0x" + topics.get(2).getAsString().substring(26).toLowerCase(Locale.ROOT);
                if (to.equals(topicTo)) sum = sum.add(hexToBigInteger(logObj.get("data").getAsString()));
            }
            if (sum.signum() == 0) return CryptoTxProofResult.failed("no " + currencyCode + " transfer from the canonical contract to the seller");
            paid = new BigDecimal(sum, token.decimals());
        }
        if (paid.compareTo(expectedAmount) < 0) return CryptoTxProofResult.failed("underpaid: " + paid.toPlainString() + " < " + expectedAmount.toPlainString());
        BigInteger block = hexToBigInteger(tx.get("blockNumber").getAsString());
        BigInteger head = hexToBigInteger(rpc(rpcUrl, "eth_blockNumber", "[]").getAsString());
        int confirmations = head.subtract(block).intValueExact() + 1;
        JsonObject blockObj = rpc(rpcUrl, "eth_getBlockByNumber", "[\"0x" + block.toString(16) + "\", false]").getAsJsonObject();
        if (hexToBigInteger(blockObj.get("timestamp").getAsString()).longValueExact() < tradeStartSec) return CryptoTxProofResult.failed("payment predates the trade");
        if (confirmations < minConfirmations) return CryptoTxProofResult.pending(confirmations + "/" + minConfirmations + " confirmations", confirmations);
        return CryptoTxProofResult.verified(confirmations);
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        return response.body();
    }

    private JsonElement rpc(String url, String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("error") && !json.get("error").isJsonNull()) throw new IllegalStateException("RPC error: " + json.get("error"));
        return json.get("result");
    }

    static BigInteger hexToBigInteger(String hex) {
        return new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
    }
}
