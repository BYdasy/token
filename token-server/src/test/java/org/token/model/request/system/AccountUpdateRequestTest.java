package org.token.model.request.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountUpdateRequestTest {

    @Test
    void distinguishesEmptyRequestFromMainAndProfileChanges() {
        AccountUpdateRequest request = new AccountUpdateRequest();
        request.setUid(1);
        assertFalse(request.hasAccountChanges());
        assertFalse(request.hasProfileChanges());

        request.setAvatar("");
        assertTrue(request.hasAccountChanges(), "空字符串也表示明确清空头像");

        request.setAvatar(null);
        request.setRemarks("");
        assertTrue(request.hasProfileChanges(), "空字符串也表示明确清空备注");
    }
}
