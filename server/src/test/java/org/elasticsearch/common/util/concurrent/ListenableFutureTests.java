/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.util.concurrent;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.ReachabilityChecker;
import org.junit.After;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class ListenableFutureTests extends ESTestCase {

    private ExecutorService executorService;
    private final ThreadContext threadContext = new ThreadContext(Settings.EMPTY);

    @After
    public void stopExecutorService() throws InterruptedException {
        if (executorService != null) {
            terminate(executorService);
        }
    }

    public void testListenableFutureNotifiesListeners() {
        ListenableFuture<String> future = new ListenableFuture<>();
        AtomicInteger notifications = new AtomicInteger(0);
        final int numberOfListeners = scaledRandomIntBetween(1, 12);
        for (int i = 0; i < numberOfListeners; i++) {
            future.addListener(ActionListener.running(notifications::incrementAndGet), EsExecutors.DIRECT_EXECUTOR_SERVICE, threadContext);
        }

        future.onResponse("");
        assertEquals(numberOfListeners, notifications.get());
        assertTrue(future.isDone());
    }

    public void testListenableFutureNotifiesListenersOnException() {
        ListenableFuture<String> future = new ListenableFuture<>();
        AtomicInteger notifications = new AtomicInteger(0);
        final int numberOfListeners = scaledRandomIntBetween(1, 12);
        final Exception exception = new RuntimeException();
        for (int i = 0; i < numberOfListeners; i++) {
            future.addListener(ActionListener.wrap(s -> fail("this should never be called"), e -> {
                assertEquals(exception, e);
                notifications.incrementAndGet();
            }), EsExecutors.DIRECT_EXECUTOR_SERVICE, threadContext);
        }

        future.onFailure(exception);
        assertEquals(numberOfListeners, notifications.get());
        assertTrue(future.isDone());
    }

    public void testConcurrentListenerRegistrationAndCompletion() throws BrokenBarrierException, InterruptedException {
        final int numberOfThreads = scaledRandomIntBetween(2, 32);
        final int completingThread = randomIntBetween(0, numberOfThreads - 1);
        final ListenableFuture<String> future = new ListenableFuture<>();
        executorService = EsExecutors.newFixed(
            "testConcurrentListenerRegistrationAndCompletion",
            numberOfThreads,
            1000,
            EsExecutors.daemonThreadFactory("listener"),
            threadContext,
            false
        );
        final CyclicBarrier barrier = new CyclicBarrier(1 + numberOfThreads);
        final CountDownLatch listenersLatch = new CountDownLatch(numberOfThreads - 1);
        final AtomicInteger numResponses = new AtomicInteger(0);
        final AtomicInteger numExceptions = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadNum = i;
            Thread thread = new Thread(() -> {
                threadContext.putTransient("key", threadNum);
                try {
                    barrier.await();
                    if (threadNum == completingThread) {
                        // we need to do more than just call onResponse as this often results in synchronous
                        // execution of the listeners instead of actually going async
                        final int waitTime = randomIntBetween(0, 50);
                        Thread.sleep(waitTime);
                        logger.info("completing the future after sleeping {}ms", waitTime);
                        future.onResponse("");
                        logger.info("future received response");
                    } else {
                        logger.info("adding listener {}", threadNum);
                        future.addListener(ActionListener.wrap(s -> {
                            logger.info("listener {} received value {}", threadNum, s);
                            assertEquals("", s);
                            assertThat(threadContext.getTransient("key"), is(threadNum));
                            numResponses.incrementAndGet();
                            listenersLatch.countDown();
                        }, e -> {
                            logger.error(() -> "listener " + threadNum + " caught unexpected exception", e);
                            numExceptions.incrementAndGet();
                            listenersLatch.countDown();
                        }), executorService, threadContext);
                        logger.info("listener {} added", threadNum);
                    }
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new AssertionError(e);
                }
            });
            thread.start();
        }

        barrier.await();
        barrier.await();
        listenersLatch.await();

        assertEquals(numberOfThreads - 1, numResponses.get());
        assertEquals(0, numExceptions.get());
    }

    public void testAddedListenersReleasedOnCompletion() {
        final ListenableFuture<Void> future = new ListenableFuture<>();
        final ReachabilityChecker reachabilityChecker = new ReachabilityChecker();

        for (int i = between(1, 3); i > 0; i--) {
            future.addListener(reachabilityChecker.register(ActionListener.running(() -> {})));
        }
        reachabilityChecker.checkReachable();
        if (randomBoolean()) {
            future.onResponse(null);
        } else {
            future.onFailure(new ElasticsearchException("simulated"));
        }
        reachabilityChecker.ensureUnreachable();

        future.addListener(reachabilityChecker.register(ActionListener.running(() -> {})));
        reachabilityChecker.ensureUnreachable();
    }

    public void testRejection() throws Exception {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final EsThreadPoolExecutor executorService = EsExecutors.newFixed(
            "testRejection",
            1,
            1,
            EsExecutors.daemonThreadFactory("testRejection"),
            threadContext,
            false
        );

        try {
            executorService.execute(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS); // notify main thread that the executor is blocked
                    barrier.await(10, TimeUnit.SECONDS); // wait for main thread to release us
                } catch (Exception e) {
                    throw new AssertionError("unexpected", e);
                }
            });

            barrier.await(10, TimeUnit.SECONDS); // wait for executor to be blocked

            final var listenableFuture = new ListenableFuture<Void>();
            final var future1 = new PlainActionFuture<Void>();
            final var future2 = new PlainActionFuture<Void>();

            listenableFuture.addListener(future1, executorService, null);
            listenableFuture.addListener(future2, executorService, null);

            final var success = randomBoolean();
            if (success) {
                listenableFuture.onResponse(null);
            } else {
                listenableFuture.onFailure(new ElasticsearchException("simulated"));
            }

            assertFalse(future1.isDone()); // still waiting in the executor queue
            assertTrue(future2.isDone()); // rejected from the executor on this thread

            barrier.await(10, TimeUnit.SECONDS); // release blocked executor

            if (success) {
                expectThrows(EsRejectedExecutionException.class, future2::result);
                assertNull(future1.actionGet(10, TimeUnit.SECONDS));
            } else {
                var exception = expectThrows(EsRejectedExecutionException.class, future2::result);
                assertEquals(1, exception.getSuppressed().length);
                assertThat(exception.getSuppressed()[0], instanceOf(ElasticsearchException.class));
                assertEquals(
                    "simulated",
                    expectThrows(ElasticsearchException.class, () -> future1.actionGet(10, TimeUnit.SECONDS)).getMessage()
                );
            }
        } finally {
            barrier.reset();
            terminate(executorService);
        }
    }
}
