package com.ofaladag.instantly.agents.application.port.out;

import com.ofaladag.instantly.agents.domain.CharacterProfile;
import com.ofaladag.instantly.agents.domain.Chat;

import java.util.List;

public interface LanguageModel {
    String reply(CharacterProfile character, Chat.Context context);

    String summarize(String previousSummary, List<Chat.Message> messages);
}
