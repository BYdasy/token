package org.token.util.identifier;

import java.security.SecureRandom;

/** 独立业务编号，不使用记录主键；数据库唯一索引负责最后一道冲突保护。 */
public final class ComputeBusinessNumbers {
    // 80 位安全随机数；排除容易混淆的 I/L/O/U，两个字母业务前缀下总长 18 位。
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private ComputeBusinessNumbers() {}

    public static String next(String prefix) {
        if (prefix == null || !prefix.matches("[A-Z]{1,6}")) throw new IllegalArgumentException("业务编号前缀必须为一至六位大写字母");
        StringBuilder number = new StringBuilder(prefix);
        for (int i = 0; i < 16; i++) number.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return number.toString();
    }
}
