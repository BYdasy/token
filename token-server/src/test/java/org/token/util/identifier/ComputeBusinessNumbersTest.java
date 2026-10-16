package org.token.util.identifier;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class ComputeBusinessNumbersTest {
    @Test void businessNumbersAreIndependentAndHaveFixedSafeFormat() {
        var numbers=new HashSet<String>();
        for(int i=0;i<10000;i++) {String value=ComputeBusinessNumbers.next("LG");assertTrue(value.matches("LG[0-9A-HJKMNP-TV-Z]{16}"));assertTrue(numbers.add(value));}
        assertThrows(IllegalArgumentException.class,()->ComputeBusinessNumbers.next("bad-prefix"));
    }
}
