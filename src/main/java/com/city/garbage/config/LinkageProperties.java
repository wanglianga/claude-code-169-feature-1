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
    /** 厨余桶满溢持续多少分钟清运车仍未到 -> 物业临时封桶并引导居民到备用点，同时通知环卫调整路线 */
    private int binSealMinutes = 10;
    /** 封桶后再持续多少分钟清运车仍未到 -> 升级街道端超时督办 */
    private int streetOverdueMinutes = 20;
    /** 封桶时向居民公示的预计恢复时长（分钟） */
    private int estimatedResumeMinutes = 40;
}
