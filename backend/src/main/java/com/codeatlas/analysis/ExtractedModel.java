package com.codeatlas.analysis;

import java.util.ArrayList;
import java.util.List;

/** Deterministic extraction output for one asset revision. */
public class ExtractedModel {

    private final List<ExtractedNode> nodes = new ArrayList<>();
    private final List<ExtractedEdge> edges = new ArrayList<>();
    private final List<CoverageFinding> findings = new ArrayList<>();
    private final List<ExtractedLocation> locations = new ArrayList<>();
    private final List<ConfigValue> configValues = new ArrayList<>();

    private int discoveredFiles;
    private int eligibleFiles;
    private int parsedFiles;
    private int excludedFiles;
    private int failedFiles;

    public List<ExtractedNode> nodes() { return nodes; }
    public List<ExtractedEdge> edges() { return edges; }
    public List<CoverageFinding> findings() { return findings; }
    public List<ExtractedLocation> locations() { return locations; }
    public List<ConfigValue> configValues() { return configValues; }

    public int discoveredFiles() { return discoveredFiles; }
    public int eligibleFiles() { return eligibleFiles; }
    public int parsedFiles() { return parsedFiles; }
    public int excludedFiles() { return excludedFiles; }
    public int failedFiles() { return failedFiles; }

    public void countDiscovered() { discoveredFiles++; }
    public void countEligible() { eligibleFiles++; }
    public void countParsed() { parsedFiles++; }
    public void countExcluded() { excludedFiles++; }
    public void countFailed() { failedFiles++; }
}
