package com.ply.exceptions.web;

import com.ply.exceptions.alerts.Alert;
import com.ply.exceptions.alerts.AlertService;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.rules.JobRiskView;
import com.ply.exceptions.rules.JobRiskViewBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
public class JobsApiController {

    private final JobRepository jobs;
    private final JobRiskViewBuilder viewBuilder;
    private final AlertService alerts;
    private final Clock clock;

    public JobsApiController(JobRepository jobs,
                             JobRiskViewBuilder viewBuilder,
                             AlertService alerts,
                             Clock clock) {
        this.jobs = jobs;
        this.viewBuilder = viewBuilder;
        this.alerts = alerts;
        this.clock = clock;
    }

    @GetMapping("/at-risk")
    public List<JobSummary> atRisk(@RequestParam(defaultValue = "2") int days) {
        Instant now = clock.instant();
        Instant end = now.plus(Duration.ofDays(days));
        List<Job> scheduled = jobs.findScheduledBetween(now, end);

        Map<String, List<Alert>> alertsByJob = alerts.findOpen().stream()
                .filter(a -> a.affectedJobId() != null)
                .collect(Collectors.groupingBy(Alert::affectedJobId));

        List<JobSummary> out = new ArrayList<>(scheduled.size());
        for (Job j : scheduled) {
            List<Alert> jobAlerts = alertsByJob.getOrDefault(j.id(), List.of());
            String readiness = computeReadiness(jobAlerts);
            out.add(new JobSummary(
                    j.id(),
                    j.scheduledFor(),
                    j.customer(),
                    j.assignedTech(),
                    j.assignedTruck(),
                    readiness,
                    jobAlerts.size()
            ));
        }
        return out;
    }

    @GetMapping("/{id}/readiness")
    public ResponseEntity<Readiness> readiness(@PathVariable String id) {
        return jobs.findById(id)
                .map(job -> {
                    Instant now = clock.instant();
                    JobRiskView view = viewBuilder.build(job, now);
                    List<Alert> related = alerts.findOpen().stream()
                            .filter(a -> id.equals(a.affectedJobId()))
                            .toList();
                    return ResponseEntity.ok(new Readiness(job, view, related));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private static String computeReadiness(List<Alert> jobAlerts) {
        if (jobAlerts.isEmpty()) return "safe";
        boolean hasHigh = jobAlerts.stream().anyMatch(a -> "high".equals(a.severity()));
        return hasHigh ? "likely_to_break" : "at_risk";
    }

    public record JobSummary(
            String id,
            Instant scheduledFor,
            String customer,
            String tech,
            String truck,
            String readiness,
            int openAlertCount
    ) {}

    public record Readiness(Job job, JobRiskView view, List<Alert> openAlerts) {}
}
