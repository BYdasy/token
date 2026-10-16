package org.token.model.response;

import java.nio.file.Path;

public record MaterialFileLocation(Path path, long size) {
}
