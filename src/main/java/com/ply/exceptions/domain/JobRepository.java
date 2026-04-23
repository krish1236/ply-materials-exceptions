package com.ply.exceptions.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Job> findAll() {
        return withRequirements(
                jdbc.query("""
                        SELECT id, scheduled_for, customer, assigned_tech, assigned_truck, status
                        FROM jobs ORDER BY scheduled_for
                        """, jobRowMapper())
        );
    }

    public List<Job> findScheduledBetween(Instant start, Instant end) {
        return withRequirements(
                jdbc.query("""
                        SELECT id, scheduled_for, customer, assigned_tech, assigned_truck, status
                        FROM jobs
                        WHERE scheduled_for >= ? AND scheduled_for < ?
                        ORDER BY scheduled_for
                        """, jobRowMapper(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(end))
        );
    }

    public Optional<Job> findById(String id) {
        List<Job> jobs = jdbc.query("""
                SELECT id, scheduled_for, customer, assigned_tech, assigned_truck, status
                FROM jobs WHERE id = ?
                """, jobRowMapper(), id);
        return withRequirements(jobs).stream().findFirst();
    }

    public void upsert(Job job) {
        jdbc.update("""
                INSERT INTO jobs (id, scheduled_for, customer, assigned_tech, assigned_truck, status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    scheduled_for = EXCLUDED.scheduled_for,
                    customer = EXCLUDED.customer,
                    assigned_tech = EXCLUDED.assigned_tech,
                    assigned_truck = EXCLUDED.assigned_truck,
                    status = EXCLUDED.status
                """,
                job.id(),
                java.sql.Timestamp.from(job.scheduledFor()),
                job.customer(),
                job.assignedTech(),
                job.assignedTruck(),
                job.status());
        jdbc.update("DELETE FROM job_requirements WHERE job_id = ?", job.id());
        for (Job.JobRequirement req : job.requirements()) {
            jdbc.update(
                    "INSERT INTO job_requirements (job_id, sku, required) VALUES (?, ?, ?)",
                    job.id(), req.sku(), req.required());
        }
    }

    private RowMapper<Job> jobRowMapper() {
        return (rs, i) -> new Job(
                rs.getString("id"),
                rs.getTimestamp("scheduled_for").toInstant(),
                rs.getString("customer"),
                rs.getString("assigned_tech"),
                rs.getString("assigned_truck"),
                rs.getString("status"),
                new ArrayList<>()
        );
    }

    private List<Job> withRequirements(List<Job> jobs) {
        if (jobs.isEmpty()) {
            return jobs;
        }
        List<Job> result = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            List<Job.JobRequirement> reqs = jdbc.query(
                    "SELECT sku, required FROM job_requirements WHERE job_id = ? ORDER BY sku",
                    (rs, i) -> new Job.JobRequirement(rs.getString("sku"), rs.getInt("required")),
                    job.id());
            result.add(new Job(
                    job.id(),
                    job.scheduledFor(),
                    job.customer(),
                    job.assignedTech(),
                    job.assignedTruck(),
                    job.status(),
                    reqs
            ));
        }
        return result;
    }
}
