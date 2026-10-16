package org.token.model.request.system;

import java.util.List;

public record TableColumnPreferenceRequest(String pageKey, List<String> columns) {
}
