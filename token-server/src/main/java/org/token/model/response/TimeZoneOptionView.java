package org.token.model.response;

/** 时区远程搜索选项，ID 用于保存，标签与偏移用于中文展示。 */
public record TimeZoneOptionView(String id, String label, String offset) {
}
