package com.ply.exceptions.rules;

import com.ply.exceptions.confidence.ConfidenceScorer;
import com.ply.exceptions.confidence.ScoreInputs;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.domain.PurchaseOrder;
import com.ply.exceptions.domain.PurchaseOrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class JobRiskViewBuilder {

    private final JobRepository jobs;
    private final PurchaseOrderRepository pos;
    private final ConfidenceScorer scorer;
    private final JdbcTemplate jdbc;

    public JobRiskViewBuilder(JobRepository jobs,
                              PurchaseOrderRepository pos,
                              ConfidenceScorer scorer,
                              JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.pos = pos;
        this.scorer = scorer;
        this.jdbc = jdbc;
    }

    public JobRiskView build(Job job, Instant now) {
        List<JobRiskView.PerItem> perItems = new ArrayList<>();
        List<PurchaseOrder> linkedPos = pos.findByLinkedJobId(job.id());

        for (Job.JobRequirement req : job.requirements()) {
            List<JobRiskView.LocationState> byLocation = jdbc.query("""
                            SELECT location_id, qty, last_scan_at, events_since_scan, last_event_type
                            FROM stock_projection WHERE sku = ?
                            """,
                    (rs, i) -> {
                        Instant lastScan = rs.getTimestamp("last_scan_at") == null ? null
                                : rs.getTimestamp("last_scan_at").toInstant();
                        int eventsSince = rs.getInt("events_since_scan");
                        String eventType = rs.getString("last_event_type");
                        double conf = scorer.score(new ScoreInputs(lastScan, eventsSince, eventType, now, 0.0));
                        return new JobRiskView.LocationState(
                                rs.getString("location_id"),
                                rs.getInt("qty"),
                                conf,
                                lastScan,
                                eventsSince,
                                eventType
                        );
                    }, req.sku());

            List<PurchaseOrder> itemPos = new ArrayList<>();
            for (PurchaseOrder po : linkedPos) {
                for (PurchaseOrder.Line line : po.lines()) {
                    if (line.sku().equals(req.sku())) {
                        itemPos.add(po);
                        break;
                    }
                }
            }

            perItems.add(new JobRiskView.PerItem(req.sku(), req.required(), byLocation, itemPos));
        }

        return new JobRiskView(job, perItems, now);
    }

    public List<JobRiskView> buildForScheduledBetween(Instant start, Instant end, Instant now) {
        List<Job> scheduled = jobs.findScheduledBetween(start, end);
        List<JobRiskView> views = new ArrayList<>(scheduled.size());
        for (Job j : scheduled) {
            views.add(build(j, now));
        }
        return views;
    }
}
