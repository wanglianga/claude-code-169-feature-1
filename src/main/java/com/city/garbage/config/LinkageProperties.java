package com.city.garbage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.linkage")
public class LinkageProperties {
    private int nightStart = 20;
    private int nightEnd = 6;
    private int nightDisposalThreshold = 3;
    private int offlineMinutes = 10;
    private int binFullThreshold = 90;
    private int odorThreshold = 70;
    private int pointsPerKg = 10;
    private int wrongSortDeduction = 5;
    /** 清运离场后多少分钟未做清洁确认视为“清运后未清洁” */
    private int notCleanedMinutes = 15;
    /** 积分待结算超过多少秒视为“积分未到账” */
    private int unsettledSeconds = 60;
    /** 物业临时封桶后，清运车超过该分钟数仍未到场则升级提醒街道 */
    private int sealOverdueMinutes = 30;
    /** 封桶时居民端默认展示的预计恢复时间（分钟） */
    private int defaultRestoreMinutes = 60;
}
