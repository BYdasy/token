package org.token.service.compute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeWheelMemberListingTest {
    private ComputeWheelTransactionTest fixture;
    @BeforeEach void setup() throws Exception { fixture = new ComputeWheelTransactionTest(); fixture.setup(); }

    @Test void firstVisitIncludesEveryMemberWithoutCreatingActivityState() {
        long id = fixture.create(false);
        assertMembers(id);
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_wheel_member", Integer.class));
    }

    @Test void mixedExistingAndUninitializedMembersKeepTheirOwnIdsForWheelAndChest() {
        long id = fixture.create(false);
        fixture.tx.executeWithoutResult(s -> fixture.wheel.adjust(id, 1, fixture.adjustment("list-existing", 2), 1));
        assertMembers(id);
        var config = fixture.wheel.config(fixture.data.one("SELECT * FROM compute_activity WHERE id=?", id));
        config.put("tiers", List.of(Map.of("threshold", "20", "selfCount", 1, "inviterCount", 0)));
        config.put("chestRanges", List.of(Map.of("id", "range-a", "minReward", "1", "maxReward", "2", "weight", 1)));
        fixture.jdbc.update("UPDATE compute_activity SET type='CHEST',wheel_config=? WHERE id=?", fixture.data.json(config), id);
        assertMembers(id);
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_wheel_member", Integer.class));
        assertEquals(2, fixture.jdbc.queryForObject("SELECT available_count FROM compute_wheel_member WHERE member_id=1", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private void assertMembers(long activityId) {
        var result = fixture.tx.execute(s -> fixture.wheel.members(activityId, Map.of()));
        var rows = (List<Map<String,Object>>) result.get("records");
        assertEquals(Set.of(1L,2L,3L), rows.stream().map(row -> ((Number)row.get("memberId")).longValue()).collect(java.util.stream.Collectors.toSet()));
        for (var row : rows) {
            assertEquals("wheel" + row.get("memberId"), row.get("username"));
            assertFalse(row.containsKey("listedMemberId"));
        }
    }
}
