package com.ply.exceptions.web;

import com.ply.exceptions.domain.Item;
import com.ply.exceptions.domain.ItemRepository;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.domain.Location;
import com.ply.exceptions.domain.LocationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final ItemRepository items;
    private final LocationRepository locations;
    private final JobRepository jobs;

    public DebugController(ItemRepository items, LocationRepository locations, JobRepository jobs) {
        this.items = items;
        this.locations = locations;
        this.jobs = jobs;
    }

    @GetMapping("/items")
    public List<Item> items() {
        return items.findAll();
    }

    @GetMapping("/locations")
    public List<Location> locations() {
        return locations.findAll();
    }

    @GetMapping("/jobs")
    public List<Job> jobs() {
        return jobs.findAll();
    }
}
