package com.ofaladag.instantly.agents.adapter.in.internal;

import com.ofaladag.instantly.agents.application.port.in.ProcessReplyUseCase;
import com.ofaladag.instantly.agents.application.port.out.AgentStore;

import lombok.RequiredArgsConstructor;

import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class ReplyWorkers implements AutoCloseable {
    private final AgentStore store;
    private final ProcessReplyUseCase processor;
    private final Supplier<Set<String>> readyCharacters;
    private final int concurrency;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;

    public void start() {
        running = true;
        for (int i = 0; i < concurrency; i++)
            executor.submit(
                    () -> {
                        while (running && !Thread.currentThread().isInterrupted()) {
                            try {
                                var job = store.claim(readyCharacters.get());
                                if (job.isPresent()) processor.process(job.get());
                                else Thread.sleep(500);
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (RuntimeException failure) {
                                LoggerFactory.getLogger(ReplyWorkers.class)
                                        .warn("Reply worker unavailable; durable jobs retained");
                                try {
                                    Thread.sleep(2000);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    });
    }

    public void close() {
        running = false;
        executor.shutdownNow();
    }
}
