package io.activeledger.sdk.java;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deprecated event subscription, driven from Java against a raw socket
 * node whose event stream never ends, so the listener is fed while the
 * connection is genuinely open.
 */
@SuppressWarnings("deprecation")
class SubscriptionTest {

    private ServerSocket node;
    private Thread acceptor;
    private final BlockingQueue<String> requestLines = new LinkedBlockingQueue<>();
    private volatile String eventsToSend = "";

    @BeforeEach
    void start() throws IOException {
        node = new ServerSocket(0);
        acceptor = new Thread(this::serve);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws IOException {
        node.close();
    }

    /** Reads the request, answers with an open-ended event stream, then waits for the client to hang up. */
    private void serve() {
        try (Socket socket = node.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                requestLines.add(line);
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + eventsToSend)
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Never finish the body; the client decides when to leave.
            while (socket.getInputStream().read() != -1) {
                // discard
            }
        } catch (IOException ignored) {
            // The client went away.
        }
    }

    private ActiveledgerClient client() {
        return new ActiveledgerClient("http://127.0.0.1:" + node.getLocalPort());
    }

    @Test
    void listenerReceivesEachEventInOrder() throws Exception {
        eventsToSend = "event: commit\nid: 1\ndata: first\n\ndata: second\n\n";
        BlockingQueue<io.activeledger.sdk.events.LedgerEvent> got = new LinkedBlockingQueue<>();
        try (ActiveledgerClient client = client();
             Subscription subscription = client.subscribe(got::add)) {
            io.activeledger.sdk.events.LedgerEvent first = got.poll(5, TimeUnit.SECONDS);
            io.activeledger.sdk.events.LedgerEvent second = got.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals("first", first.getData());
            assertEquals("commit", first.getName());
            assertEquals("1", first.getId());
            assertEquals("second", second.getData());
        }
    }

    @Test
    void defaultsToTheEventsPathAndAsksForAnEventStream() throws Exception {
        eventsToSend = "data: x\n\n";
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActiveledgerClient client = client();
             Subscription subscription = client.subscribe(e -> delivered.countDown())) {
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        assertEquals("GET /events HTTP/1.1", requestLines.poll(5, TimeUnit.SECONDS));
        assertTrue(requestLines.stream().anyMatch(l -> l.equalsIgnoreCase("Accept: text/event-stream")), requestLines.toString());
    }

    @Test
    void customPathIsRequested() throws Exception {
        eventsToSend = "data: x\n\n";
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActiveledgerClient client = client();
             Subscription subscription = client.subscribe(e -> delivered.countDown(), "/feed/mine")) {
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        assertEquals("GET /feed/mine HTTP/1.1", requestLines.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        eventsToSend = "data: x\n\n";
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActiveledgerClient client = client()) {
            Subscription subscription = client.subscribe(e -> delivered.countDown());
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            subscription.close();
            subscription.close();
        }
    }
}
