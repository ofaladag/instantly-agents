package com.ofaladag.instantly.agents.adapter.out.backend;

import com.ofaladag.instantly.agents.application.port.in.ReceiveMessageUseCase;
import com.ofaladag.instantly.agents.application.port.out.ChatTransport;
import com.ofaladag.instantly.agents.domain.Chat;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class InstantlyTransport implements ChatTransport, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(InstantlyTransport.class);
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final JsonMapper json;
    private final ReceiveMessageUseCase receiver;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;

    public InstantlyTransport(
            List<InstantlyAccountClient> clients,
            HttpClient http,
            JsonMapper json,
            ReceiveMessageUseCase receiver) {
        this.http = http;
        this.json = json;
        this.receiver = receiver;
        clients.forEach(client -> nodes.put(client.characterId(), new Node(client)));
    }

    public void start() {
        running = true;
        nodes.values().forEach(node -> executor.submit(() -> supervise(node)));
    }

    public Set<String> readyCharacters() {
        var result = new HashSet<String>();
        nodes.keySet()
                .forEach(
                        id -> {
                            if (ready(id)) result.add(id);
                        });
        return result;
    }

    public int configuredCount() {
        return nodes.size();
    }

    public Map<String, String> states() {
        var states = new TreeMap<String, String>();
        nodes.forEach(
                (id, node) ->
                        states.put(
                                id,
                                node.failure != null
                                        ? node.failure
                                        : ready(id) ? "ready" : "connecting"));
        return states;
    }

    @Override
    public boolean ready(String id) {
        var node = nodes.get(id);
        var connection = node == null ? null : node.connection;
        return running
                && connection != null
                && connection.synced
                && !connection.done.isDone()
                && node.failure == null;
    }

    @Override
    public String prepare(String id, UUID conversation, String text) {
        if (!ready(id)) throw new IllegalStateException("agent_not_connected");
        return nodes.get(id).client.prepare(conversation, text);
    }

    @Override
    public void reconcileKeys(String id) {
        nodes.get(id).client.reconcile();
    }

    @Override
    public void send(String id, String frozenFrame) {
        if (!ready(id)) throw new IllegalStateException("agent_not_connected");
        var connection = nodes.get(id).connection;
        var frame = json.readTree(frozenFrame);
        String requestId = InstantlyAccountClient.required(frame, "requestId");
        var future = new CompletableFuture<JsonNode>();
        if (connection.pending.putIfAbsent(requestId, future) != null)
            throw new IllegalStateException("send_already_pending");
        try {
            connection.write(frozenFrame);
            var response = future.get(25, TimeUnit.SECONDS);
            if (response.path("type").asText().equals("error")) {
                String code = response.path("payload").path("code").asText();
                if (code.equals("key_changed")) throw new Chat.KeyChanged();
                if (Set.of(
                                "idempotency_conflict",
                                "forbidden",
                                "conversation_not_found",
                                "invalid_payload",
                                "validation_error")
                        .contains(code)) throw new Chat.PermanentFailure(code);
                throw new IllegalStateException("send_rejected");
            }
            if (!response.path("type").asText().equals("message.accepted")
                    || !response.path("payload")
                            .path("clientMessageId")
                            .equals(frame.path("payload").path("clientMessageId")))
                throw new IllegalStateException("invalid_send_acceptance");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("send_interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("send_outcome_unknown");
        } finally {
            connection.pending.remove(requestId, future);
        }
    }

    private void supervise(Node node) {
        int failures = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            Connection connection = null;
            try {
                node.client.initialize();
                connection = new Connection(node);
                node.connection = connection;
                String socketToken = node.client.token();
                Instant socketExpiresAt = node.client.expiresAt();
                http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + socketToken)
                        .buildAsync(node.client.socketUri(), connection)
                        .get(25, TimeUnit.SECONDS);
                long nextHeartbeat = 0;
                while (running && !connection.done.isDone()) {
                    // HTTP may rotate tokens while this socket still carries the older JWT.
                    if (socketExpiresAt.isBefore(Instant.now().plusSeconds(30))) break;
                    if (System.nanoTime() >= nextHeartbeat) {
                        connection.write(command("connection.heartbeat", Map.of()));
                        nextHeartbeat =
                                System.nanoTime()
                                        + TimeUnit.SECONDS.toNanos(connection.heartbeatSeconds);
                    }
                    if (connection.synced) failures = 0;
                    Thread.sleep(500);
                }
                if (node.failure != null)
                    break; // Unsupported data/identity needs operator action, never destructive
                // ACK/reset.
            } catch (Chat.PermanentFailure failure) {
                node.failure = failure.getMessage();
                LOG.warn("Agent {} stopped: {}", node.client.characterId(), node.failure);
                break;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                LOG.warn("Agent {} connection unavailable; retrying", node.client.characterId());
            } finally {
                if (connection != null) connection.disconnect();
            }
            try {
                Thread.sleep(Math.min(30000, 1000L << Math.min(failures++, 5)));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private String command(String type, Object payload) {
        return json.writeValueAsString(
                Map.of(
                        "v",
                        1,
                        "type",
                        type,
                        "requestId",
                        UUID.randomUUID().toString(),
                        "payload",
                        payload));
    }

    @Override
    public void close() {
        running = false;
        nodes.values()
                .forEach(
                        node -> {
                            if (node.connection != null) node.connection.disconnect();
                        });
        executor.shutdownNow();
    }

    @RequiredArgsConstructor
    private static final class Node {
        final InstantlyAccountClient client;
        volatile Connection connection;
        volatile String failure;
    }

    @RequiredArgsConstructor
    private final class Connection implements WebSocket.Listener {
        private final Node node;
        private final StringBuilder fragments = new StringBuilder();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending =
                new ConcurrentHashMap<>();
        private volatile WebSocket socket;
        private volatile boolean synced;
        private volatile long heartbeatSeconds = 20;

        @Override
        public void onOpen(WebSocket socket) {
            this.socket = socket;
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            // A recovery batch may contain 100 envelopes, each up to 64 KiB before base64.
            if (fragments.length() + text.length() > 10_000_000) {
                disconnect();
                return CompletableFuture.completedFuture(null);
            }
            fragments.append(text);
            if (!last) {
                socket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            String frame = fragments.toString();
            fragments.setLength(0);
            return CompletableFuture.runAsync(
                    () -> {
                        try {
                            accept(json.readTree(frame));
                        } catch (Chat.PermanentFailure failure) {
                            node.failure = failure.getMessage();
                            LOG.warn(
                                    "Agent {} requires attention: {}",
                                    node.client.characterId(),
                                    node.failure);
                            disconnect();
                        } catch (RuntimeException failure) {
                            disconnect();
                        } finally {
                            socket.request(1);
                        }
                    },
                    executor);
        }

        private void accept(JsonNode frame) {
            if (frame.path("v").asInt() != 1)
                throw new Chat.PermanentFailure("unsupported_socket_version");
            String type = frame.path("type").asText();
            var payload = frame.path("payload");
            var waiter = pending.get(frame.path("requestId").asText(""));
            if (waiter != null && (type.equals("message.accepted") || type.equals("error"))) {
                waiter.complete(frame);
                return;
            }
            switch (type) {
                case "connection.ready" ->
                        heartbeatSeconds =
                                Math.max(
                                        1,
                                        Math.min(
                                                20,
                                                payload.path("heartbeatIntervalSeconds")
                                                        .asLong(20)));
                case "sync.batch" -> {
                    var ids = new ArrayList<UUID>();
                    for (var message : payload.path("messages")) {
                        var id = persist(message);
                        if (id != null) ids.add(id);
                    }
                    acknowledge(ids);
                }
                case "message.created" -> {
                    var id = persist(payload);
                    if (id != null) acknowledge(List.of(id));
                }
                case "sync.complete" -> synced = true;
                case "error" ->
                        disconnect(); // In particular, an unsuccessful delivery ACK needs backend
                // resync.
                default -> {} // Heartbeats, read/delivery receipts and typing do not trigger model
                    // calls.
            }
        }

        private UUID persist(JsonNode message) {
            if (message.path("outgoing").asBoolean()) return null;
            UUID delivery = InstantlyAccountClient.uuid(message, "deliveryId");
            receiver.receive(
                    node.client.decrypt(
                            message)); // Returns only after the PostgreSQL transaction commits.
            return delivery;
        }

        private void acknowledge(List<UUID> ids) {
            if (!ids.isEmpty()) write(command("delivery.ack", Map.of("deliveryIds", ids)));
        }

        synchronized void write(String frame) {
            if (socket == null || done.isDone()) throw new IllegalStateException("socket_closed");
            try {
                socket.sendText(frame, true).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("socket_interrupted");
            } catch (ExecutionException | TimeoutException failure) {
                disconnect();
                throw new IllegalStateException("socket_write_failed");
            }
        }

        void disconnect() {
            synced = false;
            done.complete(null);
            if (socket != null) socket.abort();
            pending.values()
                    .forEach(
                            future ->
                                    future.completeExceptionally(
                                            new IllegalStateException("socket_closed")));
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            disconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            disconnect();
        }
    }
}
