package org.token.model.response;

import lombok.Data;

@Data
public class Location {
    private String country;

    public Location(String country) {
        this.country = country;
    }

    public static Location other() {
        return new Location("Other");
    }
}
