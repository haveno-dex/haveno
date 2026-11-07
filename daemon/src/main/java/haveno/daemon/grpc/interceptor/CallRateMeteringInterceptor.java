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

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static io.grpc.Status.PERMISSION_DENIED;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

@Slf4j
public final class CallRateMeteringInterceptor implements ServerInterceptor {

    // Maps the gRPC server method names to rate meters.  This allows one interceptor
    // instance to handle rate metering for any or all the methods in a Grpc*Service.
    protected final Map<String, GrpcCallRateMeter> serviceCallRateMeters;

    public CallRateMeteringInterceptor(Map<String, GrpcCallRateMeter> serviceCallRateMeters) {
        this.serviceCallRateMeters = serviceCallRateMeters;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> serverCall,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> serverCallHandler) {
        Optional<Map.Entry<String, GrpcCallRateMeter>> rateMeterKV = getRateMeterKV(serverCall);
        if (rateMeterKV.isPresent()) {
            // a closed call must not reach the handler, so hand back a listener that ignores the request
            if (!checkRateMeterAndMaybeCloseCall(rateMeterKV.get(), serverCall)) return new ServerCall.Listener<>() {};
        } else {
            handleMissingRateMeterConfiguration(serverCall);
        }
        return serverCallHandler.startCall(serverCall, headers);
    }

    // Returns whether the call is within its rate limit, otherwise it has been closed.
    private boolean checkRateMeterAndMaybeCloseCall(Map.Entry<String, GrpcCallRateMeter> rateMeterKV,
                                                    ServerCall<?, ?> serverCall) {
        String methodName = rateMeterKV.getKey();
        GrpcCallRateMeter rateMeter = rateMeterKV.getValue();

        if (!rateMeter.checkAndIncrement()) {
            handlePermissionDeniedWarningAndCloseCall(methodName, rateMeter, serverCall);
            return false;
        }
        log.debug(rateMeter.getCallsCountProgress(methodName));
        return true;
    }

    private void handleMissingRateMeterConfiguration(ServerCall<?, ?> serverCall)
            throws StatusRuntimeException {
        log.debug("The gRPC service's call rate metering interceptor does not"
                        + " meter the {} method.",
                getRateMeterKey(serverCall));
    }

    private void handlePermissionDeniedWarningAndCloseCall(String methodName,
                                                           GrpcCallRateMeter rateMeter,
                                                           ServerCall<?, ?> serverCall)
            throws StatusRuntimeException {
        String msg = getDefaultRateExceededError(methodName, rateMeter);
        log.warn(msg + ".");
        serverCall.close(PERMISSION_DENIED.withDescription(msg.toLowerCase()), new Metadata());
    }

    private String getDefaultRateExceededError(String methodName,
                                               GrpcCallRateMeter rateMeter) {
        // The derived method name may not be an exact match to CLI's method name.
        String timeUnitName = StringUtils.chop(rateMeter.getTimeUnit().name().toLowerCase());
        // Just print 'getversion', not the grpc method descriptor's
        // full-method-name: 'io.haveno.protobuffer.getversion/getversion'.
        String loggedMethodName = methodName.split("/")[1];
        return format("The maximum allowed number of %s calls (%d/%s) has been exceeded",
                loggedMethodName,
                rateMeter.getAllowedCallsPerTimeWindow(),
                timeUnitName);
    }

    private Optional<Map.Entry<String, GrpcCallRateMeter>> getRateMeterKV(ServerCall<?, ?> serverCall) {
        String rateMeterKey = getRateMeterKey(serverCall);
        return serviceCallRateMeters.entrySet().stream()
                .filter((e) -> e.getKey().equals(rateMeterKey)).findFirst();
    }

    private String getRateMeterKey(ServerCall<?, ?> serverCall) {
        // Get the rate meter map key from the server call method descriptor.  The
        // returned String (e.g., 'io.haveno.protobuffer.Offers/CreateOffer') will match
        // a map entry key in the 'serviceCallRateMeters' constructor argument, if it
        // was defined in the Grpc*Service class' rateMeteringInterceptor method.
        return serverCall.getMethodDescriptor().getFullMethodName();
    }

    @Override
    public String toString() {
        String rateMetersString =
                serviceCallRateMeters.entrySet()
                        .stream()
                        .map(Object::toString)
                        .collect(joining("\n\t\t"));
        return "CallRateMeteringInterceptor {" + "\n\t" +
                "serviceCallRateMeters {" + "\n\t\t" +
                rateMetersString + "\n\t" + "}" + "\n"
                + "}";
    }

    public static CallRateMeteringInterceptor valueOf(Map<String, GrpcCallRateMeter> rateMeters) {
        return new CallRateMeteringInterceptor(new HashMap<>() {{
            putAll(rateMeters);
        }});
    }
}
