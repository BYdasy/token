package org.token.repository.support;

import java.util.List;
import java.util.Map;

/** 注解 SQL Provider 将仓储内部的位置参数转换为 MyBatis 绑定参数，不执行 SQL。 */
public final class SqlParameters {
    private SqlParameters() { }

    public static String bind(String statement, List<?> values, Map<String, Object> parameters) {
        parameters.put("boundValues", values);
        StringBuilder sql = new StringBuilder(statement.length() + values.size() * 16);
        char quote = 0;
        int parameter = 0;
        for (int index = 0; index < statement.length(); index++) {
            char character = statement.charAt(index);
            if (quote != 0) {
                sql.append(character);
                if (character == '\\' && index + 1 < statement.length()) sql.append(statement.charAt(++index));
                else if (character == quote) {
                    if (index + 1 < statement.length() && statement.charAt(index + 1) == quote) sql.append(statement.charAt(++index));
                    else quote = 0;
                }
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
                sql.append(character);
            } else if (character == '?') {
                sql.append("#{boundValues[").append(parameter++).append("]}");
            } else sql.append(character);
        }
        if (parameter != values.size()) throw new IllegalArgumentException("查询参数数量与占位符不一致");
        return sql.toString();
    }
}
