package com.ofaladag.instantly.agents;

import static org.mockito.Mockito.*;

import com.ofaladag.instantly.agents.application.port.out.*;
import com.ofaladag.instantly.agents.application.usecase.ReplyProcessor;
import com.ofaladag.instantly.agents.domain.Chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ReplyProcessorTest {
    private final AgentStore store = mock(AgentStore.class);
    private final CharacterCatalog catalog = mock(CharacterCatalog.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private final ChatTransport transport = mock(ChatTransport.class);
    private final UUID messageId = UUID.randomUUID();
    private final ReplyProcessor processor =
            new ReplyProcessor(store, catalog, model, transport, _ -> messageId, 8);

    private Chat.Job job(String reply, String frame) {
        return new Chat.Job(
                UUID.randomUUID(),
                "aylin-izmir",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                2,
                UUID.randomUUID(),
                reply,
                frame);
    }

    @Test
    void uncertainSendRetriesExactPersistedFrameWithoutAnotherModelCall() {
        var job = job("same reply", "frozen ciphertext");
        doThrow(new IllegalStateException("timeout"))
                .when(transport)
                .send(job.characterId(), job.outboundFrame());
        processor.process(job);
        verify(transport).send(job.characterId(), "frozen ciphertext");
        verify(store).fail(job, "processing_failed", false, 8);
        verify(store, never()).clearFrame(any());
        verifyNoInteractions(model, catalog);
    }

    @Test
    void persistedFrameIsCompletedOnlyAfterAcceptance() {
        var job = job("same reply", "frozen ciphertext");
        processor.process(job);
        var order = inOrder(transport, store);
        order.verify(transport).send(job.characterId(), job.outboundFrame());
        order.verify(store).complete(job, messageId, job.reply());
        verifyNoInteractions(model, catalog);
    }

    @Test
    void definiteKeyRejectionAllowsResealButKeepsGeneratedReply() {
        var job = job("same reply", "old keys");
        doThrow(new Chat.KeyChanged()).when(transport).send(anyString(), anyString());
        processor.process(job);
        var order = inOrder(transport, store);
        order.verify(transport).send(anyString(), anyString());
        order.verify(transport).reconcileKeys(job.characterId());
        order.verify(store).clearFrame(job);
        order.verify(store).fail(job, "key_changed", false, 8);
        verifyNoInteractions(model, catalog);
    }

    @Test
    void lostLeasePreventsTransmission() {
        var job = job("saved reply", null);
        when(transport.prepare(anyString(), any(), anyString())).thenReturn("new frame");
        doThrow(new Chat.LeaseLost()).when(store).saveFrame(job, "new frame");
        processor.process(job);
        verify(transport, never()).send(anyString(), anyString());
        verify(store, never()).complete(any(), any(), any());
    }
}
