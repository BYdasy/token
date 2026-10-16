package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 翻译表实体
 *
 * 对应数据库表：translates
 */
@Data
@TableName("translates")
public class Translation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 翻译键（key）
     *
     * 例如：
     * server.success（操作成功）
     * server.session.expired（会话已过期）
     * server.user.notFound（用户不存在）
     */
    @TableField("tkey")
    private String tkey;

    /**
     * 语言
     *
     * 例如：
     * zh
     * en
     * es
     */
    @TableField("lang")
    private String lang;

    /**
     * 翻译内容
     */
    @TableField("tvalue")
    private String tvalue;

    /**
     * 类型
     *
     * 翻译记录类型。
     */
    @TableField("type")
    private Integer type;

    /**
     * 翻译属性
     *
     * 内容格式：1 为 HTML，其他值为纯文本。
     */
    @TableField("prop")
    private Integer prop;

    /**
     * 操作时间
     */
    @TableField("action_time")
    private LocalDateTime actionTime;

    /**
     * 标题
     */
    @TableField("title")
    private String title;

    /**
     * 是否可删除
     *
     * 删除标记。
     */
    @TableField("is_del")
    private Integer isDel;

    /**
     * 非数据库字段：英文翻译内容
     */
    @TableField(exist = false)
    private String englishTvalue;

    /**
     * 非数据库字段：语言列表
     */
    @TableField(exist = false)
    private List<String> languages;

    /**
     * 非数据库字段：多语言翻译 Map
     *
     * 例如：
     * {
     *   "zh": "成功",
     *   "en": "success",
     *   "es": "éxito"
     * }
     */
    @TableField(exist = false)
    private Map<String, String> translations;

    /**
     * 非数据库字段：模板自身是否允许编辑（系统内置模板不可编辑）。
     */
    @TableField(exist = false)
    private Boolean editable;

    /**
     * 非数据库字段：该模板是否允许出现在通用键值维护页。
     * 后台、前台语言包（模板 5/7）应在各自独立页面维护。
     */
    @TableField(exist = false)
    private Boolean translationEditable;

    /**
     * 非数据库字段：是否是受保护的前后端语言包模板。
     */
    @TableField(exist = false)
    private Boolean protectedPackage;

    /**
     * 非数据库字段：模板种类，字段值为 SYSTEM（系统内置）或 CUSTOM（自定义）。
     */
    @TableField(exist = false)
    private String kind;

    /**
     * 非数据库字段：是否允许执行只补缺失项的一键补齐。
     */
    @TableField(exist = false)
    private Boolean completionAllowed;

    /** 固定系统用户消息键的事件与启停状态，复用通知消息模板6编辑。 */
    @TableField(exist = false)
    private String systemMessageEvent;
    @TableField(exist = false)
    private Boolean systemMessageEnabled;
}
