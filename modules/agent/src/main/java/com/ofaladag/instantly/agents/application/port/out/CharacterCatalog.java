package com.ofaladag.instantly.agents.application.port.out;

import com.ofaladag.instantly.agents.domain.CharacterProfile;

import java.util.Collection;

public interface CharacterCatalog {
    CharacterProfile get(String id);

    Collection<CharacterProfile> all();

    String commonInstructions();
}
