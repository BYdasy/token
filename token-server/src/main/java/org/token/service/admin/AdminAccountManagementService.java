package org.token.service.admin;

import org.springframework.stereotype.Service;
import org.token.custom.LogActionCatalog;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.entity.*;
import org.token.model.request.system.*;
import org.token.model.request.QueryParams;
import org.token.model.response.OperationOutcome;
import org.token.model.response.AccountView;
import org.token.exception.BusinessException;
import org.token.service.*;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.compute.ComputeManagementService;
import org.token.util.PasswordUtils;
import org.token.tools.media.SundryUtils;
import org.token.util.SystemDataProtection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 账号、角色、权限、菜单和操作日志接口。
 */
@Service

@RequiredArgsConstructor
public class AdminAccountManagementService {

    private final AccountService accountService;

    private final AccountProfileService accountProfileService;

    private final RoleService roleService;

    private final OperationLogService operationLogService;

    private final MenuService menuService;

    private final PasswordUtils passwordUtils;

    private final LogActionCatalog logActionCatalog;
    private final org.token.repository.system.RoleRepository roleRepository;

    private final ComputeManagementService computeManagement;

    /**
     * 账号管理列表。
     */

    public OperationOutcome accountList( QueryParams params) {
        IPage<AccountView> page = accountProfileService.listAccounts(params);
        return OperationOutcome.success(page);
    }

    /**
     * 新增后台账号。
     */

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome accountCreate( AccountCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername())) {
            LoggingAspect.setLogDetails("新增账号失败：账号为空", 0);
            return OperationOutcome.failure(-1, "server.account.username.required");
        }
        if (!StringUtils.hasText(request.getPassword())) {
            LoggingAspect.setLogDetails("新增账号失败：密码为空，账号: " + trim(request.getUsername()), 0);
            return OperationOutcome.failure(-1, "server.password.required");
        }

        String username = trim(request.getUsername());
        if (accountService.findByUsername(username) != null) {
            LoggingAspect.setLogDetails("新增账号失败：账号已存在，账号: " + username, 0);
            return OperationOutcome.failure(-1, "server.account.add.failed");
        }

        Integer roleId = normalizeRoleId(request.getRoleId());
        Role role = roleService.getById(roleId);
        if (role == null) {
            LoggingAspect.setLogDetails("新增账号失败：角色不存在，账号: " + username + "，角色ID: " + roleId, 0);
            return OperationOutcome.failure(-1, "server.role.notFound");
        }
        requireSuperForRole(role);

        Account account = new Account();
        account.setUsername(username);
        account.setPassword(passwordUtils.hashPassword(request.getPassword()));
        account.setRole(roleId);
        account.setState(normalizeState(request.getState(), 1));
        account.setAvatar(trimToNull(request.getAvatar()));
        account.setTag(firstText(request.getTag(), "Admin"));
        account.setLocale(trimToNull(request.getLocale()));
        account.setTimeZone(normalizeTimeZone(request.getTimeZone()));
        account.setRemark(trimToNull(request.getRemarks()));
        account.setLoginCount(0);
        account.setProfileUpdatedAt(SundryUtils.getTime());
        accountService.createAccount(account);

        if (request.getAgentMemberIds() != null && !request.getAgentMemberIds().isEmpty()) {
            computeManagement.saveBinding(Map.of("adminUid", account.getUid(), "memberIds", request.getAgentMemberIds()));
        }

        LoggingAspect.setLogDetails("新增账号成功：账号: " + username + "，角色: " + role.getName(), 1);
        return OperationOutcome.success(account);
    }

    /**
     * 修改后台账号基础信息。
     */

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome accountUpdate( AccountUpdateRequest request) {
        Integer uid = request == null ? null : request.resolveUid();
        Account oldUser = requireAccount(uid);
        requireSuperForAccount(oldUser);

        Account account = new Account();
        account.setUid(uid);

        if (StringUtils.hasText(request.getPassword())) {
            account.setPassword(passwordUtils.hashPassword(request.getPassword()));
        }

        if (request.getRoleId() != null) {
            Integer roleId = normalizeRoleId(request.getRoleId());
            Role targetRole = roleService.getById(roleId);
            if (targetRole == null) {
                LoggingAspect.setLogDetails("修改账号失败：角色不存在，UID: " + uid + "，角色ID: " + roleId, 0);
                return OperationOutcome.failure(-1, "server.role.notFound");
            }
            requireSuperForRole(targetRole);
            if (SystemDataProtection.isDefaultAdmin(oldUser.getUid(), oldUser.getUsername())
                    && !Integer.valueOf(1).equals(targetRole.getIsSuper())) {
                LoggingAspect.setLogDetails("修改默认管理员失败：默认管理员必须保留超级角色，UID: " + uid, 0);
                return OperationOutcome.failure(-1, "server.account.defaultProtected");
            }
            account.setRole(roleId);
        }

        if (request.getLocale() != null) {
            account.setLocale(trim(request.getLocale()));
        }
        if (request.getAvatar() != null) {
            account.setAvatar(trim(request.getAvatar()));
        }
        if (request.getTag() != null) {
            account.setTag(trim(request.getTag()));
        }
        if (request.getTimeZone() != null) {
            account.setTimeZone(normalizeTimeZoneForUpdate(request.getTimeZone()));
        }

        if (request.hasAccountChanges()) {
            accountService.updateAccount(account);
        }

        if (request.hasProfileChanges()) {
            AccountProfile updateDetail = new AccountProfile();
            updateDetail.setUid(uid);
            updateDetail.setComment(trim(request.getRemarks()));
            updateDetail.setUpdateTime(SundryUtils.getTime());
            accountProfileService.updateById(updateDetail);
        }

        if (request.getAgentMemberIds() != null) {
            computeManagement.saveBinding(Map.of("adminUid", uid, "memberIds", request.getAgentMemberIds()));
        }
        LoggingAspect.setLogDetails("修改账号成功：UID: " + uid + "，账号: " + oldUser.getUsername(), 1);
        return OperationOutcome.success(accountService.findByUid(uid));
    }

    /**
     * 修改账号启用状态。
     */

    public OperationOutcome accountEnableUpdate( AccountStatusRequest request) {
        Integer uid = request == null ? null : request.resolveUid();
        Integer state = request == null ? null : request.resolveState();
        Account user = requireAccount(uid);

        requireSuperForAccount(user);

        Account update = new Account();
        update.setUid(uid);
        update.setState(normalizeState(state, 1));
        accountService.updateAccount(update);

        String stateText = update.getState() == 0 ? "禁用" : "启用";
        LoggingAspect.setLogDetails("账号状态修改成功：UID: " + uid + "，账号: " + user.getUsername() + "，状态: " + stateText, 1);
        return OperationOutcome.success();
    }

    /**
     * 删除后台账号。
     */

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome accountDelete(
             SystemIdRequest request,
             JwtUserPrincipal principal
    ) {
        Integer uid = request == null ? null : request.resolveId();
        Account user = requireAccount(uid);

        requireSuperForAccount(user);

        if (principal != null && Objects.equals(principal.getUid(), uid)) {
            LoggingAspect.setLogDetails("删除账号失败：不能删除当前登录账号，UID: " + uid, 0);
            return OperationOutcome.failure(-1, "server.account.selfDeleteForbidden");
        }

        accountService.deleteAccount(uid);

        LoggingAspect.setLogDetails("删除账号成功：UID: " + uid + "，账号: " + user.getUsername(), 1);
        return OperationOutcome.success();
    }

    /**
     * 账号编辑时使用的角色下拉列表。
     */

    public OperationOutcome roleListForAccount() {
        return OperationOutcome.success(roleService.getAll());
    }

    /**
     * 角色分页列表。
     */

    public OperationOutcome rolePage( QueryParams params) {
        return OperationOutcome.success(roleService.getList(params));
    }

    /**
     * 读取角色权限树。
     */

    public OperationOutcome rolePermissionRead( SystemIdRequest request) {
        Integer roleId = request == null ? null : request.resolveId();
        Role role = requireRole(roleId);
        Map<String, Object> data = new LinkedHashMap<>();
        List<Menu> menus = menuService.getMenu(null);
        Set<String> available = menus.stream().filter(menu -> org.token.custom.security.PermissionCodes.GRANTABLE_IDS.contains(menu.getId()))
                .map(menu -> String.valueOf(menu.getId())).collect(Collectors.toSet());
        List<String> configured = splitPermissions(role.getRole());
        Set<String> effective = org.token.custom.security.PermissionCodes.fromRole(role.getRole());
        data.put("selected", effective.stream().filter(available::contains).distinct().toList());
        data.put("ignoredCount", configured.stream().filter(code -> !effective.contains(code)).distinct().count());
        data.put("treeData", buildMenuTree(menus, Set.of(0, -1)));
        return OperationOutcome.success(data);
    }

    /**
     * 修改角色权限。
     */

    public OperationOutcome rolePermissionUpdate( RolePermissionUpdateRequest request) {
        Integer roleId = request == null ? null : request.getRoleId();
        Role role = requireRole(roleId);
        String permissionText = joinPermissions(request.getNewPlatformMenuIds());
        requireSuperForRole(role);

        Role update = new Role();
        update.setId(roleId);
        update.setRole(permissionText);
        roleService.updateRole(update);

        LoggingAspect.setLogDetails("角色权限修改成功：角色: " + role.getName() + "，权限列表: " + permissionText, 1);
        return OperationOutcome.success();
    }

    /**
     * 新增角色。
     */

    public OperationOutcome roleCreate( RoleSaveRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            LoggingAspect.setLogDetails("创建角色失败：角色名称为空", 0);
            return OperationOutcome.failure(-1, "server.role.nameRequired");
        }

        String name = trim(request.getName());
        if (normalizeIsSuper(request.getIsSuper()) == 1) requireSuper();
        Role exists = roleRepository.findByName(name);
        if (exists != null) {
            LoggingAspect.setLogDetails("创建角色失败：角色已存在，角色: " + name, 0);
            return OperationOutcome.failure(-1, "server.role.exists");
        }

        roleService.createRole(name, trimToNull(request.getRemarks()), normalizeIsSuper(request.getIsSuper()));
        LoggingAspect.setLogDetails("创建角色成功：角色: " + name, 1);
        return OperationOutcome.success();
    }

    /**
     * 修改角色。
     */

    public OperationOutcome roleUpdate( RoleSaveRequest request) {
        Integer roleId = request == null ? null : request.getId();
        Role oldRole = requireRole(roleId);
        requireSuperForRole(oldRole);
        if (normalizeIsSuper(request.getIsSuper()) == 1) requireSuper();

        Role update = new Role();
        update.setId(roleId);
        if (StringUtils.hasText(request.getName())) {
            update.setName(trim(request.getName()));
        }
        if (request.getRemarks() != null) {
            update.setRemarks(trimToNull(request.getRemarks()));
        }
        if (request.getIsSuper() != null) {
            update.setIsSuper(normalizeIsSuper(request.getIsSuper()));
        }
        roleService.updateRole(update);

        LoggingAspect.setLogDetails("修改角色成功：角色ID: " + roleId + "，原角色: " + oldRole.getName(), 1);
        return OperationOutcome.success(roleService.getRole(roleId));
    }

    /**
     * 删除角色。
     */

    public OperationOutcome roleDelete( SystemIdRequest request) {
        Integer roleId = request == null ? null : request.resolveId();
        Role role = requireRole(roleId);

        if (Integer.valueOf(1).equals(role.getIsSuper())) {
            LoggingAspect.setLogDetails("删除角色失败：系统默认角色不能删除，角色ID: " + roleId, 0);
            return OperationOutcome.failure(-1, "server.role.cannotDeleteDefault");
        }

        Integer usingCount = accountService.countByRoleId(roleId);
        if (usingCount != null && usingCount > 0) {
            LoggingAspect.setLogDetails("删除角色失败：角色已被账号使用，角色: " + role.getName(), 0);
            return OperationOutcome.failure(-1, "server.role.inUse");
        }

        roleService.deleteRole(roleId);
        LoggingAspect.setLogDetails("删除角色成功：角色: " + role.getName() + "，角色ID: " + roleId, 1);
        return OperationOutcome.success();
    }

    /**
     * 操作日志分页列表。
     */

    public OperationOutcome systemLogRead( QueryParams params) {
        IPage<OperationLog> page = operationLogService.getAdmin(params);
        return OperationOutcome.success(page);
    }

    /**
     * 操作类型列表。
     */

    public OperationOutcome systemLogActions( ActionQueryRequest request) {
        String query = request == null ? "" : trimToEmpty(request.getQ());
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<String> actions = java.util.stream.Stream.concat(logActionCatalog.getSupportedActions().stream(),
                        operationLogService.actionTypes().stream()).distinct()
                .filter(action -> !StringUtils.hasText(query)
                        || action.toLowerCase(Locale.ROOT).contains(lowerQuery))
                .sorted()
                .toList();
        return OperationOutcome.success(actions);
    }

    /**
     * 当前启用菜单树。
     */

    public OperationOutcome menuTree() {
        return OperationOutcome.success(buildMenuTree(menuService.getMenu(null), Set.of(0, -1)));
    }

    private Account requireAccount(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }

        Account user = accountService.findByUid(uid);
        if (user == null) {
            throw new BusinessException("server.user.notFound");
        }
        return user;
    }

    private Role requireRole(Integer roleId) {
        if (roleId == null || roleId <= 0) {
            throw new BusinessException("server.role.idRequired");
        }

        Role role = roleService.getById(roleId);
        if (role == null) {
            throw new BusinessException("server.role.notFound");
        }
        return role;
    }

    private List<Menu> buildMenuTree(List<Menu> menus, Set<Integer> pids) {
        if (menus == null || menus.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<Menu>> menusByParent = menus.stream()
                .filter(Objects::nonNull)
                .filter(menu -> menu.getPid() != null)
                .collect(Collectors.groupingBy(
                        Menu::getPid,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return menus.stream()
                .filter(menu -> menu != null && pids.contains(menu.getPid()))
                .peek(menu -> attachMenuChildren(menu, menusByParent, new HashSet<>()))
                .collect(Collectors.toList());
    }

    private void attachMenuChildren(
            Menu menu,
            Map<Integer, List<Menu>> menusByParent,
            Set<Integer> ancestors
    ) {
        if (menu.getId() == null || !ancestors.add(menu.getId())) {
            menu.setChildren(null);
            return;
        }

        List<Menu> children = menusByParent.getOrDefault(menu.getId(), List.of())
                .stream()
                .filter(child -> child.getId() != null && !ancestors.contains(child.getId()))
                .peek(child -> attachMenuChildren(child, menusByParent, new HashSet<>(ancestors)))
                .toList();
        menu.setChildren(children.isEmpty() ? null : children);
    }

    private List<String> splitPermissions(String role) {
        if (!StringUtils.hasText(role)) {
            return new ArrayList<>();
        }

        return Arrays.stream(role.split(","))
                .map(this::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String joinPermissions(List<Integer> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return "";
        }

        LinkedHashSet<Integer> ids = permissionIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Menu> menus = menuService.getMenu(null);
        Set<Integer> available = menus.stream().map(Menu::getId).collect(Collectors.toSet());
        if (!available.containsAll(ids) || !org.token.custom.security.PermissionCodes.GRANTABLE_IDS.containsAll(ids)) {
            throw new BusinessException("server.permission.invalidSelection");
        }

        return ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /** 角色和账号编辑叶子不能隐含授予超级身份；安全判断直接读取当前角色，不使用列表缓存。 */
    private void requireSuperForRole(Role role) {
        if (role != null && Integer.valueOf(1).equals(role.getIsSuper())) requireSuper();
    }

    private void requireSuperForAccount(Account account) {
        if (SystemDataProtection.isDefaultAdmin(account.getUid(),account.getUsername())) requireSuper();
        if (account.getRole() != null) requireSuperForRole(roleService.getById(account.getRole()));
    }

    private void requireSuper() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(authority->"super".equals(authority.getAuthority())))
            throw new AccessDeniedException("只有超级管理员可以授予或修改超级角色及账号");
    }

    private Integer normalizeRoleId(Integer roleId) {
        if (roleId == null || roleId <= 0) {
            throw new BusinessException("server.role.idRequired");
        }
        return roleId;
    }

    private Integer normalizeState(Integer state, Integer defaultState) {
        Integer finalState = state == null ? defaultState : state;
        return finalState != null && finalState == 0 ? 0 : 1;
    }

    private Integer normalizeIsSuper(Integer isSuper) {
        return isSuper != null && isSuper == 1 ? 1 : 0;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : second;
    }

    private String trimToNull(Object value) {
        String text = trimToEmpty(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeTimeZone(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return java.time.ZoneId.of(value.trim()).getId();
        } catch (Exception exception) {
            throw new BusinessException("server.settings.timeZoneInvalid");
        }
    }

    private String normalizeTimeZoneForUpdate(String value) {
        return StringUtils.hasText(value) ? normalizeTimeZone(value) : "";
    }
}
