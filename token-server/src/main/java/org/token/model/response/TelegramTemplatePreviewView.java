package org.token.model.response;

import java.util.List;

/** 仅包含选定模板的渲染结果，不包含渠道凭据或业务数据。 */
public record TelegramTemplatePreviewView(String title, String content, List<String> variables) { }
