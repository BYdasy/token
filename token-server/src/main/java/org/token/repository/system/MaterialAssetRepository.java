package org.token.repository.system;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.model.entity.MaterialAsset;
import org.token.repository.dao.MaterialAssetMapper;
import org.token.util.PageSupport;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MaterialAssetRepository {
    private final MaterialAssetMapper mapper;

    public IPage<MaterialAsset> page(Map<String, Object> body, boolean canManage) {
        Map<String, Object> supplied = PageSupport.filters(body);
        Map<String, Object> filters = new LinkedHashMap<>();
        for (String key : List.of("name", "tags", "fileName", "mediaType", "category")) {
            String value = PageSupport.text(supplied.get(key));
            filters.put(key, StringUtils.hasText(value) ? value : null);
        }
        if (!canManage) filters.put("mediaType", "IMAGE");
        for (String key : List.of("createdFrom", "createdTo")) {
            String value = PageSupport.text(supplied.get(key));
            filters.put(key, value == null ? null : LocalDateTime.parse(value));
        }
        long current = PageSupport.current(body), size = PageSupport.size(body);
        Page<MaterialAsset> page = new Page<>(current, size, mapper.count(filters));
        page.setRecords(mapper.findPage(filters, size, (current - 1) * size));
        return page;
    }

    public List<String> categories(boolean canManage) {
        return mapper.categories(!canManage).stream().filter(StringUtils::hasText).toList();
    }

    public MaterialAsset find(Long id) { return mapper.selectById(id); }
    public int insert(MaterialAsset asset) { return mapper.insert(asset); }
    public int update(MaterialAsset asset) { return mapper.updateById(asset); }
    public int softDelete(Long id) { return mapper.deleteById(id); }
}
