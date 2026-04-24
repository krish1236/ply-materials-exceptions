package com.ply.exceptions.web;

import com.ply.exceptions.alerts.Alert;
import com.ply.exceptions.alerts.AlertService;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.view.RedirectView;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class DashboardController {

    private final AlertService alerts;
    private final JobRepository jobs;
    private final Clock clock;

    public DashboardController(AlertService alerts, JobRepository jobs, Clock clock) {
        this.alerts = alerts;
        this.jobs = jobs;
        this.clock = clock;
    }

    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView("/exceptions");
    }

    @GetMapping("/exceptions")
    public String exceptions(Model model) {
        List<Alert> openAlerts = alerts.findByStatus("open");
        List<Alert> acknowledgedAlerts = alerts.findByStatus("acknowledged");
        model.addAttribute("openAlerts", openAlerts);
        model.addAttribute("acknowledgedAlerts", acknowledgedAlerts);
        model.addAttribute("openCount", openAlerts.size());
        model.addAttribute("ackCount", acknowledgedAlerts.size());
        return "exceptions";
    }

    @GetMapping("/exceptions/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        return alerts.findById(id)
                .map(a -> {
                    model.addAttribute("alert", a);
                    model.addAttribute("evidencePretty", prettyPrint(a.evidence()));
                    return "exception_detail";
                })
                .orElse("redirect:/exceptions");
    }

    @PostMapping("/exceptions/{id}/acknowledge")
    public RedirectView acknowledge(@PathVariable UUID id) {
        alerts.acknowledge(id);
        return new RedirectView("/exceptions/" + id);
    }

    @PostMapping("/exceptions/{id}/resolve")
    public RedirectView resolve(@PathVariable UUID id) {
        alerts.resolve(id);
        return new RedirectView("/exceptions");
    }

    @GetMapping("/jobs")
    public String jobs(Model model) {
        Instant now = clock.instant();
        List<Job> scheduled = jobs.findScheduledBetween(now, now.plus(Duration.ofDays(2)));
        Map<String, List<Alert>> alertsByJob = alerts.findOpen().stream()
                .filter(a -> a.affectedJobId() != null)
                .collect(Collectors.groupingBy(Alert::affectedJobId));

        model.addAttribute("jobs", scheduled);
        model.addAttribute("alertsByJob", alertsByJob);
        return "jobs";
    }

    private static String prettyPrint(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
