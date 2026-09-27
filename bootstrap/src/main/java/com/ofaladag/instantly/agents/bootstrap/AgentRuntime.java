package com.ofaladag.instantly.agents.bootstrap;

import com.ofaladag.instantly.agents.adapter.in.internal.ReplyWorkers;
import com.ofaladag.instantly.agents.adapter.out.backend.InstantlyTransport;

import lombok.RequiredArgsConstructor;

import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.SmartLifecycle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;

import javax.sql.DataSource;

/** One active runtime per agents database: refresh tokens and identity files are account-scoped. */
@RequiredArgsConstructor
@DependsOnDatabaseInitialization
public final class AgentRuntime implements SmartLifecycle {
    public static final long LEADERSHIP_LOCK = 84482923044781L;
    private final DataSource dataSource;
    private final InstantlyTransport transport;
    private final ReplyWorkers workers;
    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private volatile boolean running;
    private Connection leadership;

    @Override
    public synchronized void start() {
        try {
            leadership = dataSource.getConnection();
            try (var statement = leadership.createStatement();
                    var result =
                            statement.executeQuery(
                                    "SELECT pg_try_advisory_lock(" + LEADERSHIP_LOCK + ")")) {
                result.next();
                if (!result.getBoolean(1))
                    throw new IllegalStateException("Another agent runtime owns this database");
            }
            running = true;
            transport.start();
            workers.start();
            monitor.scheduleWithFixedDelay(
                    () -> {
                        try {
                            if (!leadership.isValid(2)) stop();
                        } catch (SQLException failure) {
                            stop();
                        }
                    },
                    2,
                    2,
                    TimeUnit.SECONDS);
        } catch (SQLException | RuntimeException failure) {
            stop();
            throw new IllegalStateException("Unable to acquire agent runtime leadership", failure);
        }
    }

    @Override
    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;
        workers.close();
        transport.close();
        monitor.shutdownNow();
        if (leadership != null) {
            try {
                try (var statement = leadership.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(" + LEADERSHIP_LOCK + ")");
                }
            } catch (SQLException ignored) {
            } finally {
                try {
                    leadership.close();
                } catch (SQLException ignored) {
                }
                leadership = null;
            }
        }
        if (wasRunning)
            LoggerFactory.getLogger(AgentRuntime.class)
                    .info("Agent runtime stopped; unfinished work remains in PostgreSQL");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
