package org.token.service.compute;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputeTransactionExplorerTest {
    private final String hash="a".repeat(64);
    @Test void linksUseTheNetworkAndSupportTronFragmentsAndConfiguredTemplates() {
        assertEquals("https://tronscan.org/#/transaction/"+hash,ComputeTransactionExplorer.url("","TRC20",hash));
        assertEquals("https://bscscan.com/tx/0x"+hash,ComputeTransactionExplorer.url("","BSC","0x"+hash));
        assertEquals("https://polygonscan.com/tx/0x"+hash,ComputeTransactionExplorer.url(null,"POL","0x"+hash));
        assertEquals("https://mempool.space/tx/"+hash,ComputeTransactionExplorer.url(null,"BTC",hash));
        assertEquals("https://example.org/transaction/"+hash,ComputeTransactionExplorer.url("https://example.org/transaction/{txHash}","OTHER",hash));
    }
    @Test void rejectsMalformedHashesCredentialsAndExecutableUrls() {
        assertEquals("",ComputeTransactionExplorer.url("javascript:alert(1)","BSC",hash));
        assertEquals("",ComputeTransactionExplorer.url("https://user:password@example.org","BSC",hash));
        assertEquals("",ComputeTransactionExplorer.url("","UNKNOWN",hash));
        assertEquals("",ComputeTransactionExplorer.url("","BSC","<script>"));
    }
}
