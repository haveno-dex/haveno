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

package haveno.daemon.grpc;

import com.google.inject.Inject;
import haveno.common.config.Config;
import haveno.core.api.CoreApi;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.CoreProtoResolver;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.CreateCryptoCurrencyPaymentAccountReply;
import haveno.proto.grpc.CreateCryptoCurrencyPaymentAccountRequest;
import haveno.proto.grpc.CreatePaymentAccountReply;
import haveno.proto.grpc.CreatePaymentAccountRequest;
import haveno.proto.grpc.GetCryptoCurrencyPaymentMethodsReply;
import haveno.proto.grpc.GetCryptoCurrencyPaymentMethodsRequest;
import haveno.proto.grpc.GetPaymentAccountFormReply;
import haveno.proto.grpc.GetPaymentAccountFormRequest;
import haveno.proto.grpc.GetPaymentAccountsReply;
import haveno.proto.grpc.GetPaymentAccountsRequest;
import haveno.proto.grpc.GetPaymentMethodsReply;
import haveno.proto.grpc.GetPaymentMethodsRequest;
import haveno.proto.grpc.PaymentAccountsGrpc.PaymentAccountsImplBase;
import static haveno.proto.grpc.PaymentAccountsGrpc.getCreateCryptoCurrencyPaymentAccountMethod;
import static haveno.proto.grpc.PaymentAccountsGrpc.getCreatePaymentAccountMethod;
import static haveno.proto.grpc.PaymentAccountsGrpc.getGetPaymentAccountFormMethod;
import static haveno.proto.grpc.PaymentAccountsGrpc.getGetPaymentAccountsMethod;
import static haveno.proto.grpc.PaymentAccountsGrpc.getGetPaymentMethodsMethod;
import haveno.proto.grpc.ValidateFormFieldReply;
import haveno.proto.grpc.ValidateFormFieldRequest;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcPaymentAccountsService extends PaymentAccountsImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcPaymentAccountsService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void createPaymentAccount(CreatePaymentAccountRequest req,
                                     StreamObserver<CreatePaymentAccountReply> responseObserver) {
        try {
            PaymentAccount paymentAccount = coreApi.createPaymentAccount(PaymentAccountForm.fromProto(req.getPaymentAccountForm()));
            var reply = CreatePaymentAccountReply.newBuilder()
                    .setPaymentAccount(paymentAccount.toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getPaymentAccounts(GetPaymentAccountsRequest req,
                                   StreamObserver<GetPaymentAccountsReply> responseObserver) {
        try {
            var paymentAccounts = coreApi.getPaymentAccounts().stream()
                    .map(PaymentAccount::toProtoMessage)
                    .collect(Collectors.toList());
            var reply = GetPaymentAccountsReply.newBuilder()
                    .addAllPaymentAccounts(paymentAccounts).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getPaymentMethods(GetPaymentMethodsRequest req,
                                  StreamObserver<GetPaymentMethodsReply> responseObserver) {
        try {
            var paymentMethods = coreApi.getPaymentMethods().stream()
                    .map(PaymentMethod::toProtoMessage)
                    .collect(Collectors.toList());
            var reply = GetPaymentMethodsReply.newBuilder()
                    .addAllPaymentMethods(paymentMethods).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getPaymentAccountForm(GetPaymentAccountFormRequest req,
                                      StreamObserver<GetPaymentAccountFormReply> responseObserver) {
        try {
            PaymentAccountForm form = null;
            if (req.getPaymentMethodId().isEmpty()) {
                PaymentAccount account = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(req.getPaymentAccountPayload().getPaymentMethodId()));
                account.setAccountName("tmp");
                account.init(PaymentAccountPayload.fromProto(req.getPaymentAccountPayload(), new CoreProtoResolver()));
                account.setAccountName(null);
                form = coreApi.getPaymentAccountForm(account);
            } else {
                form = coreApi.getPaymentAccountForm(req.getPaymentMethodId());
            }
            var reply = GetPaymentAccountFormReply.newBuilder()
                    .setPaymentAccountForm(form.toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void createCryptoCurrencyPaymentAccount(CreateCryptoCurrencyPaymentAccountRequest req,
                                                   StreamObserver<CreateCryptoCurrencyPaymentAccountReply> responseObserver) {
        try {
            PaymentAccount paymentAccount = coreApi.createCryptoCurrencyPaymentAccount(req.getAccountName(),
                    req.getCurrencyCode(),
                    req.getAddress(),
                    req.getTradeInstant());
            var reply = CreateCryptoCurrencyPaymentAccountReply.newBuilder()
                    .setPaymentAccount(paymentAccount.toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getCryptoCurrencyPaymentMethods(GetCryptoCurrencyPaymentMethodsRequest req,
                                                StreamObserver<GetCryptoCurrencyPaymentMethodsReply> responseObserver) {
        try {
            var paymentMethods = coreApi.getCryptoCurrencyPaymentMethods().stream()
                    .map(PaymentMethod::toProtoMessage)
                    .collect(Collectors.toList());
            var reply = GetCryptoCurrencyPaymentMethodsReply.newBuilder()
                    .addAllPaymentMethods(paymentMethods).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void validateFormField(ValidateFormFieldRequest req,
                                                StreamObserver<ValidateFormFieldReply> responseObserver) {
        try {
            coreApi.validateFormField(PaymentAccountForm.fromProto(req.getForm()), PaymentAccountFormField.FieldId.fromProto(req.getFieldId()), req.getValue());
            var reply = ValidateFormFieldReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    final ServerInterceptor[] interceptors() {
        Optional<ServerInterceptor> rateMeteringInterceptor = rateMeteringInterceptor();
        return rateMeteringInterceptor.map(serverInterceptor ->
                new ServerInterceptor[]{serverInterceptor}).orElseGet(() -> new ServerInterceptor[0]);
    }

    final Optional<ServerInterceptor> rateMeteringInterceptor() {
        return getCustomRateMeteringInterceptor(coreApi.getConfig().appDataDir, this.getClass())
                .or(() -> Optional.of(CallRateMeteringInterceptor.valueOf(
                        new HashMap<>() {{
                            put(getCreatePaymentAccountMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 100 : 1, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getCreateCryptoCurrencyPaymentAccountMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 100 : 1, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getGetPaymentAccountsMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 100 : 1, SECONDS));
                            put(getGetPaymentMethodsMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 100 : 1, SECONDS));
                            put(getGetPaymentAccountFormMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 100 : 1, SECONDS));
                        }}
                )));
    }
}
