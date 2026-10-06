package com.city.garbage.service;

import com.city.garbage.domain.SiteIssue;
import com.city.garbage.domain.SiteIssueStatus;
import com.city.garbage.repo.SiteIssueRepository;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 物业端现场管理问题处置：封桶期间居民滞留投放的摄像头记录，现场督导清理后回填 */
@Service
@RequiredArgsConstructor
public class SiteIssueService {

    private final SiteIssueRepository siteIssueRepository;
    private final LinkageEngine engine;
    private final TimeService timeService;

    public List<SiteIssue> list(boolean openOnly) {
        return openOnly ? siteIssueRepository.findByStatusOrderByCreatedAtDesc(SiteIssueStatus.OPEN)
                : siteIssueRepository.findAll();
    }

    /** 物业现场处置完成：回填处置说明并完成关联现场督导任务 */
    @Transactional
    public SiteIssue handle(Long issueId, String note) {
        SiteIssue issue = siteIssueRepository.findById(issueId)
                .orElseThrow(() -> new BusinessException("现场管理问题不存在: " + issueId));
        if (issue.getStatus() == SiteIssueStatus.HANDLED) {
            throw new BusinessException("该现场管理问题已处置");
        }
        issue.setStatus(SiteIssueStatus.HANDLED);
        issue.setHandledAt(timeService.now());
        issue.setHandleNote(note);
        siteIssueRepository.save(issue);
        // 同单仍有其他未处置现场问题时，督导任务保留；全部处置完才完成任务
        if (issue.getTaskId() != null
                && siteIssueRepository.findByCaseNoAndStatus(issue.getCaseNo(), SiteIssueStatus.OPEN).isEmpty()) {
            engine.markDoneById(issue.getTaskId(), "现场管理问题已全部处置：" + note);
        }
        return issue;
    }
}
