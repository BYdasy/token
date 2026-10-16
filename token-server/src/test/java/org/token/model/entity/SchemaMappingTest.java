package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SchemaMappingTest {

    @Test
    void entitiesUseCurrentTableNames() {
        Map<Class<?>, String> mappings = Map.of(
                Account.class, "ad_master",
                AccountProfile.class, "ad_master",
                Role.class, "user_role",
                Menu.class, "memu",
                OperationLog.class, "ad_record",
                Language.class, "translate_language",
                Translation.class, "translates",
                MaterialAsset.class, "material_asset"
        );

        mappings.forEach((entity, table) -> {
            TableName annotation = entity.getAnnotation(TableName.class);
            assertNotNull(annotation, entity.getSimpleName() + " must declare @TableName");
            assertEquals(table, annotation.value());
        });
    }

    @Test
    void persistedColumnsStayMappedExactly() throws NoSuchFieldException {
        assertColumn(AccountProfile.class, "updateTime", "profile_updated_at");
        assertColumn(Role.class, "actionTime", "actionTime");
        assertColumn(Role.class, "isSuper", "isSelf");
        assertColumn(Menu.class, "isOpen", "isopen");
        assertColumn(OperationLog.class, "actionTime", "actiontime");
        assertColumn(Translation.class, "actionTime", "action_time");
        assertColumn(Translation.class, "isDel", "is_del");
    }

    @Test
    void sharedAndPermissionIdsRemainExplicitInputs() throws NoSuchFieldException {
        assertIdType(AccountProfile.class, "uid", IdType.INPUT);
        assertIdType(Menu.class, "id", IdType.INPUT);
    }

    @Test
    void passwordIsNeverSerialized() throws NoSuchFieldException {
        Field password = Account.class.getDeclaredField("password");
        assertNotNull(password.getAnnotation(JsonIgnore.class));
    }

    private void assertColumn(Class<?> entity, String fieldName, String columnName)
            throws NoSuchFieldException {
        Field field = entity.getDeclaredField(fieldName);
        TableField annotation = field.getAnnotation(TableField.class);
        assertNotNull(annotation, entity.getSimpleName() + "." + fieldName + " must declare @TableField");
        assertEquals(columnName, annotation.value());
    }

    private void assertIdType(Class<?> entity, String fieldName, IdType idType)
            throws NoSuchFieldException {
        Field field = entity.getDeclaredField(fieldName);
        TableId annotation = field.getAnnotation(TableId.class);
        assertNotNull(annotation, entity.getSimpleName() + "." + fieldName + " must declare @TableId");
        assertEquals(idType, annotation.type());
    }
}
