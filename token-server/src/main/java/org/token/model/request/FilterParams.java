package org.token.model.request;

import lombok.Data;

/**
 * 系统管理页面共用的筛选条件。
 */
@Data
public class FilterParams {

    private String userName;
    private String name;
    private String userRemark;
    private String key;
    private String langTitle;
    private String content;
    private String prop;
    private String type;
    private String status;
    private String state;
    private String searchInputValue;
    private String startDate;
    private String endDate;
    private String[] dateRangeVal;
}
