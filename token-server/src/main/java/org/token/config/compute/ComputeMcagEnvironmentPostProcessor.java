package org.token.config.compute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.Locale;

/** 外置配置加载后拒绝已停用的前缀，避免文件存在却未绑定到当前商户配置。 */
public final class ComputeMcagEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean legacy;
        try {
            legacy = containsLegacyPropertyName(environment)
                    || Binder.get(environment).bind("credits.mcag", Bindable.mapOf(String.class, Object.class)).isBound();
        } catch (RuntimeException invalidLegacyConfiguration) {
            // 不把绑定异常及其中可能包含的配置值写入启动日志。
            throw legacyConfiguration();
        }
        if (legacy) throw legacyConfiguration();
    }

    private static boolean containsLegacyPropertyName(ConfigurableEnvironment environment) {
        // Map 绑定不能枚举所有系统环境变量别名；直接检查名称，绝不读取或输出其配置值。
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                String normalized = name.toLowerCase(Locale.ROOT);
                if (normalized.equals("credits.mcag") || normalized.startsWith("credits.mcag.")
                        || normalized.equals("credits_mcag") || normalized.startsWith("credits_mcag_")) return true;
            }
        }
        return false;
    }

    private static IllegalStateException legacyConfiguration() {
        return new IllegalStateException("检测到已停用的 credits.mcag 配置。请将外置 YAML 的 credits: 改为 token:，"
                + "仅保留 token.mcag，移除旧前缀后重新启动；spring.config.additional-location 只加载文件，不会转换配置前缀。");
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
