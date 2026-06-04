package haveno.network.http;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeDnsResolverTest {

    private final FakeDnsResolver resolver = new FakeDnsResolver();

    @Test
    void resolveHostDoesNotPerformLocalLookup() throws Exception {
        assertNull(resolver.resolve("example.onion"));
    }

    @Test
    void resolveHostAndPortReturnsUnresolvedAddress() throws Exception {
        InetSocketAddress address = resolver.resolve("example.onion", 80).get(0);
        assertNull(address.getAddress(), "Tor SOCKS requires unresolved hostname for remote lookup");
        assertEquals("example.onion", address.getHostString());
        assertEquals(80, address.getPort());
    }
}
