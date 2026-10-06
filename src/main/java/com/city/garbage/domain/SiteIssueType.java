package com.city.garbage.domain;

/** 现场管理问题类型：封桶期间居民继续在满溢点投放等现场管理问题 */
public enum SiteIssueType {
    DISPOSAL_AT_SEALED_BIN("封桶期间继续投放"),
    ILLEGAL_DUMPING("满溢点周边乱倾倒");

    private final String label;
    SiteIssueType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
