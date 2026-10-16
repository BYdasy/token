package org.token.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelegramBotClientTest {

    @Test
    void keepsTokenColonUnencodedInTelegramApiUri() {
        assertEquals(
                "https://api.telegram.org/bot123456:ABC_def-ghi/getUpdates?offset=12&timeout=1",
                TelegramBotClient.apiUri(
                        " 123456:ABC_def-ghi ",
                        "getUpdates",
                        "offset=12&timeout=1"
                ).toString()
        );
    }
}
