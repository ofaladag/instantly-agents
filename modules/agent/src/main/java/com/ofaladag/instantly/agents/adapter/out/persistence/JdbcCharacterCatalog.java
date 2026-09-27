package com.ofaladag.instantly.agents.adapter.out.persistence;

import com.ofaladag.instantly.agents.application.port.out.CharacterCatalog;
import com.ofaladag.instantly.agents.domain.CharacterProfile;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public final class JdbcCharacterCatalog implements CharacterCatalog {
    private final JdbcTemplate jdbc;
    private final String commonInstructions;

    @Override
    public CharacterProfile get(String id) {
        return jdbc
                .query(
                        "SELECT * FROM agent.character_profile WHERE id=?",
                        (rs, _) -> profile(rs),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown character id"));
    }

    @Override
    public Collection<CharacterProfile> all() {
        return jdbc.query(
                "SELECT * FROM agent.character_profile ORDER BY id", (rs, _) -> profile(rs));
    }

    @Override
    public String commonInstructions() {
        return commonInstructions;
    }

    private static CharacterProfile profile(ResultSet rs) throws SQLException {
        var interests = rs.getArray("interests");
        try {
            return new CharacterProfile(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("gender"),
                    rs.getInt("age"),
                    rs.getString("country"),
                    rs.getString("city"),
                    rs.getString("native_language"),
                    rs.getString("timezone"),
                    rs.getString("occupation"),
                    List.of((String[]) interests.getArray()),
                    rs.getString("persona"));
        } finally {
            interests.free();
        }
    }
}
