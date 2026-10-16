package org.token.service;

import org.token.model.entity.Menu;

import java.util.Collection;
import java.util.List;

/**
 * 后台菜单服务。
 */
public interface MenuService {

    /**
     * 查询启用菜单。
     *
     * ids 为空时查询全部启用菜单；不为空时只查询指定权限码。
     */
    List<Menu> getMenu(Collection<Integer> ids);
}
