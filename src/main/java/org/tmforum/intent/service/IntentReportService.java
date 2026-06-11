package org.tmforum.intent.service;

import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.repositories.IntentReportRepository;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Business-logic layer for IntentReport resources.
 * Thin delegate over IntentReportRepository with parent-validation semantics.
 */
@Service
public class IntentReportService {

    private final IntentReportRepository reportRepository;

    public IntentReportService(IntentReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    public Map<String, Object> findById(String intentId, String reportId) {
        Map<String, Object> report = reportRepository.findById(intentId, reportId);
        if (report == null) {
            throw new NoSuchElementException(
                    "IntentReport not found: intentId=" + intentId + " reportId=" + reportId);
        }
        return report;
    }

    public List<Map<String, Object>> findAll(String intentId, int limit, int offset) {
        return reportRepository.findAll(intentId, limit, offset);
    }

    public long count(String intentId) {
        return reportRepository.count(intentId);
    }

    public boolean delete(String intentId, String reportId) {
        return reportRepository.delete(intentId, reportId);
    }

    public Map<String, Object> create(String intentId, Map<String, Object> data) {
        reportRepository.create(intentId, data);
        return data;
    }
}
