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

package haveno.daemon.grpc.interceptor;

import com.google.inject.Inject;
import haveno.common.config.Config;
import io.grpc.Metadata;
import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static io.grpc.Metadata.Key;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import static io.grpc.Status.UNAUTHENTICATED;
import io.grpc.StatusRuntimeException;
import static java.lang.String.format;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.extern.slf4j.Slf4j;

/**
 * Authorizes rpc server calls by comparing the value of the caller's
 * {@value PASSWORD_KEY} header to an expected value set at server startup time.
 *
 * @see haveno.common.config.Config#apiPassword
 */
@Slf4j
public class PasswordAuthInterceptor implements ServerInterceptor {

    private static final String PASSWORD_KEY = "password";
    private static final long MIN_LOCKOUT_MS = 1000;
    private static final long MAX_LOCKOUT_MS = 60000;

    private final byte[] expectedPasswordHash;
    private long lockedUntilMs = nowMs();
    private long nextLockoutMs = MIN_LOCKOUT_MS;

    @Inject
    public PasswordAuthInterceptor(Config config) {
        this.expectedPasswordHash = sha256(config.apiPassword);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> serverCall,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> serverCallHandler) {
        var actualPasswordValue = headers.get(Key.of(PASSWORD_KEY, ASCII_STRING_MARSHALLER));

        if (actualPasswordValue == null)
            throw new StatusRuntimeException(UNAUTHENTICATED.withDescription(
                    format("missing '%s' rpc header value", PASSWORD_KEY)));

        verifyPassword(actualPasswordValue);
        return serverCallHandler.startCall(serverCall, headers);
    }

    // verify by comparing fixed-length hashes in constant time, and fail fast unevaluated during a lockout which doubles
    // on each failure, so an attacker gets one guess per lockout period without being able to exhaust server threads
    private synchronized void verifyPassword(String actualPasswordValue) {
        long now = nowMs();
        if (now - lockedUntilMs < 0)
            throw new StatusRuntimeException(UNAUTHENTICATED.withDescription(
                    format("too many failed attempts, try again in %d seconds", (lockedUntilMs - now + 999) / 1000)));

        if (!MessageDigest.isEqual(expectedPasswordHash, sha256(actualPasswordValue))) {
            lockedUntilMs = now + nextLockoutMs;
            nextLockoutMs = Math.min(nextLockoutMs * 2, MAX_LOCKOUT_MS);
            log.warn("Failed api password attempt, locking out attempts for {} ms", lockedUntilMs - now);
            throw new StatusRuntimeException(UNAUTHENTICATED.withDescription(
                    format("incorrect '%s' rpc header value", PASSWORD_KEY)));
        }
        nextLockoutMs = MIN_LOCKOUT_MS;
    }

    // monotonic clock, unaffected by wall clock adjustments
    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is required on every JVM
        }
    }
}
