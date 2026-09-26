package com.ofaladag.instantly.agents.application.usecase;

import com.ofaladag.instantly.agents.application.port.in.ProcessReplyUseCase;
import com.ofaladag.instantly.agents.application.port.out.*;
import com.ofaladag.instantly.agents.domain.Chat;

import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.function.Function;

@RequiredArgsConstructor
public final class ReplyProcessor implements ProcessReplyUseCase {
    private final AgentStore store;
    private final CharacterCatalog characters;
    private final LanguageModel model;
    private final ChatTransport transport;
    private final Function<String, UUID> messageId;
    private final int maxAttempts;

    public void process(Chat.Job job) {
        try {
            String reply = job.reply();
            if (reply == null) {
                var context = store.context(job);
                if (context.messages().size() > 40) {
                    long cutoff = context.messages().get(context.messages().size() - 20).position();
                    var older =
                            context.messages().stream().filter(m -> m.position() < cutoff).toList();
                    if (!older.isEmpty()) {
                        String summary = model.summarize(context.summary(), older);
                        long through = older.getLast().position();
                        store.summarize(job, summary, through);
                        context =
                                new Chat.Context(
                                        summary,
                                        through,
                                        context.messages().stream()
                                                .filter(m -> m.position() > through)
                                                .toList());
                    }
                }
                reply = model.reply(characters.get(job.characterId()), context);
                if (reply == null || reply.isBlank() || reply.length() > 6000) {
                    throw new IllegalStateException("invalid_model_output");
                }
                store.saveReply(job, reply);
            }
            String frame = job.outboundFrame();
            if (frame == null) {
                frame = transport.prepare(job.characterId(), job.conversationId(), reply);
                store.saveFrame(job, frame);
            }
            transport.send(job.characterId(), frame);
            store.complete(job, messageId.apply(frame), reply);
        } catch (Chat.LeaseLost ignored) {
            // Another worker owns recovery. Never overwrite its state.
        } catch (Chat.KeyChanged changed) {
            try {
                transport.reconcileKeys(job.characterId());
                store.clearFrame(job);
                fail(job, "key_changed", false, maxAttempts);
            } catch (Chat.LeaseLost ignored) {
            } catch (RuntimeException failure) {
                fail(job, "key_reconciliation_failed", false, maxAttempts);
            }
        } catch (Chat.PermanentFailure failure) {
            fail(job, failure.getMessage(), true, maxAttempts);
        } catch (RuntimeException failure) {
            fail(job, "processing_failed", false, maxAttempts);
        }
    }

    private void fail(Chat.Job job, String code, boolean permanent, int attempts) {
        try {
            store.fail(job, code, permanent, attempts);
        } catch (Chat.LeaseLost ignored) {
        }
    }
}
