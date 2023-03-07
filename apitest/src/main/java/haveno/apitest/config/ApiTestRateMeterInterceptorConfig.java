package haveno.apitest.config;

import java.io.File;

import static haveno.proto.grpc.DisputeAgentsGrpc.getRegisterDisputeAgentMethod;
import static haveno.proto.grpc.GetVersionGrpc.getGetVersionMethod;
import static haveno.apitest.config.ApiTestConfig.CALL_RATE_METERING_CONFIG_PATH;
import static java.lang.System.arraycopy;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import haveno.daemon.grpc.GrpcVersionService;
import haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig;

public class ApiTestRateMeterInterceptorConfig {

    public static File getTestRateMeterInterceptorConfig() {
        GrpcServiceRateMeteringConfig.Builder builder = new GrpcServiceRateMeteringConfig.Builder();
        builder.addCallRateMeter(GrpcVersionService.class.getSimpleName(),
                getGetVersionMethod().getFullMethodName(),
                1,
                SECONDS);
        // Only GrpcVersionService is @VisibleForTesting, so we need to
        // hardcode other grpcServiceClassName parameter values used in
        // builder.addCallRateMeter(...).
        builder.addCallRateMeter("GrpcDisputeAgentsService",
                getRegisterDisputeAgentMethod().getFullMethodName(),
                10, // Same as default.
                SECONDS);
        // Define rate meters for non-existent method 'disabled', to override other grpc
        // services' default rate meters -- defined in their rateMeteringInterceptor()
        // methods.
        String[] serviceClassNames = new String[]{
                "GrpcGetTradeStatisticsService",
                "GrpcHelpService",
                "GrpcOffersService",
                "GrpcPaymentAccountsService",
                "GrpcPriceService",
                "GrpcTradesService",
                "GrpcWalletsService"
        };
        for (String service : serviceClassNames) {
            builder.addCallRateMeter(service, "disabled", 1, MILLISECONDS);
        }
        File file = builder.build();
        file.deleteOnExit();
        return file;
    }

    public static boolean hasCallRateMeteringConfigPathOpt(String[] args) {
        return stream(args).anyMatch(a -> a.contains("--" + CALL_RATE_METERING_CONFIG_PATH));
    }

    public static String[] appendCallRateMeteringConfigPathOpt(String[] args, File rateMeterInterceptorConfig) {
        String[] rateMeteringConfigPathOpt = new String[]{
                "--" + CALL_RATE_METERING_CONFIG_PATH + "=" + rateMeterInterceptorConfig.getAbsolutePath()
        };
        if (args.length == 0) {
            return rateMeteringConfigPathOpt;
        } else {
            String[] appendedOpts = new String[args.length + 1];
            arraycopy(args, 0, appendedOpts, 0, args.length);
            arraycopy(rateMeteringConfigPathOpt, 0, appendedOpts, args.length, rateMeteringConfigPathOpt.length);
            return appendedOpts;
        }
    }
}
