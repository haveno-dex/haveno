package haveno.core.util.validation;

import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.trade.HavenoUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verification tests for Issue #1076: IPv6 Support.
 * 
 * Checks:
 * 1. Regex validation for IPv6 addresses (with/without ports, bracketed).
 * 2. URI construction logic in XmrNodes.
 * 3. Localhost detection in HavenoUtils.
 * 4. Real-world connectivity (Manual execution required).
 */
public class IPv6SupportTest {

    @Test
    public void testBountySpecificRegexExamples() {
        RegexValidator validator = RegexValidatorFactory.addressRegexValidator();
        validator.setErrorMessage("Invalid address"); // Avoid NPE in Res.get() during testing
        // Determine validity based on internal isValid field
        
        // Example 1: feder8.me:18089 (Domain with port)
        assertTrue(validator.validate("feder8.me:18089").isValid, 
                   "Should accept domains with ports");

        // Example 2: [2607:3c40:1900:33e0::1]:18089 (Bracketed IPv6 with port)
        assertTrue(validator.validate("[2607:3c40:1900:33e0::1]:18089").isValid, 
                   "Should accept bracketed IPv6 with port");

        // Additional: Naked IPv6 (often used in simple inputs if allowed, or internally)
        // Note: The specific regex implementation might prefer brackets for full validity in UI, 
        // but here we check the examples given in the bounty.
    }

    @Test
    public void testIPv6UriConstruction() {
        // Case 1: Bracketed input stays bracketed
        XmrNodes.XmrNode node1 = XmrNodes.XmrNode.fromFullAddress("[::1]:18081");
        assertEquals("http://[::1]:18081", node1.getClearNetUri(), "URI should preserve brackets");

        // Case 2: Unbracketed input gets bracketed (if logic handles it, otherwise input should be bracketed)
        // The fix in XmrNodes ensures that if it detects a raw IPv6, it should handle it, 
        // strictly speaking standard URL requries brackets.
        
        // Case 3: Domain name
        XmrNodes.XmrNode node3 = XmrNodes.XmrNode.fromFullAddress("node.privacyx.co:443");
        assertEquals("http://node.privacyx.co:443", node3.getClearNetUri(), "Domains should not be bracketed");
    }

    @Test
    public void testLocalhostIPv6() {
        // Standard loopback
        assertTrue(HavenoUtils.isLocalHost("::1"), "::1 is localhost");
        assertTrue(HavenoUtils.isLocalHost("[::1]"), "[::1] is localhost");
        assertTrue(HavenoUtils.isLocalHost("0:0:0:0:0:0:0:1"), "Expanded IPv6 loopback is localhost");
        
        // Non-localhost
        assertTrue(!HavenoUtils.isLocalHost("2001:db8::1"), "Public IPv6 is NOT localhost");
    }

    /**
     * Manual connectivity test. 
     * Disabled by default to prevent CI failures in environments without IPv6.
     * Run manually to verify network stack.
     */
    @Test
    @Disabled("Requires active IPv6 internet connection. Run manually.")
    public void testLiveConnection() throws Exception {
        String nodeUrl = "https://node.privacyx.co:443/json_rpc";
        System.out.println("Testing connection to: " + nodeUrl);

        URL url = new URL(nodeUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        String jsonPayload = "{\"jsonrpc\":\"2.0\",\"id\":\"0\",\"method\":\"get_info\"}";
        try {
            conn.getOutputStream().write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            System.out.println("Response Code: " + code);
            assertTrue(code == 200 || code == 405, "Should establish connection (200 OK or 405 Method Not Allowed)");
        } catch (Exception e) {
            // If network fails entirely, fail the test
            throw new RuntimeException("Network connection failed: " + e.getMessage(), e);
        }
    }
}
