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

package haveno.daemon.grpc.interceptor;

import haveno.common.config.Config;
import io.grpc.Metadata;
import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasswordAuthInterceptorTest {

    private static final Metadata.Key<String> PASSWORD_KEY = Metadata.Key.of("password", ASCII_STRING_MARSHALLER);

    @TempDir
    File tempDir;

    private PasswordAuthInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        Config config = new Config("--appDataDir=" + tempDir.getAbsolutePath(), "--apiPassword=secret");
        interceptor = new PasswordAuthInterceptor(config);
    }

    @Test
    public void whenPasswordIsCorrect_thenCallIsAuthorized() {
        assertDoesNotThrow(() -> intercept("secret"));
    }

    @Test
    public void whenPasswordHeaderIsMissing_thenCallIsRejected() {
        assertTrue(interceptError(null).contains("missing"));
    }

    @Test
    public void whenPasswordIsIncorrect_thenSubsequentAttemptsAreLockedOut() throws Exception {
        assertTrue(interceptError("wrong").contains("incorrect"));
        assertTrue(interceptError("secret").contains("too many failed attempts")); // locked out, unevaluated
        Thread.sleep(1100); // initial lockout is 1s
        assertDoesNotThrow(() -> intercept("secret"));
        assertDoesNotThrow(() -> intercept("secret")); // lockout reset on success
    }

    private void intercept(String password) {
        Metadata headers = new Metadata();
        if (password != null) headers.put(PASSWORD_KEY, password);
        interceptor.interceptCall(null, headers, (call, metadata) -> null);
    }

    private String interceptError(String password) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> intercept(password));
        assertEquals(Status.UNAUTHENTICATED.getCode(), exception.getStatus().getCode());
        return exception.getStatus().getDescription();
    }
}
