package com.ofaladag.instantly.agents.adapter.out.characters;

import com.ofaladag.instantly.agents.application.port.out.CharacterCatalog;
import com.ofaladag.instantly.agents.domain.CharacterProfile;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MarkdownCharacterCatalog implements CharacterCatalog {
    private final Map<String, CharacterProfile> profiles;
    private final String commonInstructions;

    public MarkdownCharacterCatalog(String location) {
        var resolver = new PathMatchingResourcePatternResolver();
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        var yaml = new Yaml(new SafeConstructor(options));
        var loaded = new TreeMap<String, CharacterProfile>();
        try {
            for (var resource : resolver.getResources(location)) {
                String markdown =
                        resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (!markdown.startsWith("---\n"))
                    throw new IllegalArgumentException("Missing character frontmatter");
                int end = markdown.indexOf("\n---\n", 4);
                if (end < 0) throw new IllegalArgumentException("Unclosed character frontmatter");
                Map<String, Object> meta = yaml.load(markdown.substring(4, end));
                var interests =
                        ((List<?>) meta.get("interests")).stream().map(String.class::cast).toList();
                var profile =
                        new CharacterProfile(
                                value(meta, "id"),
                                value(meta, "name"),
                                value(meta, "gender"),
                                ((Number) meta.get("age")).intValue(),
                                value(meta, "country"),
                                value(meta, "city"),
                                value(meta, "nativeLanguage"),
                                value(meta, "timezone"),
                                value(meta, "occupation"),
                                interests,
                                markdown.substring(end + 5).strip());
                if (loaded.putIfAbsent(profile.id(), profile) != null)
                    throw new IllegalArgumentException("Duplicate character id");
            }
            if (loaded.isEmpty()) throw new IllegalArgumentException("Character catalog is empty");
            profiles = Collections.unmodifiableMap(loaded);
            commonInstructions =
                    resolver.getResource("classpath:prompts/social-policy.md")
                            .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load character resources", failure);
        }
    }

    private static String value(Map<String, Object> map, String key) {
        return (String) Objects.requireNonNull(map.get(key), key);
    }

    @Override
    public CharacterProfile get(String id) {
        var profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown character id");
        return profile;
    }

    @Override
    public Collection<CharacterProfile> all() {
        return profiles.values();
    }

    @Override
    public String commonInstructions() {
        return commonInstructions;
    }
}
