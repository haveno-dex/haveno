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

package haveno.network.p2p.network;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.mocks.MockPayload;
import org.berndpruenster.netlayer.tor.Tor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TorNode created. Took 6 sec.
// Hidden service created. Took 40-50 sec.
// Connection establishment takes about 4 sec.
//TODO P2P network tests are outdated
@SuppressWarnings("ConstantConditions")
public class TorNetworkNodeTest {
    private static final Logger log = LoggerFactory.getLogger(TorNetworkNodeTest.class);
    private CountDownLatch latch;


    @Test
    public void testSocketClosePrecedesTorShutdownWithoutBlockingCaller() throws Exception {
        TorNetworkNodeNetlayer node = new TorNetworkNodeNetlayer(9001, TestUtils.getNetworkProtoResolver(),
                mock(TorMode.class), null, 2, false, null, null, "127.0.0.1");
        Tor tor = mock(Tor.class);
        setField(TorNetworkNodeNetlayer.class, node, "tor", tor);
        ServerSocket socket = mock(ServerSocket.class);
        Server server = new Server(socket, mock(MessageListener.class), mock(ConnectionListener.class),
                TestUtils.getNetworkProtoResolver(), null);
        setField(NetworkNode.class, node, "server", server);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        Executor originalExecutor = UserThread.getExecutor();
        // Completion runs on another thread, outside the thread-local static mock.
        UserThread.setExecutor(Runnable::run);
        doAnswer(invocation -> {
            closeStarted.countDown();
            assertTrue(releaseClose.await(5, TimeUnit.SECONDS));
            return null;
        }).when(socket).close();
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(timeout.capture(), anyLong())).thenReturn(mock(Timer.class));
            long started = System.nanoTime();
            node.shutDown(() -> {
                completions.incrementAndGet();
                finished.countDown();
            }, errorMessage -> { throw new AssertionError(errorMessage); });
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            verify(tor, never()).shutdown();
            assertEquals(1, finished.getCount());
            timeout.getValue().run();
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            releaseClose.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            timeout.getValue().run();
            assertEquals(1, completions.get());
            verify(tor).shutdown();
            assertTrue(node.executor.isTerminated());
        } finally {
            releaseClose.countDown();
            UserThread.setExecutor(originalExecutor);
        }
    }

    @Test
    public void testShutdownTimeoutRejectsCleanupUntilSocketCloseFinishes() throws Exception {
        TorNetworkNodeNetlayer node = new TorNetworkNodeNetlayer(9001, TestUtils.getNetworkProtoResolver(),
                mock(TorMode.class), null, 2, false, null, null, "127.0.0.1");
        Tor tor = mock(Tor.class);
        setField(TorNetworkNodeNetlayer.class, node, "tor", tor);
        ServerSocket socket = mock(ServerSocket.class);
        Server server = new Server(socket, mock(MessageListener.class), mock(ConnectionListener.class),
                TestUtils.getNetworkProtoResolver(), null);
        setField(NetworkNode.class, node, "server", server);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        Runnable cleanup = mock(Runnable.class);
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        Executor originalExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        doAnswer(invocation -> {
            closeStarted.countDown();
            assertTrue(releaseClose.await(15, TimeUnit.SECONDS));
            return null;
        }).when(socket).close();
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(timeout.capture(), anyLong())).thenReturn(mock(Timer.class));
            node.shutDown(cleanup, errorMessage -> {
                failure.handleErrorMessage(errorMessage);
                failed.countDown();
            });
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            ErrorMessageHandler repeatedFailure = mock(ErrorMessageHandler.class);
            node.shutDown(cleanup, repeatedFailure);
            verify(repeatedFailure).handleErrorMessage("Tor shutdown is still in progress");

            timeout.getValue().run();
            assertTrue(failed.await(8, TimeUnit.SECONDS));
            verify(cleanup, never()).run();
            verify(failure).handleErrorMessage("Tor shutdown is still in progress");
            verify(tor, never()).shutdown();
            Runnable bestEffort = mock(Runnable.class);
            node.shutDown(bestEffort);
            verify(bestEffort).run();

            releaseClose.countDown();
            Field executorField = TorNetworkNodeNetlayer.class.getDeclaredField("shutDownExecutor");
            executorField.setAccessible(true);
            assertTrue(((ExecutorService) executorField.get(node)).awaitTermination(3, TimeUnit.SECONDS));
            verify(tor).shutdown();
            node.shutDown(cleanup, failure);
            verify(cleanup).run();
            verify(failure).handleErrorMessage("Tor shutdown is still in progress");
        } finally {
            releaseClose.countDown();
            UserThread.setExecutor(originalExecutor);
        }
    }

    @Test
    public void testTorShutdownFailureRejectsCleanup() throws Exception {
        TorNetworkNodeNetlayer node = new TorNetworkNodeNetlayer(9001, TestUtils.getNetworkProtoResolver(),
                mock(TorMode.class), null, 2, false, null, null, "127.0.0.1");
        Tor tor = mock(Tor.class);
        setField(TorNetworkNodeNetlayer.class, node, "tor", tor);
        doThrow(new IllegalStateException("Cannot stop Tor")).when(tor).shutdown();
        CountDownLatch failed = new CountDownLatch(1);
        Runnable cleanup = mock(Runnable.class);
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        Executor originalExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), anyLong())).thenReturn(mock(Timer.class));
            node.shutDown(cleanup, errorMessage -> {
                failure.handleErrorMessage(errorMessage);
                failed.countDown();
            });
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            verify(cleanup, never()).run();
            verify(failure).handleErrorMessage("Tor shutdown failed");
        } finally {
            UserThread.setExecutor(originalExecutor);
        }
    }

    @Test
    public void testDiscardedTorShutdownFailureRejectsCleanup() throws Throwable {
        TorMode mode = mock(TorMode.class);
        TorNetworkNodeNetlayer node = new TorNetworkNodeNetlayer(9001, TestUtils.getNetworkProtoResolver(),
                mode, null, 2, false, null, null, "127.0.0.1");
        Tor tor = mock(Tor.class);
        doThrow(new IllegalStateException("Cannot stop discarded Tor")).when(tor).shutdown();
        CountDownLatch startupStarted = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        when(mode.getTor()).thenAnswer(invocation -> {
            startupStarted.countDown();
            assertTrue(Uninterruptibles.awaitUninterruptibly(releaseStartup, 10, TimeUnit.SECONDS));
            return tor;
        });
        CountDownLatch failed = new CountDownLatch(1);
        Runnable cleanup = mock(Runnable.class);
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        Executor originalExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), anyLong())).thenReturn(mock(Timer.class));
            node.publishHiddenService("discarded", 9002);
            assertTrue(startupStarted.await(2, TimeUnit.SECONDS));
            node.shutDown(cleanup, errorMessage -> {
                failure.handleErrorMessage(errorMessage);
                failed.countDown();
            });
            releaseStartup.countDown();
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            verify(tor).shutdown();
            verify(cleanup, never()).run();
            verify(failure).handleErrorMessage("Tor shutdown failed");
        } finally {
            releaseStartup.countDown();
            UserThread.setExecutor(originalExecutor);
        }
    }

    @Test
    public void testTorTimeoutWaitsForStartupCleanupAndCompletesOnce() throws Exception {
        TorNetworkNodeNetlayer node = new TorNetworkNodeNetlayer(9001, TestUtils.getNetworkProtoResolver(),
                mock(TorMode.class), null, 2, false, null, null, "127.0.0.1");
        CountDownLatch startupStarted = new CountDownLatch(1);
        CountDownLatch startupInterrupted = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        Executor originalExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        node.executor.execute(() -> {
            startupStarted.countDown();
            try {
                releaseStartup.await();
            } catch (InterruptedException e) {
                startupInterrupted.countDown();
                try {
                    assertTrue(releaseStartup.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(timeout.capture(), anyLong())).thenReturn(mock(Timer.class));
            assertTrue(startupStarted.await(2, TimeUnit.SECONDS));
            node.shutDown(() -> {
                completions.incrementAndGet();
                finished.countDown();
            });
            assertTrue(startupInterrupted.await(2, TimeUnit.SECONDS));
            timeout.getValue().run();
            assertEquals(1, finished.getCount());
            releaseStartup.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(1, completions.get());
            assertTrue(node.executor.isTerminated());
        } finally {
            releaseStartup.countDown();
            UserThread.setExecutor(originalExecutor);
        }
    }

    @Test
    public void testTorTimeoutWaitsForTeardownSubmission() throws Exception {
        TorNetworkNodeNetlayer node = spy(new TorNetworkNodeNetlayer(9001, TestUtils.getNetworkProtoResolver(),
                mock(TorMode.class), null, 2, false, null, null, "127.0.0.1"));
        Tor tor = mock(Tor.class);
        setField(TorNetworkNodeNetlayer.class, node, "tor", tor);
        Connection connection = mock(Connection.class);
        doReturn(Set.of(connection)).when(node).getAllConnections();
        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        ExecutorService gatedExecutor = mock(ExecutorService.class, delegatesTo(shutdownExecutor));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        CountDownLatch releaseSubmission = new CountDownLatch(1);
        CountDownLatch timeoutStarted = new CountDownLatch(1);
        CountDownLatch timeoutFinished = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        // Pause submission after teardown is claimed so the timeout can race with it.
        doAnswer(invocation -> {
            submissionStarted.countDown();
            assertTrue(releaseSubmission.await(5, TimeUnit.SECONDS));
            shutdownExecutor.execute(invocation.getArgument(0));
            return null;
        }).when(gatedExecutor).execute(any(Runnable.class));
        setField(TorNetworkNodeNetlayer.class, node, "shutDownExecutor", gatedExecutor);
        Executor originalExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
            ArgumentCaptor<Runnable> connectionClosed = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(timeout.capture(), anyLong())).thenReturn(mock(Timer.class));
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));
            node.shutDown(() -> {
                completions.incrementAndGet();
                finished.countDown();
            });
            verify(connection).shutDown(eq(CloseConnectionReason.APP_SHUT_DOWN), connectionClosed.capture());
            Future<?> closed = callers.submit(connectionClosed.getValue());
            assertTrue(submissionStarted.await(2, TimeUnit.SECONDS));
            Future<?> timedOut = callers.submit(() -> {
                timeoutStarted.countDown();
                timeout.getValue().run();
                timeoutFinished.countDown();
            });
            assertTrue(timeoutStarted.await(2, TimeUnit.SECONDS));
            assertFalse(timeoutFinished.await(100, TimeUnit.MILLISECONDS));
            assertFalse(shutdownExecutor.isShutdown());
            assertEquals(1, finished.getCount());
            releaseSubmission.countDown();
            closed.get(3, TimeUnit.SECONDS);
            timedOut.get(3, TimeUnit.SECONDS);
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            verify(tor).shutdown();
            assertEquals(1, completions.get());
            assertTrue(shutdownExecutor.isTerminated());
        } finally {
            releaseSubmission.countDown();
            callers.shutdownNow();
            shutdownExecutor.shutdownNow();
            node.executor.shutdownNow();
            callers.awaitTermination(3, TimeUnit.SECONDS);
            shutdownExecutor.awaitTermination(3, TimeUnit.SECONDS);
            UserThread.setExecutor(originalExecutor);
        }
    }

    private static void setField(Class<?> owner, Object object, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Disabled("P2P network test is outdated")
    @Test
    public void testTorNodeBeforeSecondReady() throws InterruptedException, IOException {
        latch = new CountDownLatch(1);
        int port = 9001;
        TorNetworkNode node1 = new TorNetworkNodeNetlayer(port,
                TestUtils.getNetworkProtoResolver(),
                new NewTor(new File("torNode_" + port), null, "", this::getBridgeAddresses),
                null,
                12,
                false,
                null,
                null,
                "127.0.0.1");
        node1.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onReadyForSendingMessages");
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onReadyForReceivingMessages");
                latch.countDown();
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
            }

            @Override
            public void onRequestCustomBridges() {

            }
        });
        latch.await();

        latch = new CountDownLatch(1);
        int port2 = 9002;
        TorNetworkNode node2 = new TorNetworkNodeNetlayer(port2,
                TestUtils.getNetworkProtoResolver(),
                new NewTor(new File("torNode_" + port), null, "", this::getBridgeAddresses),
                null,
                12,
                false,
                null,
                null,
                "127.0.0.1");
        node2.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onReadyForSendingMessages");
                latch.countDown();
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onReadyForReceivingMessages");

            }

            @Override
            public void onSetupFailed(Throwable throwable) {
            }

            @Override
            public void onRequestCustomBridges() {

            }
        });
        latch.await();


        latch = new CountDownLatch(2);
        node1.addMessageListener((message, connection) -> {
            log.debug("onMessage node1 " + message);
            latch.countDown();
        });
        SettableFuture<Connection> future = node2.sendMessage(node1.getNodeAddress(), new MockPayload("msg1"));
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                log.debug("onSuccess ");
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                log.debug("onFailure ");
            }
        }, MoreExecutors.directExecutor());
        latch.await();


        latch = new CountDownLatch(2);
        node1.shutDown(latch::countDown);
        node2.shutDown(latch::countDown);
        latch.await();
    }

    //@Test
    public void testTorNodeAfterBothReady() throws InterruptedException, IOException {
        latch = new CountDownLatch(2);
        int port = 9001;
        TorNetworkNode node1 = new TorNetworkNodeNetlayer(port,
                TestUtils.getNetworkProtoResolver(),
                new NewTor(new File("torNode_" + port), null, "", this::getBridgeAddresses),
                null,
                12,
                false,
                null,
                null,
                "127.0.0.1");
        node1.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onReadyForSendingMessages");
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onReadyForReceivingMessages");
                latch.countDown();
            }

            @Override
            public void onSetupFailed(Throwable throwable) {

            }

            @Override
            public void onRequestCustomBridges() {

            }
        });

        int port2 = 9002;
        TorNetworkNode node2 = new TorNetworkNodeNetlayer(port2, TestUtils.getNetworkProtoResolver(),
                new NewTor(new File("torNode_" + port), null, "", this::getBridgeAddresses),
                null,
                12,
                false,
                null,
                null,
                "127.0.0.1");
        node2.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onReadyForSendingMessages");
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onReadyForReceivingMessages");
                latch.countDown();
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
            }

            @Override
            public void onRequestCustomBridges() {

            }
        });

        latch.await();

        latch = new CountDownLatch(2);
        node2.addMessageListener((message, connection) -> {
            log.debug("onMessage node2 " + message);
            latch.countDown();
        });
        SettableFuture<Connection> future = node1.sendMessage(node2.getNodeAddress(), new MockPayload("msg1"));
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                log.debug("onSuccess ");
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                log.debug("onFailure ");
            }
        }, MoreExecutors.directExecutor());
        latch.await();


        latch = new CountDownLatch(2);
        node1.shutDown(latch::countDown);
        node2.shutDown(latch::countDown);
        latch.await();
    }

    public List<String> getBridgeAddresses() {
        return new ArrayList<>();
    }
}
