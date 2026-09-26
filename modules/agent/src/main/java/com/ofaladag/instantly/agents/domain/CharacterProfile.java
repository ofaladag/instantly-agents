package com.ofaladag.instantly.agents.domain;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

public record CharacterProfile(
        String id,
        String name,
        String gender,
        int age,
        String country,
        String city,
        String nativeLanguage,
        String timezone,
        String occupation,
        List<String> interests,
        String persona) {
    public CharacterProfile {
        for (String field :
                List.of(
                        id,
                        name,
                        gender,
                        country,
                        city,
                        nativeLanguage,
                        timezone,
                        occupation,
                        persona)) {
            if (field.isBlank())
                throw new IllegalArgumentException("Character fields must not be blank");
        }
        if (!id.matches("[a-z][a-z0-9-]{2,63}"))
            throw new IllegalArgumentException("Invalid character id");
        if (!(gender.equals("female") || gender.equals("male")))
            throw new IllegalArgumentException("Invalid gender");
        if (age < 20 || age > (gender.equals("female") ? 35 : 30))
            throw new IllegalArgumentException("Invalid age");
        ZoneId.of(timezone);
        interests = List.copyOf(Objects.requireNonNull(interests));
        if (interests.size() < 3)
            throw new IllegalArgumentException("At least three interests required");
    }

    public String instructions() {
        return "Character: "
                + name
                + "\nAge: "
                + age
                + "\nHome: "
                + city
                + ", "
                + country
                + "\nNative language: "
                + nativeLanguage
                + "\nOccupation: "
                + occupation
                + "\nInterests: "
                + String.join(", ", interests)
                + "\n\n"
                + persona;
    }
}
